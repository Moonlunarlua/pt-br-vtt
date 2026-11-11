package app.lume.reader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lume.reader.data.Dictionary
import app.lume.reader.data.Highlight
import app.lume.reader.data.Library
import app.lume.reader.data.ReaderTheme
import app.lume.reader.data.Settings
import app.lume.reader.ui.EditorScreen
import app.lume.reader.ui.HighlightsScreen
import app.lume.reader.ui.LibraryScreen
import app.lume.reader.ui.LumeTheme
import app.lume.reader.ui.ReaderScreen

sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String, val jump: Highlight? = null) : Screen
    data class Highlights(val bookId: String) : Screen
    data class Editor(val bookId: String?, val initialText: String = "") : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var library: Library
    private lateinit var settings: Settings
    private lateinit var dictionary: Dictionary

    /** Aba selecionada na tela inicial (0 = livros, 1 = frases marcadas, 2 = dicionário). */
    private var homeTab by mutableStateOf(0)

    /** Para onde o botão "voltar" do leitor leva. */
    private var readerReturn: Screen = Screen.Library

    private var screen by mutableStateOf<Screen>(Screen.Library)

    /** Arquivo recebido de outro app ("Abrir com" / "Compartilhar"), esperando importação. */
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        settings = Settings(this)
        dictionary = Dictionary(this)
        savedInstanceState?.getString("readerBook")?.let { id ->
            if (library.find(id) != null) screen = Screen.Reader(id)
        }
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val theme = settings.theme
            DisposableEffect(theme) {
                val style = if (theme == ReaderTheme.DARK) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            LumeTheme(theme) {
                when (val s = screen) {
                    Screen.Library -> LibraryScreen(
                        library = library,
                        settings = settings,
                        dictionary = dictionary,
                        tab = homeTab,
                        onTabChange = { homeTab = it },
                        incomingUri = incomingUri,
                        onIncomingHandled = { incomingUri = null },
                        onOpen = {
                            readerReturn = Screen.Library
                            screen = Screen.Reader(it)
                        },
                        onOpenHighlights = { screen = Screen.Highlights(it) },
                        onNewText = { screen = Screen.Editor(null) },
                        onEditText = { screen = Screen.Editor(it) },
                    )
                    is Screen.Highlights -> {
                        BackHandler { screen = Screen.Library }
                        HighlightsScreen(
                            bookId = s.bookId,
                            library = library,
                            onBack = { screen = Screen.Library },
                            onOpen = { highlight ->
                                readerReturn = s
                                screen = Screen.Reader(s.bookId, highlight)
                            },
                        )
                    }
                    is Screen.Reader -> {
                        BackHandler { screen = readerReturn }
                        val book = library.find(s.bookId)
                        if (book == null) {
                            screen = Screen.Library
                        } else {
                            ReaderScreen(
                                bookId = s.bookId,
                                library = library,
                                settings = settings,
                                dictionary = dictionary,
                                jump = s.jump,
                                onBack = { screen = readerReturn },
                            )
                        }
                    }
                    is Screen.Editor -> {
                        EditorScreen(
                            library = library,
                            bookId = s.bookId,
                            initialText = s.initialText,
                            onDone = { saved ->
                                readerReturn = Screen.Library
                                screen = if (saved != null) Screen.Reader(saved) else Screen.Library
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (screen as? Screen.Reader)?.let { outState.putString("readerBook", it.bookId) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let {
                incomingUri = it
                screen = Screen.Library
            }
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (stream != null) {
                    incomingUri = stream
                    screen = Screen.Library
                } else if (!text.isNullOrBlank()) {
                    screen = Screen.Editor(null, text)
                }
            }
        }
    }
}
