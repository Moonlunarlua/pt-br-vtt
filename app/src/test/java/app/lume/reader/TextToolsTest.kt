package app.lume.reader

import app.lume.reader.data.ReadingSpeed
import app.lume.reader.data.TextTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    @Test
    fun countsWords() {
        assertEquals(0, TextTools.countWords("   "))
        assertEquals(4, TextTools.countWords("  Era uma vez,\n\tum  "))
    }

    @Test
    fun plainTextSplitsOnLines() {
        val p = TextTools.plainTextToParagraphs("Primeiro parágrafo.\r\n\r\nSegundo   parágrafo.\nTerceiro.")
        assertEquals(listOf("Primeiro parágrafo.", "Segundo parágrafo.", "Terceiro."), p)
        assertTrue(TextTools.plainTextToParagraphs(" \n \n").isEmpty())
    }

    @Test
    fun pdfLinesAreJoinedIntoParagraphs() {
        val page = """
            Capítulo Um
            Era uma vez um leitor que queria muito terminar o seu livro an-
            tes do fim do mês, mas nunca sabia quanto tempo ainda faltava
            para chegar lá.
            — Quanto falta? — perguntava ele, toda noite, olhando para a
            pilha de páginas que ainda restava na mesa de cabeceira dele.
        """.trimIndent()
        val p = TextTools.pdfPageToParagraphs(page)
        assertEquals(3, p.size)
        assertEquals("Capítulo Um", p[0])
        assertTrue(p[1].startsWith("Era uma vez"))
        assertTrue("hifenização desfeita", p[1].contains("livro antes do fim"))
        assertTrue(p[1].endsWith("chegar lá."))
        assertTrue(p[2].startsWith("— Quanto falta?"))
    }

    @Test
    fun longParagraphsAreSplitWithoutLosingText() {
        val sentence = "Esta é uma frase de tamanho médio para o teste. "
        val long = sentence.repeat(100).trim()
        val parts = TextTools.splitLong(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 1400 })
        assertEquals(TextTools.countWords(long), parts.sumOf { TextTools.countWords(it) })
        val noSpaces = "x".repeat(5000)
        assertEquals(5000, TextTools.splitLong(noSpaces).sumOf { it.length })
    }

    @Test
    fun cumulativeWordsAndLookup() {
        val acc = TextTools.cumulativeWords(listOf("a b c", "d e", "f"))
        assertEquals(listOf(0, 3, 5, 6), acc.toList())
        assertEquals(0, TextTools.indexForValue(acc, 0))
        assertEquals(0, TextTools.indexForValue(acc, 2))
        assertEquals(1, TextTools.indexForValue(acc, 3))
        assertEquals(3, TextTools.indexForValue(acc, 99))
    }

    @Test
    fun formatsDurations() {
        assertEquals("menos de 1 min", TextTools.formatDuration(0.2))
        assertEquals("45 min", TextTools.formatDuration(45.0))
        assertEquals("2 h", TextTools.formatDuration(120.0))
        assertEquals("1 h 12 min", TextTools.formatDuration(72.4))
    }

    @Test
    fun speedUsesDefaultUntilCalibrated() {
        val s = ReadingSpeed()
        assertFalse(s.isCalibrated)
        assertEquals(200.0, s.wpm, 0.0)
    }

    @Test
    fun speedMeasuresSteadyReading() {
        // lê parado 25 s e rola 100 palavras nos 5 s seguintes => 200 ppm
        val s = ReadingSpeed()
        var t = 0L
        var pos = 0.0
        s.sample(pos, t)
        repeat(10) {
            repeat(5) { t += 5000; s.sample(pos, t) }
            t += 5000; pos += 100; s.sample(pos, t)
        }
        assertTrue(s.isCalibrated)
        assertEquals(200.0, s.wpm, 1.0)
    }

    @Test
    fun speedIgnoresJumpsBackwardsAndLongPauses() {
        val s = ReadingSpeed()
        var t = 0L
        s.sample(0.0, t)
        t += 5000; s.sample(20000.0, t) // arrastou a barra para longe
        t += 5000; s.sample(100.0, t) // voltou
        assertEquals(0.0, s.totalWords, 0.0)
        // 10 min parado (largou o celular) e depois avança: a pausa não entra na conta
        repeat(120) { t += 5000; s.sample(100.0, t) }
        t += 5000; s.sample(150.0, t)
        assertEquals(50.0, s.totalWords, 0.0)
        assertEquals(5000.0, s.totalMs, 0.0)
        // depois de resetBaseline, a primeira amostra só marca posição
        s.resetBaseline()
        t += 5000; s.sample(9000.0, t)
        assertEquals(50.0, s.totalWords, 0.0)
    }
}
