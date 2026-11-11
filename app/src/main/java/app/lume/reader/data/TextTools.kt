package app.lume.reader.data

/** Funções puras de texto (sem Android), para dar pra testar na JVM. */
object TextTools {

    private const val MAX_PARAGRAPH_CHARS = 1400
    private val terminators = charArrayOf('.', '!', '?', ':', ';', '"', '”', '»', ')', '…')
    private val dialogueStarts = charArrayOf('—', '–', '•')

    fun countWords(s: String): Int {
        var n = 0
        var inWord = false
        for (c in s) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                n++
            }
        }
        return n
    }

    /** Texto digitado/colado ou .txt: cada linha em branco (ou quebra de linha) vira parágrafo. */
    fun plainTextToParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()
        // Se o arquivo quebra as linhas numa largura fixa (hard wrap), junta as linhas como num PDF.
        val hasBlankSeparators = lines.any { it.isBlank() }
        val lengths = nonBlank.map { it.trimEnd().length }.sorted()
        val p90 = lengths[(lengths.size * 9) / 10 - if (lengths.size >= 10) 1 else 0]
        val nearMax = lengths.count { it >= p90 - 12 && it <= p90 + 2 }
        val hardWrapped = hasBlankSeparators && nonBlank.size >= 20 && p90 in 50..100 &&
            nearMax > nonBlank.size / 2
        return if (hardWrapped) {
            joinLines(lines)
        } else {
            nonBlank.flatMap { splitLong(it.trim().replace(Regex("[ \\t]+"), " ")) }
        }
    }

    /** Texto extraído de uma página de PDF: remonta parágrafos a partir das linhas. */
    fun pdfPageToParagraphs(pageText: String): List<String> =
        joinLines(pageText.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    private fun joinLines(rawLines: List<String>): List<String> {
        val lines = rawLines.map { it.trim().replace(Regex("\\s+"), " ") }
        val lengths = lines.filter { it.isNotEmpty() }.map { it.length }.sorted()
        if (lengths.isEmpty()) return emptyList()
        val typical = lengths[(lengths.size * 3) / 4]
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out.addAll(splitLong(cur.toString().trim()))
            cur.setLength(0)
        }
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (cur.isNotEmpty() && line[0] in dialogueStarts) flush()
            if (cur.isEmpty()) {
                cur.append(line)
            } else if (cur.last() == '-' && cur.length >= 2 && cur[cur.length - 2].isLetter() &&
                line[0].isLowerCase()
            ) {
                // palavra hifenizada no fim da linha
                cur.setLength(cur.length - 1)
                cur.append(line)
            } else {
                cur.append(' ').append(line)
            }
            val endsSentence = line.last() in terminators
            val short = line.length < typical * 0.85
            val looksLikeHeading = short && line.length <= 60 && !endsSentence &&
                (i + 1 >= lines.size || lines[i + 1].firstOrNull()?.isUpperCase() == true) &&
                line.first().isUpperCase() && cur.length == line.length
            if ((endsSentence && short) || looksLikeHeading) flush()
        }
        flush()
        return out
    }

    /** Quebra parágrafos gigantes em pedaços menores, em fim de frase quando der. */
    fun splitLong(p: String): List<String> {
        if (p.length <= MAX_PARAGRAPH_CHARS) return listOf(p)
        val out = ArrayList<String>()
        var start = 0
        while (p.length - start > MAX_PARAGRAPH_CHARS) {
            val limit = start + MAX_PARAGRAPH_CHARS
            var cut = -1
            var i = limit
            while (i > start + MAX_PARAGRAPH_CHARS / 2) {
                if (p[i - 1] in ".!?…" && p[i].isWhitespace()) {
                    cut = i
                    break
                }
                i--
            }
            if (cut < 0) {
                cut = p.lastIndexOf(' ', limit).takeIf { it > start } ?: limit
            }
            out.add(p.substring(start, cut).trim())
            start = cut
        }
        val rest = p.substring(start).trim()
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /** Soma acumulada de palavras: result[i] = palavras antes do parágrafo i; result[n] = total. */
    fun cumulativeWords(paragraphs: List<String>): IntArray {
        val acc = IntArray(paragraphs.size + 1)
        for (i in paragraphs.indices) acc[i + 1] = acc[i] + countWords(paragraphs[i])
        return acc
    }

    /** Maior índice i tal que acc[i] <= value. */
    fun indexForValue(acc: IntArray, value: Int): Int {
        var lo = 0
        var hi = acc.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (acc[mid] <= value) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun formatDuration(minutes: Double): String {
        val m = Math.round(minutes).toInt()
        return when {
            m < 1 -> "menos de 1 min"
            m < 60 -> "$m min"
            else -> {
                val h = m / 60
                val r = m % 60
                if (r == 0) "${h} h" else "${h} h ${r} min"
            }
        }
    }
}
