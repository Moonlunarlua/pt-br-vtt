package app.lume.reader.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.lume.reader.R
import java.io.File

enum class ReaderTheme(val label: String) {
    LIGHT("Claro"),
    SEPIA("Leitura"),
    DARK("Escuro"),
}

data class FontOption(val key: String, val label: String, val family: FontFamily)

/** Preferências de leitura + velocidade de leitura medida, salvas em SharedPreferences. */
class Settings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val fontsDir = File(appContext.filesDir, "fonts").apply { mkdirs() }

    var theme by mutableStateOf(
        runCatching { ReaderTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(ReaderTheme.LIGHT)
    )
    var fontKey by mutableStateOf(prefs.getString("font", "literata") ?: "literata")
    var fontSize by mutableFloatStateOf(prefs.getFloat("fontSize", 19f))
    var lineHeight by mutableFloatStateOf(prefs.getFloat("lineHeight", 1.5f))
    var margin by mutableFloatStateOf(prefs.getFloat("margin", 22f))
    var justify by mutableStateOf(prefs.getBoolean("justify", false))
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keepScreenOn", true))

    val speed = ReadingSpeed(
        totalWords = prefs.getFloat("speedWords", 0f).toDouble(),
        totalMs = prefs.getFloat("speedMs", 0f).toDouble(),
    )

    /** Muda a cada atualização da velocidade, para a tela recompor. */
    var speedVersion by mutableStateOf(0)
        private set

    private val builtIn = listOf(
        FontOption("literata", "Literata", FontFamily(Font(R.font.literata))),
        FontOption("lora", "Lora", FontFamily(Font(R.font.lora))),
        FontOption("atkinson", "Atkinson Hyperlegible", FontFamily(Font(R.font.atkinson))),
        FontOption("nunito", "Nunito", FontFamily(Font(R.font.nunito))),
        FontOption("serif", "Serifada (sistema)", FontFamily.Serif),
        FontOption("sans", "Sem serifa (sistema)", FontFamily.SansSerif),
        FontOption("mono", "Monoespaçada", FontFamily.Monospace),
    )

    var fonts by mutableStateOf(builtIn + loadCustomFonts())
        private set

    val fontFamily: FontFamily
        get() = (fonts.firstOrNull { it.key == fontKey } ?: fonts.first()).family

    fun persist() {
        prefs.edit()
            .putString("theme", theme.name)
            .putString("font", fontKey)
            .putFloat("fontSize", fontSize)
            .putFloat("lineHeight", lineHeight)
            .putFloat("margin", margin)
            .putBoolean("justify", justify)
            .putBoolean("keepScreenOn", keepScreenOn)
            .putFloat("speedWords", speed.totalWords.toFloat())
            .putFloat("speedMs", speed.totalMs.toFloat())
            .apply()
    }

    fun speedChanged() {
        speedVersion++
    }

    fun resetSpeed() {
        speed.totalWords = 0.0
        speed.totalMs = 0.0
        speed.resetBaseline()
        speedChanged()
        persist()
    }

    private fun loadTypeface(file: File): Typeface? = try {
        if (Build.VERSION.SDK_INT >= 26) Typeface.Builder(file).build() else Typeface.createFromFile(file)
    } catch (e: Exception) {
        null
    }

    private fun loadCustomFonts(): List<FontOption> =
        (fontsDir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.mapNotNull { f ->
            loadTypeface(f)?.let { FontOption("custom:${f.name}", f.nameWithoutExtension, FontFamily(it)) }
        }

    /** Copia um .ttf/.otf escolhido pelo usuário para o app. Retorna false se não for uma fonte válida. */
    fun importFont(uri: Uri): Boolean {
        var name = "fonte.ttf"
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val target = File(fontsDir, name)
        val ok = runCatching {
            appContext.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            val lower = name.lowercase()
            (lower.endsWith(".ttf") || lower.endsWith(".otf")) && loadTypeface(target) != null
        }.getOrDefault(false)
        if (!ok) {
            target.delete()
            return false
        }
        fonts = builtIn + loadCustomFonts()
        fontKey = "custom:$name"
        persist()
        return true
    }

    fun deleteFont(key: String) {
        if (!key.startsWith("custom:")) return
        File(fontsDir, key.removePrefix("custom:")).delete()
        fonts = builtIn + loadCustomFonts()
        if (fontKey == key) fontKey = "literata"
        persist()
    }
}
