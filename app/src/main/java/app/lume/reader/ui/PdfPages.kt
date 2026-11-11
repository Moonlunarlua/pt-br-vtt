package app.lume.reader.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.lume.reader.data.ReaderTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_RENDER_WIDTH = 1800

/** Renderiza páginas do PDF em bitmaps. O PdfRenderer só aceita uma página aberta por vez. */
private class PdfPageSource(file: File) {
    private val mutex = Mutex()
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    init {
        runCatching {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd!!)
        }
    }

    suspend fun render(page: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val r = renderer ?: return@withLock null
            if (page !in 0 until r.pageCount) return@withLock null
            runCatching {
                r.openPage(page).use { p ->
                    val height = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                runCatching { renderer?.close() }
                runCatching { fd?.close() }
                renderer = null
                fd = null
            }
        }
    }
}

private val invertMatrix = ColorMatrix(
    floatArrayOf(
        -0.87f, 0f, 0f, 0f, 240f,
        0f, -0.87f, 0f, 0f, 240f,
        0f, 0f, -0.87f, 0f, 240f,
        0f, 0f, 0f, 1f, 0f,
    )
)

/** Modo "páginas": mostra o PDF original, uma página por vez, com zoom por pinça e duplo toque. */
@Composable
fun PdfPagesReader(file: File, pagerState: PagerState, theme: ReaderTheme, onTap: () -> Unit) {
    val source = remember(file) { PdfPageSource(file) }
    DisposableEffect(source) { onDispose { source.close() } }
    var zoomed by remember { mutableStateOf(false) }
    val colors = pageColors(theme)

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !zoomed,
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) { page ->
        PdfPage(
            source = source,
            page = page,
            isCurrent = pagerState.settledPage == page,
            theme = theme,
            onZoomedChange = { if (pagerState.settledPage == page) zoomed = it },
            onTap = onTap,
        )
    }
}

@Composable
private fun PdfPage(
    source: PdfPageSource,
    page: Int,
    isCurrent: Boolean,
    theme: ReaderTheme,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoomed by rememberUpdatedState(onZoomedChange)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // ao sair da página, volta o zoom ao normal
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        } else {
            currentOnZoomed(scale > 1.01f)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val renderWidth = (widthPx * 2).toInt().coerceIn(1, MAX_RENDER_WIDTH)

        var bitmap by remember(page) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(page) { mutableStateOf(false) }
        LaunchedEffect(page, renderWidth) {
            val b = source.render(page, renderWidth)
            if (b != null) bitmap = b.asImageBitmap() else failed = true
        }

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = widthPx * (s - 1) / 2
            val maxY = heightPx * (s - 1) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        fun setZoom(newScale: Float, newOffset: Offset) {
            scale = newScale
            offset = clamp(newOffset, newScale)
            currentOnZoomed(newScale > 1.01f)
        }

        val gestures = Modifier
            .pointerInput(widthPx, heightPx) {
                val center = Offset(size.width / 2f, size.height / 2f)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = if (fingers >= 2) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val z = newScale / scale
                            val q = if (fingers >= 2) event.calculateCentroid(useCurrent = false) - center else Offset.Zero
                            setZoom(newScale, offset * z + q * (1 - z) + pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(widthPx, heightPx) {
                val center = Offset(size.width / 2f, size.height / 2f)
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            setZoom(1f, Offset.Zero)
                        } else {
                            setZoom(DOUBLE_TAP_ZOOM, (center - tap) * (DOUBLE_TAP_ZOOM - 1))
                        }
                    },
                )
            }

        Box(modifier = Modifier.fillMaxSize().then(gestures), contentAlignment = Alignment.Center) {
            val b = bitmap
            when {
                b != null -> Image(
                    bitmap = b,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = when (theme) {
                        ReaderTheme.LIGHT -> null
                        ReaderTheme.SEPIA -> ColorFilter.tint(Color(0xFFF4ECD8), BlendMode.Multiply)
                        ReaderTheme.DARK -> ColorFilter.colorMatrix(invertMatrix)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Página ${page + 1}" }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
                failed -> Text("Não consegui mostrar esta página.", color = pageColors(theme).muted)
                else -> CircularProgressIndicator()
            }
        }
    }
}
