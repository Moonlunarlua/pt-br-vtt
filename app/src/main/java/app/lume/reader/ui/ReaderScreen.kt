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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
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
import app.lume.reader.data.Dictionary
import app.lume.reader.data.Highlight
import app.lume.reader.data.WordForms
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

/** Fração da largura, em cada lateral, que vira a página no modo "tocar nas laterais". */
const val SIDE_TAP_FRACTION = 0.33f

@Composable
fun ReaderScreen(
    bookId: String,
    library: Library,
    settings: Settings,
    dictionary: Dictionary,
    /** Frase marcada para onde o leitor deve pular ao abrir (vindo da aba "Frases marcadas"). */
    jump: Highlight?,
    onBack: () -> Unit,
) {
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
            else -> ReaderContent(book, result.getOrThrow(), library, settings, dictionary, jump, onBack)
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

/** O fim do último parágrafo já está visível na tela? */
private fun textAtEnd(state: LazyListState, paged: Boolean): Boolean {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    if (last.index != info.totalItemsCount - 1) return false
    // no modo paginado o espaço depois do texto é só para a última linha poder subir ao topo
    val limit = info.viewportEndOffset - if (paged) 0 else info.afterContentPadding
    return last.offset + last.size <= limit + 1
}

@Composable
private fun ReaderContent(
    initialBook: Book,
    content: BookContent,
    library: Library,
    settings: Settings,
    dictionary: Dictionary,
    jump: Highlight?,
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
                !canShowPages || jump != null -> ViewMode.TEXT
                else -> initialBook.viewMode
            }
        )
    }
    var chrome by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    val lastParagraph = (content.paragraphs.size - 1).coerceAtLeast(0)
    val jumpTarget = jump?.takeIf { canShowText }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (jumpTarget?.para ?: initialBook.paraIndex).coerceIn(0, lastParagraph),
        initialFirstVisibleItemScrollOffset = if (jumpTarget != null) 0 else initialBook.paraOffset,
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
            } else if (textAtEnd(listState, settings.tapToTurn)) {
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
                TextReader(
                    content = content,
                    listState = listState,
                    settings = settings,
                    highlights = library.highlightsOf(initialBook.id),
                    jump = jumpTarget,
                    chromeVisible = chrome,
                    onMenuTap = { chrome = !chrome },
                    onLongPress = { para, offset ->
                        val text = content.paragraphs.getOrNull(para)
                        if (text != null) {
                            val sentence = WordForms.sentenceAt(text, offset)
                            if (!sentence.isEmpty()) {
                                selection = TextSelection(
                                    para = para,
                                    start = sentence.first,
                                    end = sentence.last + 1,
                                    sentence = text.substring(sentence.first, sentence.last + 1),
                                    word = WordForms.wordAt(text, offset)?.let { text.substring(it.first, it.last + 1) },
                                )
                            }
                        }
                    },
                )
            } else {
                PdfPagesReader(
                    file = library.pdfFile(initialBook.id),
                    pagerState = pagerState,
                    theme = settings.theme,
                    onTap = { x, zoomed ->
                        val side = settings.tapToTurn && !chrome && !zoomed
                        when {
                            side && x < SIDE_TAP_FRACTION && pagerState.currentPage > 0 ->
                                scope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) }
                            side && x > 1 - SIDE_TAP_FRACTION && pagerState.currentPage < pageCount - 1 ->
                                scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) }
                            else -> chrome = !chrome
                        }
                    },
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

    selection?.let { sel ->
        SelectionSheet(
            selection = sel,
            existing = library.highlightsOf(initialBook.id)
                .firstOrNull { it.para == sel.para && it.start < sel.end && sel.start < it.end },
            dictionary = dictionary,
            onMark = {
                val page = if (canShowPages) content.pageOfParagraph(sel.para) + 1 else 0
                library.addHighlight(initialBook.id, sel.para, sel.start, sel.end, sel.sentence, page)
                selection = null
            },
            onUnmark = { id ->
                library.removeHighlight(initialBook.id, id)
                selection = null
            },
            onDismiss = { selection = null },
        )
    }
}

/** Pinça com dois dedos = aumentar/diminuir a letra. */
private fun Modifier.pinchToResizeFont(settings: Settings): Modifier = pointerInput(Unit) {
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

/** Guarda onde cada parágrafo visível está na tela, para descobrir a palavra sob o dedo. */
private class ParagraphRegistry {
    val coords = HashMap<Int, LayoutCoordinates>()
    val layouts = HashMap<Int, TextLayoutResult>()
    var container: LayoutCoordinates? = null

    /** (parágrafo, posição do caractere) no ponto [pos] da área de leitura, ou null. */
    fun hit(pos: Offset): Pair<Int, Int>? {
        val c = container?.takeIf { it.isAttached } ?: return null
        for ((index, lc) in coords) {
            if (!lc.isAttached) continue
            val local = lc.localPositionOf(c, pos)
            if (local.y < 0 || local.y > lc.size.height) continue
            val layout = layouts[index] ?: return null
            val x = local.x.coerceIn(0f, lc.size.width.toFloat())
            return index to layout.getOffsetForPosition(Offset(x, local.y))
        }
        return null
    }
}

@Composable
private fun Paragraph(
    index: Int,
    text: String,
    highlights: List<Highlight>?,
    highlight: Color,
    style: TextStyle,
    registry: ParagraphRegistry,
    modifier: Modifier,
) {
    val annotated = remember(text, highlights, highlight) {
        if (highlights.isNullOrEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                for (h in highlights) {
                    val start = h.start.coerceIn(0, text.length)
                    val end = h.end.coerceIn(start, text.length)
                    if (end > start) addStyle(SpanStyle(background = highlight), start, end)
                }
            }
        }
    }
    DisposableEffect(index) {
        onDispose {
            registry.coords.remove(index)
            registry.layouts.remove(index)
        }
    }
    Text(
        text = annotated,
        style = style,
        onTextLayout = { registry.layouts[index] = it },
        modifier = modifier.onGloballyPositioned { registry.coords[index] = it },
    )
}

/** Ao abrir numa frase marcada, desce até a linha onde ela começa dentro do parágrafo. */
@Composable
private fun JumpToSentence(jump: Highlight?, listState: LazyListState, registry: ParagraphRegistry) {
    LaunchedEffect(jump?.id) {
        if (jump == null) return@LaunchedEffect
        repeat(40) {
            val layout = registry.layouts[jump.para]
            if (layout != null && listState.firstVisibleItemIndex == jump.para) {
                if (listState.firstVisibleItemScrollOffset == 0) {
                    val offset = jump.start.coerceIn(0, layout.layoutInput.text.length)
                    val top = layout.getLineTop(layout.getLineForOffset(offset))
                    if (top > 0f) listState.scrollBy(top)
                }
                return@LaunchedEffect
            }
            delay(25)
        }
    }
}

@Composable
private fun TextReader(
    content: BookContent,
    listState: LazyListState,
    settings: Settings,
    highlights: List<Highlight>,
    jump: Highlight?,
    chromeVisible: Boolean,
    onMenuTap: () -> Unit,
    onLongPress: (paragraph: Int, offset: Int) -> Unit,
) {
    val colors = pageColors(settings.theme)
    val baseStyle = TextStyle(
        color = colors.text,
        fontFamily = settings.fontFamily,
        fontSize = settings.fontSize.sp,
        textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
        hyphens = if (settings.justify) Hyphens.Auto else Hyphens.None,
        lineBreak = LineBreak.Paragraph,
    )
    val byParagraph = remember(highlights) { highlights.groupBy { it.para } }
    val registry = remember { ParagraphRegistry() }
    val haptics = LocalHapticFeedback.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // segurar o dedo sobre o texto: marca a frase / consulta a palavra
    val longPress: (Offset) -> Unit = { pos ->
        registry.hit(pos)?.let { (paragraph, offset) ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnLongPress(paragraph, offset)
        }
    }
    JumpToSentence(jump, listState, registry)
    if (settings.tapToTurn) {
        PagedTextReader(content, listState, settings, baseStyle, byParagraph, registry, chromeVisible, onMenuTap, longPress)
    } else {
        ScrollingTextReader(content, listState, settings, baseStyle, byParagraph, registry, onMenuTap, longPress)
    }
}

/** Rolagem contínua: arrasta o texto para cima e para baixo. */
@Composable
private fun ScrollingTextReader(
    content: BookContent,
    listState: LazyListState,
    settings: Settings,
    baseStyle: TextStyle,
    highlights: Map<Int, List<Highlight>>,
    registry: ParagraphRegistry,
    onMenuTap: () -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    val fontSize = settings.fontSize
    val style = baseStyle.copy(lineHeight = (fontSize * settings.lineHeight).sp)
    val highlight = highlightColor(settings.theme)
    val currentOnTap by rememberUpdatedState(onMenuTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = settings.margin.dp, vertical = 20.dp),
        modifier = Modifier
            .fillMaxSize()
            .pinchToResizeFont(settings)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnTap() }, onLongPress = { currentOnLongPress(it) })
            }
            .onGloballyPositioned { registry.container = it },
    ) {
        items(count = content.paragraphs.size, key = { it }) { index ->
            Paragraph(
                index = index,
                text = content.paragraphs[index],
                highlights = highlights[index],
                highlight = highlight,
                style = style,
                registry = registry,
                modifier = Modifier.fillMaxWidth().padding(bottom = (fontSize * 0.7f).dp),
            )
        }
    }
}

/**
 * Uma tela por vez, como no Kindle: toque na direita avança, na esquerda volta, no meio abre o menu.
 *
 * Todas as linhas têm exatamente a mesma altura em pixels e a área de texto mede um número inteiro
 * de linhas; assim, avançar "uma tela" nunca corta uma linha ao meio.
 */
@Composable
private fun PagedTextReader(
    content: BookContent,
    listState: LazyListState,
    settings: Settings,
    baseStyle: TextStyle,
    highlights: Map<Int, List<Highlight>>,
    registry: ParagraphRegistry,
    chromeVisible: Boolean,
    onMenuTap: () -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val highlight = highlightColor(settings.theme)
    val linePx = with(density) { (settings.fontSize * settings.lineHeight).sp.toPx() }.roundToInt().coerceAtLeast(1)
    val style = baseStyle.copy(
        // um tiquinho abaixo do inteiro: o Android arredonda a altura da linha para cima
        lineHeight = with(density) { (linePx - 0.02f).toSp() },
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textIndent = TextIndent(firstLine = 1.5.em),
    )
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val topPx = with(density) { 20.dp.roundToPx() }
        val bottomPx = with(density) { 4.dp.roundToPx() }
        val lines = ((constraints.maxHeight - topPx - bottomPx) / linePx).coerceAtLeast(1)
        val pagePx = lines * linePx

        // se a letra/fonte/largura mudou (ou veio do modo rolagem), realinha no começo de uma linha
        LaunchedEffect(linePx, pagePx, constraints.maxWidth, settings.fontKey, settings.margin, settings.justify) {
            val extra = listState.firstVisibleItemScrollOffset % linePx
            if (extra != 0) listState.scrollBy(-extra.toFloat())
        }

        fun turn(direction: Int) {
            scope.launch {
                val extra = listState.firstVisibleItemScrollOffset % linePx
                listState.scrollBy((direction * pagePx - extra).toFloat())
            }
        }
        val currentTurn by rememberUpdatedState(::turn)
        val currentChrome by rememberUpdatedState(chromeVisible)
        val currentOnMenuTap by rememberUpdatedState(onMenuTap)
        val currentOnLongPress by rememberUpdatedState(onLongPress)

        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(
                start = settings.margin.dp,
                end = settings.margin.dp,
                // deixa a última linha do livro chegar ao topo da tela
                bottom = with(density) { (pagePx - linePx).toDp() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .pinchToResizeFont(settings)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { currentOnLongPress(it) },
                        onTap = { tap ->
                            val x = tap.x / size.width
                            when {
                                currentChrome -> currentOnMenuTap()
                                x < SIDE_TAP_FRACTION -> currentTurn(-1)
                                x > 1 - SIDE_TAP_FRACTION -> currentTurn(1)
                                else -> currentOnMenuTap()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // deslizar para os lados também vira a página
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            val threshold = 48.dp.toPx()
                            if (total < -threshold) currentTurn(1) else if (total > threshold) currentTurn(-1)
                        },
                    ) { _, amount -> total += amount }
                }
                .onGloballyPositioned { registry.container = it }
                .padding(top = 20.dp)
                // altura exata em pixels: número inteiro de linhas
                .layout { measurable, c ->
                    val placeable = measurable.measure(
                        Constraints(minWidth = c.minWidth, maxWidth = c.maxWidth, minHeight = pagePx, maxHeight = pagePx)
                    )
                    layout(placeable.width, pagePx) { placeable.place(0, 0) }
                },
        ) {
            items(count = content.paragraphs.size, key = { it }) { index ->
                Paragraph(
                    index = index,
                    text = content.paragraphs[index],
                    highlights = highlights[index],
                    highlight = highlight,
                    style = style,
                    registry = registry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
