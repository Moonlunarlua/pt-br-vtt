package app.lume.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

data class DictEntry(
    val word: String,
    val pos: String?,
    val tags: String?,
    val senses: List<String>,
    val synonyms: String?,
    val etymology: String?,
    /** Preenchido quando se chegou aqui por uma forma flexionada, ex.: "plural de gato". */
    val via: String? = null,
)

data class DictResult(
    val query: String,
    val entries: List<DictEntry>,
    /** Palavras parecidas, quando nada foi encontrado. */
    val suggestions: List<String> = emptyList(),
)

/**
 * Dicionário de português offline, embutido no app (assets/dicionario.bin, gerado por
 * tools/build_dictionary.py a partir do Wikcionário). Na primeira consulta o banco é
 * descompactado para o armazenamento interno; nada usa a internet.
 */
class Dictionary(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase {
        db?.let { return it }
        val file = File(appContext.filesDir, "dicionario-v$VERSION.db")
        if (!file.exists()) {
            appContext.filesDir.listFiles { f -> f.name.startsWith("dicionario-") }?.forEach { it.delete() }
            val tmp = File(appContext.filesDir, "dicionario-v$VERSION.db.tmp")
            GZIPInputStream(appContext.assets.open("dicionario.bin")).use { input ->
                tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            if (!tmp.renameTo(file)) throw java.io.IOException("não foi possível preparar o dicionário")
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    suspend fun lookup(raw: String): DictResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val word = WordForms.cleanWord(raw)
            if (word.isEmpty()) return@withLock DictResult(word, emptyList())
            val d = open()
            val key = WordForms.key(word)
            var entries = resolve(d, key, word)
            if (entries.isEmpty()) {
                for (candidate in WordForms.candidates(key)) {
                    entries = resolve(d, candidate, word)
                    if (entries.isNotEmpty()) break
                }
            }
            if (entries.isNotEmpty()) {
                DictResult(word, followRedirects(d, entries).take(MAX_ENTRIES))
            } else {
                DictResult(word, emptyList(), suggestions(d, key))
            }
        }
    }

    private fun entriesFor(d: SQLiteDatabase, key: String): List<DictEntry> {
        val out = ArrayList<DictEntry>()
        d.rawQuery("SELECT word, pos, tags, senses, syn, ety FROM entry WHERE key = ? LIMIT 20", arrayOf(key)).use { c ->
            while (c.moveToNext()) {
                out += DictEntry(
                    word = c.getString(0),
                    pos = c.getString(1),
                    tags = c.getString(2),
                    senses = c.getString(3).split('\n').filter { it.isNotBlank() },
                    synonyms = c.getString(4),
                    etymology = c.getString(5),
                )
            }
        }
        return out
    }

    /** Verbetes da própria chave + verbetes de origem quando a chave é uma forma flexionada. */
    private fun resolve(d: SQLiteDatabase, key: String, typed: String, depth: Int = 0): List<DictEntry> {
        val direct = entriesFor(d, key).sortedBy { e ->
            when {
                e.word == typed -> 0
                e.word.equals(typed, ignoreCase = true) -> 1
                e.word.firstOrNull()?.isLowerCase() == true -> 2
                else -> 3
            }
        }
        val out = ArrayList(direct)
        if (depth >= 2) return out
        val lemmas = LinkedHashMap<String, MutableList<String>>()
        d.rawQuery(
            "SELECT f.lemma, t.text FROM form f JOIN tpl t ON t.id = f.tpl WHERE f.key = ? LIMIT 40",
            arrayOf(key),
        ).use { c ->
            while (c.moveToNext()) {
                lemmas.getOrPut(c.getString(0)) { ArrayList() } += c.getString(1).replace("X", c.getString(0))
            }
        }
        for ((lemma, notes) in lemmas) {
            val lemmaKey = WordForms.key(lemma)
            if (lemmaKey == key) continue
            val via = notes.distinct().take(3).joinToString("; ")
            val found = resolve(d, lemmaKey, lemma, depth + 1)
                .filter { it.word.equals(lemma, ignoreCase = true) || it.via != null }
            // para "fizeram" interessa o verbo "fazer", não um eventual substantivo homógrafo
            val preferred = if (via.contains("verbo")) found.filter { it.pos?.startsWith("Verbo") == true } else found
            for (e in preferred.ifEmpty { found }) {
                if (out.none { it.word == e.word && it.pos == e.pos }) out += e.copy(via = e.via ?: via)
            }
        }
        return out
    }

    private val redirect = Regex("^(?:\\([^)]*\\) )?(?:ver|vide|vide em|o mesmo que|forma alternativa de|variante de) ([\\p{L}-]+)\\.?$")

    /** "efêmero: ver efémero" -> acrescenta o verbete apontado. */
    private fun followRedirects(d: SQLiteDatabase, entries: List<DictEntry>): List<DictEntry> {
        val out = ArrayList(entries)
        for (e in entries) {
            val target = e.senses.singleOrNull()?.let { redirect.find(it)?.groupValues?.get(1) } ?: continue
            for (t in entriesFor(d, WordForms.key(target))) {
                if (out.none { it.word == t.word && it.pos == t.pos }) out += t
            }
        }
        return out
    }

    private fun suggestions(d: SQLiteDatabase, key: String): List<String> {
        val out = LinkedHashSet<String>()
        var prefix = key
        while (prefix.length >= 3 && out.size < MAX_SUGGESTIONS) {
            d.rawQuery(
                "SELECT DISTINCT word FROM entry WHERE key >= ? AND key < ? ORDER BY length(key), key LIMIT 12",
                arrayOf(prefix, prefix + "￿"),
            ).use { c ->
                while (c.moveToNext() && out.size < MAX_SUGGESTIONS) {
                    val w = c.getString(0)
                    if (!w.contains(' ')) out += w
                }
            }
            prefix = prefix.dropLast(1)
        }
        return out.toList()
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_ENTRIES = 8
        private const val MAX_SUGGESTIONS = 6
    }
}
