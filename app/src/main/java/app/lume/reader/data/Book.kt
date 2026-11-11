package app.lume.reader.data

import org.json.JSONArray
import org.json.JSONObject

enum class BookType { PDF, TEXT }

enum class ViewMode { TEXT, PAGES }

data class Book(
    val id: String,
    val title: String,
    val type: BookType,
    val pageCount: Int = 0,
    val hasText: Boolean = true,
    val totalWords: Int = 0,
    val addedAt: Long = 0,
    val lastOpened: Long = 0,
    val viewMode: ViewMode = ViewMode.TEXT,
    val paraIndex: Int = 0,
    val paraOffset: Int = 0,
    val page: Int = 0,
    val progress: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("type", type.name)
        .put("pageCount", pageCount)
        .put("hasText", hasText)
        .put("totalWords", totalWords)
        .put("addedAt", addedAt)
        .put("lastOpened", lastOpened)
        .put("viewMode", viewMode.name)
        .put("paraIndex", paraIndex)
        .put("paraOffset", paraOffset)
        .put("page", page)
        .put("progress", progress.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.optString("title", "Sem título"),
            type = runCatching { BookType.valueOf(o.optString("type")) }.getOrDefault(BookType.TEXT),
            pageCount = o.optInt("pageCount"),
            hasText = o.optBoolean("hasText", true),
            totalWords = o.optInt("totalWords"),
            addedAt = o.optLong("addedAt"),
            lastOpened = o.optLong("lastOpened"),
            viewMode = runCatching { ViewMode.valueOf(o.optString("viewMode")) }.getOrDefault(ViewMode.TEXT),
            paraIndex = o.optInt("paraIndex"),
            paraOffset = o.optInt("paraOffset"),
            page = o.optInt("page"),
            progress = o.optDouble("progress", 0.0).toFloat(),
        )
    }
}

/** Conteúdo carregado de um livro: parágrafos + onde cada página do PDF começa. */
class BookContent(
    val paragraphs: List<String>,
    /** pageStarts[p] = índice do primeiro parágrafo da página p (vazio para textos). */
    val pageStarts: IntArray,
    val pageCount: Int,
) {
    val cumWords: IntArray = TextTools.cumulativeWords(paragraphs)
    val hasText: Boolean get() = paragraphs.isNotEmpty()

    /** Sem texto (PDF escaneado), estima um número fixo de palavras por página. */
    val totalWords: Int
        get() = if (hasText) cumWords[paragraphs.size] else pageCount * WORDS_PER_SCANNED_PAGE

    fun wordsBeforePage(page: Int): Int {
        if (!hasText) return page * WORDS_PER_SCANNED_PAGE
        if (pageStarts.isEmpty()) return 0
        if (page >= pageStarts.size) return cumWords[paragraphs.size]
        return cumWords[pageStarts[page.coerceAtLeast(0)]]
    }

    fun pageOfParagraph(index: Int): Int {
        if (pageStarts.isEmpty()) return 0
        return TextTools.indexForValue(pageStarts, index)
    }

    fun paragraphOfPage(page: Int): Int {
        if (pageStarts.isEmpty() || paragraphs.isEmpty()) return 0
        return pageStarts[page.coerceIn(0, pageStarts.size - 1)].coerceAtMost(paragraphs.size - 1)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("paragraphs", JSONArray(paragraphs))
        .put("pageStarts", JSONArray().also { a -> pageStarts.forEach { a.put(it) } })
        .put("pageCount", pageCount)

    companion object {
        const val WORDS_PER_SCANNED_PAGE = 250

        fun fromJson(o: JSONObject): BookContent {
            val p = o.getJSONArray("paragraphs")
            val s = o.optJSONArray("pageStarts") ?: JSONArray()
            return BookContent(
                paragraphs = List(p.length()) { p.getString(it) },
                pageStarts = IntArray(s.length()) { s.getInt(it) },
                pageCount = o.optInt("pageCount"),
            )
        }
    }
}

/** Uma frase marcada pelo leitor. [start]/[end] são posições dentro do parágrafo [para]. */
data class Highlight(
    val id: String,
    val para: Int,
    val start: Int,
    val end: Int,
    val text: String,
    /** Página do PDF (começando em 1) onde a frase está; 0 para textos sem páginas. */
    val page: Int = 0,
    val createdAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("para", para).put("start", start).put("end", end)
        .put("text", text).put("page", page).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Highlight(
            id = o.getString("id"),
            para = o.optInt("para"),
            start = o.optInt("start"),
            end = o.optInt("end"),
            text = o.optString("text"),
            page = o.optInt("page"),
            createdAt = o.optLong("createdAt"),
        )
    }
}
