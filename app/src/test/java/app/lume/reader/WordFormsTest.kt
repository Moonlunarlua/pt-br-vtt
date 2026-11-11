package app.lume.reader

import app.lume.reader.data.WordForms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFormsTest {
    @Test
    fun keyDropsCaseAndAccents() {
        assertEquals("acao", WordForms.key("Ação"))
        assertEquals("efemero", WordForms.key("efêmero"))
        assertEquals(WordForms.key("efémero"), WordForms.key("EFÊMERO"))
        assertEquals("frequente", WordForms.key("freqüente"))
    }

    @Test
    fun cleansPunctuation() {
        assertEquals("casa", WordForms.cleanWord(" «casa», "))
        assertEquals("guarda-chuva", WordForms.cleanWord("(guarda-chuva)."))
    }

    @Test
    fun findsWordUnderFinger() {
        val text = "— Disse-me que o guarda-chuva d'água sumiu!"
        fun word(at: Int) = WordForms.wordAt(text, at)?.let { text.substring(it.first, it.last + 1) }
        assertEquals("Disse-me", word(text.indexOf("Disse") + 2))
        assertEquals("guarda-chuva", word(text.indexOf("chuva")))
        assertEquals("d'água", word(text.indexOf("água")))
        assertEquals("sumiu", word(text.indexOf("!"))) // logo depois da palavra
        assertNull(word(0)) // travessão
    }

    @Test
    fun findsSentenceUnderFinger() {
        val text = "Era noite. O vento, frio e soturno, batia nas janelas! Ninguém dormia."
        fun sentence(at: Int) = WordForms.sentenceAt(text, at).let { text.substring(it.first, it.last + 1) }
        assertEquals("Era noite.", sentence(2))
        assertEquals("O vento, frio e soturno, batia nas janelas!", sentence(text.indexOf("soturno")))
        assertEquals("Ninguém dormia.", sentence(text.length - 1))
        assertTrue(WordForms.sentenceAt("", 0).isEmpty())
    }

    @Test
    fun guessesDictionaryForms() {
        fun has(word: String, expected: String) =
            assertTrue("$word -> $expected", WordForms.key(expected) in WordForms.candidates(WordForms.key(word)))
        has("corações", "coração")
        has("animais", "animal")
        has("papéis", "papel")
        has("homens", "homem")
        has("flores", "flor")
        has("professoras", "professor")
        has("belíssima", "belo")
        has("amabilíssimo", "amável")
        has("casinhas", "casa")
        has("rapidamente", "rápido")
        has("fiquei", "ficar")
        has("cheguei", "chegar")
        has("cantávamos", "cantar")
        has("partiríamos", "partir")
        has("fazê-lo", "fazer")
        has("disse-me", "disse")
        has("dir-se-ia", "diria")
        has("fizeram-no", "fizeram")
        has("acção", "ação")
        has("óptimo", "ótimo")
        has("pharmacia", "farmácia")
        has("ella", "ela")
    }
}
