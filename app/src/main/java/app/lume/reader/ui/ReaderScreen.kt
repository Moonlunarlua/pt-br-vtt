package app.lume.reader.ui

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lume.reader.data.Book
import app.lume.reader.data.BookContent
import app.lume.reader.data.BookType
import app.lume.reader.data.Library
import app.lume.reader.data.Settings
import app.lume.reader.data.TextTools
import app.lume.reader.data.ViewMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SAMPLE_INTERVAL_MS = 5000L

@Composable
fun ReaderScreen(bookId: String, library: Library, settings: Settings, onBack: () -> Unit) {
    val colors = pageColors(settings.theme)
    val content by produceState<Result<BookContent>?>(initialValue = null, bookId) {
        value = runCatching { library.loadContent(bookId) }
    }
    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        val result = content
        val book = library.find(bookId)
        when {
            book == null -> Unit
            result == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            result.isFailure -> Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Não consegui abrir este livro.", color = colors.text)
                TextButton(onClick = onBack) { Text("Voltar") }
            }
            else -> ReaderContent(book, result.getOrThrow(), library, settings, onBack)
        }
    }
}

/** Posição atual em palavras (com fração dentro do parágrafo visível no topo). */
private fun textWordPos(content: BookContent, state: LazyListState): Double {
    if (content.paragraphs.isEmpty()) return 0.0
    val index = state.firstVisibleItemIndex.coerceIn(0, content.paragraphs.size - 1)
    val before = content.cumWords[index].toDouble()
    val size = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
    val inside = content.cumWords[index + 1] - content.cumWords[index]
    val fraction = if (size > 0) (state.firstVisibleItemScrollOffset.toDouble() / size).coerceIn(0.0, 1.0) else 0.0
    return before + inside * fraction
}

@Composable
private fun ReaderContent(
    initialBook: Book,
    content: BookContent,
    library: Library,
    settings: Settings,
    onBack: () -> Unit,
) {
    val colors = pageColors(settings.theme)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canShowText = content.hasText
    val canShowPages = initialBook.type == BookType.PDF && content.pageCount > 0

    var mode by remember {
        mutableStateOf(
            when {
                !canShowText -> ViewMode.PAGES
                !canShowPages -> ViewMode.TEXT
                else -> initialBook.viewMode
            }
        )
    }
    var chrome by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialBook.paraIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0)),
        initialFirstVisibleItemScrollOffset = initialBook.paraOffset,
    )
    val pageCount = content.pageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = initialBook.page.coerceIn(0, pageCount - 1)) { pageCount }

    val totalWords = content.totalWords.coerceAtLeast(1)

    // página atual (do PDF), palavras lidas e progresso — valem para os dois modos
    val currentPage by remember(mode) {
        derivedStateOf {
            if (mode == ViewMode.PAGES) pagerState.currentPage else content.pageOfParagraph(listState.firstVisibleItemIndex)
        }
    }
    val wordPos by remember(mode) {
        derivedStateOf {
            if (mode == ViewMode.PAGES) content.wordsBeforePage(pagerState.currentPage).toDouble()
            else textWordPos(content, listState)
        }
    }
    val progress by remember(mode) {
        derivedStateOf {
            if (mode == ViewMode.PAGES) {
                content.wordsBeforePage(pagerState.currentPage + 1).toFloat() / totalWords
            } else if (!listState.canScrollForward && listState.layoutInfo.totalItemsCount > 0) {
                1f
            } else {
                (textWordPos(content, listState) / totalWords).toFloat()
            }.coerceIn(0f, 1f)
        }
    }

    fun savePosition() {
        val current = library.find(initialBook.id) ?: return
        library.save(
            current.copy(
                viewMode = mode,
                paraIndex = listState.firstVisibleItemIndex,
                paraOffset = listState.firstVisibleItemScrollOffset,
                page = pagerState.currentPage,
                progress = progress,
                lastOpened = System.currentTimeMillis(),
            )
        )
        settings.persist()
    }
    val save by rememberUpdatedState(::savePosition)

    // mede a velocidade de leitura enquanto o livro está na tela
    LaunchedEffect(Unit) {
        settings.speed.resetBaseline()
        while (true) {
            settings.speed.sample(wordPos, SystemClock.elapsedRealtime())
            settings.speedChanged()
            delay(SAMPLE_INTERVAL_MS)
            save()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                save()
                settings.speed.resetBaseline()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            save()
        }
    }

    // mantém a tela ligada durante a leitura, se a opção estiver ativa
    val keepOn = settings.keepScreenOn
    DisposableEffect(keepOn) {
        val window = (context as? Activity)?.window
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun switchMode() {
        scope.launch {
            if (mode == ViewMode.TEXT) {
                val page = content.pageOfParagraph(listState.firstVisibleItemIndex)
                pagerState.scrollToPage(page)
                mode = ViewMode.PAGES
            } else {
                val page = pagerState.currentPage
                if (content.pageOfParagraph(listState.firstVisibleItemIndex) != page) {
                    listState.scrollToItem(content.paragraphOfPage(page))
                }
                mode = ViewMode.TEXT
            }
            settings.speed.resetBaseline()
        }
    }

    fun seekTo(fraction: Float) {
        scope.launch {
            if (mode == ViewMode.PAGES) {
                pagerState.scrollToPage((fraction * (pageCount - 1)).roundToInt())
            } else {
                val word = (fraction * totalWords).toInt()
                val index = TextTools.indexForValue(content.cumWords, word).coerceAtMost(content.paragraphs.size - 1)
                listState.scrollToItem(index)
            }
            settings.speed.resetBaseline()
        }
    }

    val wpm = settings.speedVersion.let { settings.speed.wpm }
    val minutesLeft = ((totalWords - wordPos).coerceAtLeast(0.0) / wpm).let { if (progress >= 1f) 0.0 else it }
    val percent = (progress * 100).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (mode == ViewMode.TEXT) {
                TextReader(content, listState, settings, onTap = { chrome = !chrome })
            } else {
                PdfPagesReader(
                    file = library.pdfFile(initialBook.id),
                    pagerState = pagerState,
                    theme = settings.theme,
                    onTap = { chrome = !chrome },
                )
            }

            // barra de cima
            androidx.compose.animation.AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar para a biblioteca")
                        }
                        Text(
                            initialBook.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (canShowText && canShowPages) {
                            TextButton(onClick = { switchMode() }) {
                                Text(if (mode == ViewMode.TEXT) "Ver páginas" else "Ver texto")
                            }
                        }
                        TextButton(onClick = { showSettings = true }) {
                            Text("Aa", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // barra de baixo: arrastar para navegar pelo livro
            androidx.compose.animation.AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val sliderValue = seeking ?: if (mode == ViewMode.PAGES) {
                            if (pageCount > 1) pagerState.currentPage / (pageCount - 1f) else 1f
                        } else {
                            progress
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = { seeking = it },
                            onValueChangeFinished = {
                                seeking?.let { seekTo(it) }
                                seeking = null
                            },
                        )
                        val hint = seeking?.let { v ->
                            if (canShowPages) "Ir para a página ${(v * (pageCount - 1)).roundToInt() + 1}"
                            else "Ir para ${(v * 100).roundToInt()}%"
                        } ?: if (settings.speed.isCalibrated) {
                            "Seu ritmo: ${wpm.roundToInt()} palavras/min"
                        } else {
                            "Medindo o seu ritmo de leitura…"
                        }
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }

        // rodapé fixo, estilo Kindle: página, porcentagem e tempo restante
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (canShowPages) "Pág. ${currentPage + 1} de $pageCount" else "$percent% lido",
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    progress >= 1f -> "100% · fim do livro"
                    canShowPages -> "$percent% · faltam ${TextTools.formatDuration(minutesLeft)}"
                    else -> "faltam ${TextTools.formatDuration(minutesLeft)}"
                },
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }

    if (showSettings) {
        SettingsSheet(settings = settings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun TextReader(content: BookContent, listState: LazyListState, settings: Settings, onTap: () -> Unit) {
    val colors = pageColors(settings.theme)
    val fontSize = settings.fontSize
    val style = TextStyle(
        color = colors.text,
        fontFamily = settings.fontFamily,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * settings.lineHeight).sp,
        textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
        hyphens = if (settings.justify) Hyphens.Auto else Hyphens.None,
        lineBreak = LineBreak.Paragraph,
    )
    val currentOnTap by rememberUpdatedState(onTap)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = settings.margin.dp, vertical = 20.dp),
        modifier = Modifier
            .fillMaxSize()
            // pinça com dois dedos = aumentar/diminuir a letra
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                settings.fontSize = (settings.fontSize * zoom).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                                pinched = true
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinched) settings.persist()
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnTap() }) },
    ) {
        items(count = content.paragraphs.size, key = { it }) { index ->
            Text(
                text = content.paragraphs[index],
                style = style,
                modifier = Modifier.fillMaxWidth().padding(bottom = (fontSize * 0.7f).dp),
            )
        }
    }
}
