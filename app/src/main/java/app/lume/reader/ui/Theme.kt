package app.lume.reader.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.lume.reader.data.ReaderTheme

/** Cores da página de leitura para cada modo de tela. */
data class PageColors(val background: Color, val text: Color, val muted: Color)

fun pageColors(theme: ReaderTheme): PageColors = when (theme) {
    ReaderTheme.LIGHT -> PageColors(Color(0xFFFBFAF7), Color(0xFF1C1C1C), Color(0xFF6B6B6B))
    ReaderTheme.SEPIA -> PageColors(Color(0xFFF4ECD8), Color(0xFF4B3A26), Color(0xFF8A7558))
    ReaderTheme.DARK -> PageColors(Color(0xFF121212), Color(0xFFC9C9C9), Color(0xFF8A8A8A))
}

private fun scheme(theme: ReaderTheme): ColorScheme {
    val page = pageColors(theme)
    return when (theme) {
        ReaderTheme.LIGHT -> lightColorScheme(
            primary = Color(0xFF2B3A55),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE3F1),
            onPrimaryContainer = Color(0xFF17233A),
            secondaryContainer = Color(0xFFDCE3F1),
            onSecondaryContainer = Color(0xFF17233A),
            background = page.background,
            onBackground = page.text,
            surface = page.background,
            onSurface = page.text,
            surfaceVariant = Color(0xFFE9E7E1),
            onSurfaceVariant = page.muted,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF5F4F0),
            surfaceContainer = Color(0xFFF1EFEA),
            surfaceContainerHigh = Color(0xFFEBE9E3),
            surfaceContainerHighest = Color(0xFFE5E3DD),
            outline = Color(0xFF9A9A9A),
            outlineVariant = Color(0xFFD5D3CD),
        )
        ReaderTheme.SEPIA -> lightColorScheme(
            primary = Color(0xFF7A4E21),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE6D3B1),
            onPrimaryContainer = Color(0xFF3D2A12),
            secondaryContainer = Color(0xFFE6D3B1),
            onSecondaryContainer = Color(0xFF3D2A12),
            background = page.background,
            onBackground = page.text,
            surface = page.background,
            onSurface = page.text,
            surfaceVariant = Color(0xFFE6DBC2),
            onSurfaceVariant = page.muted,
            surfaceContainerLowest = Color(0xFFF9F2E1),
            surfaceContainerLow = Color(0xFFF0E7D0),
            surfaceContainer = Color(0xFFECE2C9),
            surfaceContainerHigh = Color(0xFFE7DCC1),
            surfaceContainerHighest = Color(0xFFE1D5B8),
            outline = Color(0xFFA08C6C),
            outlineVariant = Color(0xFFD6C8A8),
        )
        ReaderTheme.DARK -> darkColorScheme(
            primary = Color(0xFFA9BEE6),
            onPrimary = Color(0xFF14213A),
            primaryContainer = Color(0xFF2B3A55),
            onPrimaryContainer = Color(0xFFDCE3F1),
            secondaryContainer = Color(0xFF2B3A55),
            onSecondaryContainer = Color(0xFFDCE3F1),
            background = page.background,
            onBackground = page.text,
            surface = page.background,
            onSurface = page.text,
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = page.muted,
            surfaceContainerLowest = Color(0xFF0C0C0C),
            surfaceContainerLow = Color(0xFF1A1A1A),
            surfaceContainer = Color(0xFF1F1F1F),
            surfaceContainerHigh = Color(0xFF262626),
            surfaceContainerHighest = Color(0xFF303030),
            outline = Color(0xFF6E6E6E),
            outlineVariant = Color(0xFF3A3A3A),
        )
    }
}

/** Cor de fundo das frases marcadas em cada modo de tela. */
fun highlightColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.LIGHT -> Color(0xFFFFE27A)
    ReaderTheme.SEPIA -> Color(0xFFEBC66B)
    ReaderTheme.DARK -> Color(0xFF5E4A0E)
}

@Composable
fun LumeTheme(theme: ReaderTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(theme), content = content)
}
