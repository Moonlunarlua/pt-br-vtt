package app.lume.reader

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import app.lume.reader.data.Settings
import app.lume.reader.data.TextTools
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = File(System.getProperty("shots.dir") ?: return).apply { mkdirs() }
        runCatching {
            rule.waitForIdle()
            rule.activityRule.scenario.onActivity { activity ->
                // desenha todas as janelas (a tela + painéis/diálogos abertos por cima)
                val base = activity.window.decorView
                val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                for (view in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    val at = IntArray(2).also { view.getLocationOnScreen(it) }
                    canvas.save()
                    canvas.translate(at[0].toFloat(), at[1].toFloat())
                    view.draw(canvas)
                    canvas.restore()
                }
                File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.onFailure { println("screenshot $name failed: $it") }
    }

    @Test
    fun writeTextThenReadIt() {
        rule.onNodeWithText("Sua biblioteca está vazia").assertExists()
        shot("1-vazia")
        rule.onNodeWithText("Colar um texto").performClick()

        val body = (1..60).joinToString("\n\n") { "Parágrafo número $it. " + "Era uma vez um texto de teste bem comprido. ".repeat(6) }
        rule.onNodeWithText("Título").performTextInput("Meu texto")
        rule.onNodeWithText("Texto").performTextInput(body)
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("0% lido").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Parágrafo número 1.", substring = true).assertExists()

        // rodapé com porcentagem e tempo restante
        rule.onNodeWithText("0% lido").assertExists()
        rule.onNodeWithText("faltam", substring = true).assertExists()
        shot("2-leitor")

        // pinça aumenta a letra
        val settings = Settings(RuntimeEnvironment.getApplication())
        val before = settings.fontSize
        rule.onRoot().performTouchInput {
            pinch(Offset(centerX - 50, centerY), Offset(centerX - 300, centerY), Offset(centerX + 50, centerY), Offset(centerX + 300, centerY))
        }
        rule.waitForIdle()
        assertTrue(Settings(RuntimeEnvironment.getApplication()).fontSize > before)
        shot("3-letra-maior")

        // toque mostra as barras; "Aa" abre os ajustes; troca para o modo escuro
        rule.onRoot().performTouchInput { click(center) }
        rule.onNodeWithText("Aa").performClick()
        rule.onNodeWithContentDescription("Modo Escuro").performClick()
        rule.onNodeWithText("Lora").performClick()
        rule.waitForIdle()
        shot("4-ajustes")
        val after = Settings(RuntimeEnvironment.getApplication())
        assertEquals("DARK", after.theme.name)
        assertEquals("lora", after.fontKey)

        // volta para a biblioteca: o livro aparece lá
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Minha biblioteca").assertExists()
        rule.onAllNodesWithText("Meu texto")[0].assertExists()
        shot("5-biblioteca")
    }

    @Test
    fun tapOnTheSidesTurnsOneScreenAtATime() {
        // liga o modo "tocar nas laterais" nos ajustes
        rule.onNodeWithText("Aa").performClick()
        rule.onNodeWithText("Tocar nas laterais").performClick()
        rule.waitForIdle()
        assertTrue(Settings(RuntimeEnvironment.getApplication()).tapToTurn)
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithText("Colar um texto").performClick()
        val body = (1..40).joinToString("\n\n") { "Parágrafo número $it. " + "Era uma vez um texto de teste bem comprido. ".repeat(6) }
        rule.onNodeWithText("Título").performTextInput("Paginado")
        rule.onNodeWithText("Texto").performTextInput(body)
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("0% lido").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Parágrafo número 1.", substring = true).assertExists()
        shot("6-paginado-tela1")

        // toque na direita: avança uma tela inteira
        rule.onRoot().performTouchInput { click(Offset(width * 0.9f, centerY)) }
        rule.waitForIdle()
        shot("7-paginado-tela2")
        rule.onRoot().performTouchInput { click(Offset(width * 0.9f, centerY)) }
        rule.waitForIdle()
        shot("8-paginado-tela3")
        rule.onNodeWithText("Parágrafo número 1.", substring = true).assertDoesNotExist()
        rule.onNodeWithText("0% lido").assertDoesNotExist()

        // toque na esquerda duas vezes: volta para o começo
        repeat(2) {
            rule.onRoot().performTouchInput { click(Offset(width * 0.1f, centerY)) }
            rule.waitForIdle()
        }
        rule.onNodeWithText("Parágrafo número 1.", substring = true).assertExists()
        rule.onNodeWithText("0% lido").assertExists()

        // toque no meio abre o menu em vez de virar a página
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        rule.onNodeWithText("Paginado").assertExists()
        rule.onNodeWithText("Parágrafo número 1.", substring = true).assertExists()
    }

    @Test
    fun markSentenceThenFindItOnTheHomeTab() {
        rule.onNodeWithText("Colar um texto").performClick()
        val body = "Era noite fechada. O vento, frio e soturno, batia nas janelas da casa! Ninguém dormia.\n\n" +
            (2..30).joinToString("\n\n") { "Parágrafo número $it. " + "Um texto de teste bem comprido. ".repeat(6) }
        rule.onNodeWithText("Título").performTextInput("Fundação e Império")
        rule.onNodeWithText("Texto").performTextInput(body)
        rule.onNodeWithText("Salvar").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("0% lido").fetchSemanticsNodes().isNotEmpty() }

        // segura o dedo em cima de "soturno": abre o painel com a frase e a definição da palavra
        val paragraph = rule.onNodeWithText("Era noite fechada.", substring = true)
        val layout = paragraph.fetchSemanticsNode().let { node ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(results)
            results.first()
        }
        val at = layout.layoutInput.text.text.indexOf("soturno") + 3
        val box = layout.getBoundingBox(at)
        paragraph.performTouchInput { longClick(box.center) }
        rule.waitUntil(30_000) { rule.onAllNodesWithText("Marcar frase").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("“O vento, frio e soturno, batia nas janelas da casa!”").assertExists()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("sombrio, tristonho").fetchSemanticsNodes().isNotEmpty() }
        shot("9-frase-e-dicionario")

        rule.onNodeWithText("Marcar frase").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("Marcar frase").fetchSemanticsNodes().isEmpty() }
        shot("10-frase-marcada")

        // de volta à tela inicial, aba "Frases marcadas"
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Frases marcadas").performClick()
        rule.onNodeWithText("Fundação e Império").assertExists()
        rule.onNodeWithText("1 frase marcada").assertExists()
        shot("11-aba-frases")

        rule.onNodeWithText("Fundação e Império").performClick()
        rule.onNodeWithText("O vento, frio e soturno, batia nas janelas da casa!").assertExists()
        shot("12-lista-frases")

        // tocar na frase abre o livro nela
        rule.onNodeWithText("O vento, frio e soturno, batia nas janelas da casa!").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("Era noite fechada.", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // segurar de novo na mesma frase permite remover a marcação
        rule.onNodeWithText("Era noite fechada.", substring = true).performTouchInput { longClick(box.center) }
        rule.waitUntil(30_000) { rule.onAllNodesWithText("Remover marcação").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Remover marcação").performClick()
        rule.waitForIdle()
        val library = app.lume.reader.data.Library(RuntimeEnvironment.getApplication())
        assertTrue(library.highlights.isEmpty())
    }

    @Test
    fun offlineDictionaryUnderstandsInflectedWords() = kotlinx.coroutines.runBlocking {
        val dict = app.lume.reader.data.Dictionary(RuntimeEnvironment.getApplication())
        suspend fun words(q: String) = dict.lookup(q).entries.map { it.word }
        assertTrue("soturno" in words("soturno"))
        assertTrue("fazer" in words("fizeram"))
        assertTrue("fazer" in words("Fizeram,"))
        assertTrue("gato" in words("gatos"))
        assertTrue("casa" in words("casinha"))
        assertTrue("fazer" in words("fazê-lo"))
        assertTrue("dizer" in words("disse-me"))
        assertTrue("ação" in words("acção"))
        assertTrue("coração" in words("CORAÇÕES"))
        // "efêmero" aponta para "efémero": a definição de verdade vem junto
        assertTrue(dict.lookup("efêmero").entries.any { e -> e.senses.any { "curta duração" in it } })
        // forma flexionada explica de onde veio
        assertTrue(dict.lookup("fizeram").entries.first { it.word == "fazer" }.via!!.contains("verbo fazer"))
        // palavra inexistente: sem verbetes, mas com sugestões parecidas
        val none = dict.lookup("soturnidadezz")
        assertTrue(none.entries.isEmpty())
        assertTrue(none.suggestions.isNotEmpty())
        // mostra o que cada consulta devolve (para conferência manual no log)
        for (q in listOf("lúgubre", "olhávamos", "inefável", "mesinhas", "pô-lo", "ver-te-ei", "dormiam", "óptimos", "quiçá")) {
            println("DICT $q -> " + dict.lookup(q).let { r -> r.entries.map { it.word + "/" + it.pos } + r.suggestions })
        }
    }

    @Test
    fun pdfTextExtractionFeedsTheReader() {
        val app = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(app)
        val file = File(app.cacheDir, "t.pdf")
        PDDocument().use { doc ->
            repeat(2) { n ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.setLeading(15f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("Pagina ${n + 1} tem um texto que ocupa a linha inteira ate o fim da")
                    cs.newLine()
                    cs.showText("margem e continua aqui.")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.startPage = 2
            stripper.endPage = 2
            val paragraphs = TextTools.pdfPageToParagraphs(stripper.getText(doc))
            assertEquals(
                listOf("Pagina 2 tem um texto que ocupa a linha inteira ate o fim da margem e continua aqui."),
                paragraphs,
            )
        }
    }
}
