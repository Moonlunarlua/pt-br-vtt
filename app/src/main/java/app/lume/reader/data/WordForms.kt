package app.lume.reader.data

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Funções puras para achar a palavra/frase tocada e para "adivinhar" o verbete de dicionário
 * de uma palavra flexionada (plural, feminino, verbo conjugado, pronome grudado, grafia antiga...).
 */
object WordForms {
    private val marks = Regex("\\p{Mn}+")

    /** Chave de busca: minúsculas e sem acentos (igual à coluna `key` do banco do dicionário). */
    fun key(word: String): String =
        marks.replace(Normalizer.normalize(word.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            .replace('’', '\'')

    /** Tira pontuação/aspas das pontas: "«casa»," -> "casa". */
    fun cleanWord(raw: String): String =
        raw.trim().trim { !it.isLetterOrDigit() }

    /** Intervalo [início, fim) da palavra na posição [offset], ou null se não há palavra ali. */
    fun wordAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var i = offset.coerceIn(0, text.length - 1)
        if (!text[i].isLetterOrDigit()) {
            if (i > 0 && text[i - 1].isLetterOrDigit()) i-- else return null
        }
        fun inside(j: Int): Boolean {
            if (j < 0 || j >= text.length) return false
            val c = text[j]
            if (c.isLetterOrDigit()) return true
            // hífen ou apóstrofo só contam no meio da palavra (guarda-chuva, d'água, disse-me)
            return (c == '-' || c == '\'' || c == '’') &&
                j > 0 && j < text.length - 1 && text[j - 1].isLetterOrDigit() && text[j + 1].isLetterOrDigit()
        }
        var start = i
        while (inside(start - 1)) start--
        var end = i + 1
        while (inside(end)) end++
        return start until end
    }

    /** Intervalo [início, fim) da frase que contém [offset], sem espaços nas pontas. */
    fun sentenceAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val o = offset.coerceIn(0, text.length - 1)
        val it = BreakIterator.getSentenceInstance(Locale("pt", "BR"))
        it.setText(text)
        var end = it.following(o)
        if (end == BreakIterator.DONE) end = text.length
        var start = it.previous()
        if (start == BreakIterator.DONE) start = 0
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return start until end
    }

    private val clitics = setOf(
        "me", "te", "se", "nos", "vos", "lhe", "lhes", "o", "a", "os", "as", "lo", "la", "los", "las",
        "no", "na", "nas", "mo", "ma", "mos", "mas", "to", "ta", "tos", "tas", "lho", "lha", "lhos", "lhas",
    )

    private val verbEndings = listOf(
        "ariamos", "eriamos", "iriamos", "assemos", "essemos", "issemos",
        "aramos", "eramos", "iramos", "avamos", "aremos", "eremos", "iremos",
        "ariam", "eriam", "iriam", "assem", "essem", "issem", "astes", "estes", "istes", "armos", "ermos", "irmos",
        "iamos", "aram", "eram", "iram", "avam", "arao", "erao", "irao", "arei", "erei", "irei", "aras", "eras", "iras",
        "aria", "eria", "iria", "asse", "esse", "isse", "ando", "endo", "indo", "ados", "idos", "adas", "idas",
        "amos", "emos", "imos", "aste", "este", "iste", "avas", "arem", "erem", "irem", "ares", "eres", "ires",
        "ara", "era", "ira", "ado", "ido", "ada", "ida", "ava", "iam", "ias",
        "ia", "ou", "eu", "iu", "ei", "ais", "eis", "am", "em", "as", "es", "ar", "er", "ir", "a", "e", "o", "i",
    )

    /**
     * Chaves candidatas a verbete para a chave [k], da mais provável para a menos provável.
     * São só palpites: quem chama confere cada uma no dicionário.
     */
    fun candidates(k: String): List<String> {
        val out = LinkedHashSet<String>()
        val bases = LinkedHashSet<String>()

        // pronomes grudados: disse-me, fazê-lo, dir-se-ia, fizeram-no
        val parts = k.split('-').filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val tail = parts.drop(1)
            val head = parts[0]
            when {
                tail.all { it in clitics } -> {
                    if (tail[0].startsWith("l") && tail[0] != "lhe" && tail[0] != "lhes") {
                        bases += head + "r"; bases += head + "s"; bases += head + "z"
                    }
                    bases += head
                }
                tail.dropLast(1).isNotEmpty() && tail.dropLast(1).all { it in clitics } ->
                    bases += head + tail.last() // mesóclise: dir-se-ia -> diria
                else -> parts.filter { it.length >= 3 }.forEach { bases += it }
            }
        }
        bases += k
        out += bases

        for (w in bases.toList()) {
            oldSpelling(w).forEach { out += it }
            nominal(w).forEach { out += it }
        }
        for (w in bases.toList()) verbal(w).forEach { out += it }
        out.remove(k)
        return out.filter { it.length >= 2 }
    }

    /** Grafias antigas ou de Portugal: acção, óptimo, pharmacia, ella. */
    private fun oldSpelling(w: String): List<String> {
        val out = ArrayList<String>()
        val noMute = w.replace(Regex("[cp](?=[ct])"), "")
        if (noMute != w) out += noMute
        val modern = w.replace("ph", "f").replace("th", "t").replace("y", "i")
            .replace(Regex("([b-df-hj-np-tv-z])\\1")) { m -> if (m.value == "rr" || m.value == "ss") m.value else m.value.take(1) }
        if (modern != w) out += modern
        return out
    }

    private fun MutableList<String>.swap(w: String, suffix: String, vararg replacements: String) {
        if (w.length > suffix.length + 1 && w.endsWith(suffix)) {
            val stem = w.dropLast(suffix.length)
            replacements.forEach { add(stem + it) }
        }
    }

    /** Plural, feminino, diminutivo/aumentativo, superlativo e advérbios em -mente. */
    private fun nominal(w: String): List<String> {
        val out = ArrayList<String>()
        out.swap(w, "mente", "", "o")
        if (w.endsWith("mente") && w.length > 7 && w[w.length - 6] == 'a') out += w.dropLast(6) + "o"
        for (s in listOf("bilissimo", "bilissima", "bilissimos", "bilissimas")) out.swap(w, s, "vel")
        for (s in listOf("issimos", "issimas", "issimo", "issima")) out.swap(w, s, "o", "", "e")
        for (s in listOf("zinhos", "zinhas", "zinho", "zinha", "zitos", "zitas", "zito", "zita")) out.swap(w, s, "", "o", "a")
        for (s in listOf("inhos", "inhas", "inho", "inha", "itos", "itas", "ito", "ita")) out.swap(w, s, "o", "a", "", "e")
        out.swap(w, "oes", "ao"); out.swap(w, "aes", "ao"); out.swap(w, "aos", "ao")
        out.swap(w, "ais", "al"); out.swap(w, "eis", "el", "il"); out.swap(w, "ois", "ol"); out.swap(w, "uis", "ul")
        out.swap(w, "ns", "m")
        out.swap(w, "es", "", "e")
        out.swap(w, "s", "")
        out.swap(w, "oras", "or"); out.swap(w, "ora", "or")
        out.swap(w, "esas", "es"); out.swap(w, "esa", "es")
        out.swap(w, "eias", "eu"); out.swap(w, "eia", "eu")
        out.swap(w, "onas", "ao"); out.swap(w, "ona", "ao")
        out.swap(w, "as", "o"); out.swap(w, "a", "o")
        out.swap(w, "zao", "", "o", "a")
        return out
    }

    /** Verbo conjugado -> infinitivos possíveis (-ar, -er, -ir). */
    private fun verbal(w: String): List<String> {
        val out = ArrayList<String>()
        for (ending in verbEndings) {
            if (w.length <= ending.length + 1 || !w.endsWith(ending)) continue
            val stem = w.dropLast(ending.length)
            val stems = arrayListOf(stem)
            when {
                stem.endsWith("qu") -> stems += stem.dropLast(2) + "c" // fiquei -> ficar
                stem.endsWith("gu") -> stems += stem.dropLast(1) // cheguei -> chegar
                stem.endsWith("j") -> stems += stem.dropLast(1) + "g" // finjo -> fingir
                stem.endsWith("c") -> stems += stem.dropLast(1) + "qu"
            }
            for (s in stems) {
                out += s + "ar"; out += s + "er"; out += s + "ir"
            }
        }
        return out
    }
}
