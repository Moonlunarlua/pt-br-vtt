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
                val view = activity.window.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bmp))
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
        rule.waitUntil(10_000) { rule.onAllNodesWithText("0% lido").fetchSemanticsNodes().isNotEmpty() }
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
        rule.waitUntil(10_000) { rule.onAllNodesWithText("0% lido").fetchSemanticsNodes().isNotEmpty() }
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
