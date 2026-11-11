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
import app.lume.reader.data.Library
import app.lume.reader.data.ReaderTheme
import app.lume.reader.data.Settings
import app.lume.reader.ui.EditorScreen
import app.lume.reader.ui.LibraryScreen
import app.lume.reader.ui.LumeTheme
import app.lume.reader.ui.ReaderScreen

sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String) : Screen
    data class Editor(val bookId: String?, val initialText: String = "") : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var library: Library
    private lateinit var settings: Settings

    private var screen by mutableStateOf<Screen>(Screen.Library)

    /** Arquivo recebido de outro app ("Abrir com" / "Compartilhar"), esperando importação. */
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        settings = Settings(this)
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
                        incomingUri = incomingUri,
                        onIncomingHandled = { incomingUri = null },
                        onOpen = { screen = Screen.Reader(it) },
                        onNewText = { screen = Screen.Editor(null) },
                        onEditText = { screen = Screen.Editor(it) },
                    )
                    is Screen.Reader -> {
                        BackHandler { screen = Screen.Library }
                        val book = library.find(s.bookId)
                        if (book == null) {
                            screen = Screen.Library
                        } else {
                            ReaderScreen(
                                bookId = s.bookId,
                                library = library,
                                settings = settings,
                                onBack = { screen = Screen.Library },
                            )
                        }
                    }
                    is Screen.Editor -> {
                        EditorScreen(
                            library = library,
                            bookId = s.bookId,
                            initialText = s.initialText,
                            onDone = { saved -> screen = if (saved != null) Screen.Reader(saved) else Screen.Library },
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
