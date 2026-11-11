package app.lume.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

class ImportException(message: String) : Exception(message)

/** Guarda os livros no armazenamento interno do app: files/books/<id>/{meta.json, content.json, source.pdf, cover.png}. */
class Library(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "books").apply { mkdirs() }

    var books by mutableStateOf(listOf<Book>())
        private set

    /** Frases marcadas de cada livro (id do livro -> frases na ordem em que aparecem no texto). */
    var highlights by mutableStateOf(mapOf<String, List<Highlight>>())
        private set

    init {
        PDFBoxResourceLoader.init(appContext)
        books = (root.listFiles() ?: emptyArray()).mapNotNull { dir ->
            runCatching { Book.fromJson(JSONObject(File(dir, "meta.json").readText())) }.getOrNull()
        }.sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
        highlights = books.mapNotNull { book ->
            val list = runCatching {
                val array = JSONArray(File(dir(book.id), "highlights.json").readText())
                List(array.length()) { Highlight.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            if (list.isEmpty()) null else book.id to list
        }.toMap()
    }

    fun highlightsOf(bookId: String): List<Highlight> = highlights[bookId].orEmpty()

    private fun setHighlights(bookId: String, list: List<Highlight>) {
        val sorted = list.sortedWith(compareBy({ it.para }, { it.start }))
        highlights = if (sorted.isEmpty()) highlights - bookId else highlights + (bookId to sorted)
        runCatching {
            File(dir(bookId), "highlights.json")
                .writeText(JSONArray().also { a -> sorted.forEach { a.put(it.toJson()) } }.toString())
        }
    }

    fun addHighlight(bookId: String, para: Int, start: Int, end: Int, text: String, page: Int) {
        val h = Highlight(UUID.randomUUID().toString(), para, start, end, text, page, System.currentTimeMillis())
        // uma marcação nova substitui as que ela sobrepõe no mesmo parágrafo
        val kept = highlightsOf(bookId).filterNot { it.para == para && it.start < end && start < it.end }
        setHighlights(bookId, kept + h)
    }

    fun removeHighlight(bookId: String, id: String) {
        setHighlights(bookId, highlightsOf(bookId).filterNot { it.id == id })
    }

    /** Depois de editar um texto, reencontra cada frase marcada no texto novo (as que sumiram são descartadas). */
    private fun relocateHighlights(bookId: String, paragraphs: List<String>) {
        val old = highlightsOf(bookId)
        if (old.isEmpty()) return
        val moved = old.mapNotNull { h ->
            val order = (paragraphs.indices).sortedBy { kotlin.math.abs(it - h.para) }
            for (i in order) {
                val at = paragraphs[i].indexOf(h.text)
                if (at >= 0) return@mapNotNull h.copy(para = i, start = at, end = at + h.text.length)
            }
            null
        }
        setHighlights(bookId, moved)
    }

    private fun dir(id: String) = File(root, id)
    fun pdfFile(id: String) = File(dir(id), "source.pdf")
    fun coverFile(id: String) = File(dir(id), "cover.png")

    fun find(id: String): Book? = books.firstOrNull { it.id == id }

    /** Atualiza o livro em memória e grava o meta.json. */
    fun save(book: Book) {
        books = (listOf(book) + books.filter { it.id != book.id })
            .sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
        runCatching {
            dir(book.id).mkdirs()
            File(dir(book.id), "meta.json").writeText(book.toJson().toString())
        }
    }

    fun delete(id: String) {
        books = books.filter { it.id != id }
        highlights = highlights - id
        dir(id).deleteRecursively()
    }

    suspend fun loadContent(id: String): BookContent = withContext(Dispatchers.IO) {
        BookContent.fromJson(JSONObject(File(dir(id), "content.json").readText()))
    }

    private fun writeContent(id: String, content: BookContent) {
        dir(id).mkdirs()
        File(dir(id), "content.json").writeText(content.toJson().toString())
    }

    /** Cria (ou substitui, se [existingId] != null) um livro a partir de texto digitado/colado. */
    suspend fun saveText(title: String, text: String, existingId: String? = null): Book {
        val (book, paragraphs) = withContext(Dispatchers.IO) { writeTextBook(title, text, existingId) }
        save(book)
        if (existingId != null) relocateHighlights(book.id, paragraphs)
        return book
    }

    /** Grava os arquivos de um livro de texto e devolve o livro (ainda não registrado em [books]). */
    private fun writeTextBook(title: String, text: String, existingId: String?): Pair<Book, List<String>> {
        val paragraphs = TextTools.plainTextToParagraphs(text)
        if (paragraphs.isEmpty()) throw ImportException("O texto está vazio.")
        val content = BookContent(paragraphs, IntArray(0), 0)
        val old = existingId?.let { find(it) }
        val id = old?.id ?: UUID.randomUUID().toString()
        writeContent(id, content)
        File(dir(id), "source.txt").writeText(text)
        val now = System.currentTimeMillis()
        val book = (old ?: Book(id = id, title = title, type = BookType.TEXT, addedAt = now)).copy(
            title = title.ifBlank { "Sem título" },
            totalWords = content.totalWords,
            hasText = true,
            paraIndex = (old?.paraIndex ?: 0).coerceAtMost(paragraphs.size - 1),
        )
        return book to paragraphs
    }

    /** Texto original de um livro de texto, para edição. */
    suspend fun loadSourceText(id: String): String = withContext(Dispatchers.IO) {
        val f = File(dir(id), "source.txt")
        if (f.exists()) f.readText() else loadContent(id).paragraphs.joinToString("\n\n")
    }

    /** Importa um arquivo escolhido pelo usuário (PDF ou texto). [onProgress] vai de 0 a 1. */
    suspend fun importUri(uri: Uri, onProgress: (Float) -> Unit): Book {
        val book = withContext(Dispatchers.IO) { readUri(uri, onProgress) }
        save(book)
        return book
    }

    /** Lê o arquivo e grava os dados do livro no armazenamento do app (sem registrá-lo em [books]). */
    private fun readUri(uri: Uri, onProgress: (Float) -> Unit): Book {
        val resolver = appContext.contentResolver
        val name = displayName(uri)
        val mime = resolver.getType(uri).orEmpty()
        val id = UUID.randomUUID().toString()
        val tmp = File(appContext.cacheDir, "import-$id")
        return try {
            (resolver.openInputStream(uri) ?: throw ImportException("Não consegui abrir o arquivo."))
                .use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val isPdf = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) || looksLikePdf(tmp)
            val title = name.substringBeforeLast('.').ifBlank { "Sem título" }
            if (isPdf) {
                importPdf(id, title, tmp, onProgress)
            } else {
                if (tmp.length() > MAX_TEXT_BYTES) throw ImportException("Arquivo de texto grande demais.")
                val text = decodeText(tmp.readBytes())
                if (text.count { it == '\u0000' } > 0) {
                    throw ImportException("Esse formato não é suportado. Use PDF ou texto (.txt).")
                }
                writeTextBook(title, text, null).first
            }
        } catch (e: ImportException) {
            dir(id).deleteRecursively()
            throw e
        } catch (e: IOException) {
            dir(id).deleteRecursively()
            throw ImportException("Erro ao ler o arquivo: ${e.message ?: "desconhecido"}")
        } catch (e: OutOfMemoryError) {
            dir(id).deleteRecursively()
            throw ImportException("O arquivo é grande demais para a memória do aparelho.")
        } finally {
            tmp.delete()
        }
    }

    private fun importPdf(id: String, title: String, tmp: File, onProgress: (Float) -> Unit): Book {
        dir(id).mkdirs()
        val pdf = pdfFile(id)
        tmp.copyTo(pdf, overwrite = true)

        // 1) abre com o renderizador do Android (dá a contagem de páginas e a capa)
        val pageCount: Int
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val w = 360
                            val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 1200)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            coverFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                            bmp.recycle()
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            throw ImportException("Esse PDF é protegido por senha.")
        } catch (e: IOException) {
            throw ImportException("Não consegui abrir esse PDF (arquivo corrompido?).")
        }
        if (pageCount == 0) throw ImportException("Esse PDF não tem páginas.")

        // 2) extrai o texto página a página, para o modo de leitura com fonte ajustável
        val paragraphs = ArrayList<String>()
        val pageStarts = IntArray(pageCount)
        try {
            PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val stripper = PDFTextStripper()
                val n = minOf(pageCount, doc.numberOfPages)
                for (p in 0 until pageCount) {
                    pageStarts[p] = paragraphs.size
                    if (p < n) {
                        stripper.startPage = p + 1
                        stripper.endPage = p + 1
                        val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                        paragraphs.addAll(TextTools.pdfPageToParagraphs(text))
                    }
                    onProgress((p + 1f) / pageCount)
                }
            }
        } catch (e: Exception) {
            // sem texto extraível: o livro ainda abre no modo "páginas"
            paragraphs.clear()
        }
        val totalChars = paragraphs.sumOf { it.length }
        val hasText = totalChars >= pageCount * MIN_CHARS_PER_PAGE
        val content = if (hasText) {
            BookContent(paragraphs, pageStarts, pageCount)
        } else {
            BookContent(emptyList(), IntArray(0), pageCount)
        }
        writeContent(id, content)
        return Book(
            id = id,
            title = title,
            type = BookType.PDF,
            pageCount = pageCount,
            hasText = hasText,
            totalWords = content.totalWords,
            addedAt = System.currentTimeMillis(),
            viewMode = if (hasText) ViewMode.TEXT else ViewMode.PAGES,
        )
    }

    private fun looksLikePdf(f: File): Boolean = runCatching {
        f.inputStream().use { s ->
            val head = ByteArray(5)
            s.read(head) == 5 && String(head, Charsets.US_ASCII) == "%PDF-"
        }
    }.getOrDefault(false)

    private fun decodeText(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (e: CharacterCodingException) {
            // muito .txt em português ainda está em Latin-1/Windows-1252
            String(bytes, charset("windows-1252"))
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Sem título"
    }

    companion object {
        private const val MAX_TEXT_BYTES = 30L * 1024 * 1024
        private const val MIN_CHARS_PER_PAGE = 40
    }
}
