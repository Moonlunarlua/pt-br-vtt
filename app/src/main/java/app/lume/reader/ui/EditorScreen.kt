package app.lume.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import app.lume.reader.data.Library
import kotlinx.coroutines.launch

/** Tela para escrever/colar um texto novo ou editar um texto já salvo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    library: Library,
    bookId: String?,
    initialText: String,
    onDone: (savedBookId: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val existing = remember(bookId) { bookId?.let { library.find(it) } }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var original by remember { mutableStateOf(initialText) }
    var loaded by remember { mutableStateOf(bookId == null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        if (bookId != null && !loaded) {
            val source = runCatching { library.loadSourceText(bookId) }.getOrDefault("")
            if (text.isEmpty()) text = source
            original = source
            loaded = true
        }
    }

    val dirty = loaded && (text != original || title != (existing?.title ?: ""))
    fun leave() {
        if (dirty) confirmDiscard = true else onDone(null)
    }
    BackHandler { leave() }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val name = title.trim().ifEmpty {
                    text.trim().lineSequence().firstOrNull().orEmpty().take(40).ifEmpty { "Sem título" }
                }
                val book = library.saveText(name, text, bookId)
                onDone(book.id)
            } catch (e: Exception) {
                error = e.message ?: "Não consegui salvar o texto."
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (bookId == null) "Novo texto" else "Editar texto") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { pasted ->
                            if (pasted.isNotEmpty()) text = if (text.isEmpty()) pasted else text + "\n\n" + pasted
                        }
                    }) { Text("Colar") }
                    TextButton(onClick = { save() }, enabled = loaded && !saving && text.isNotBlank()) {
                        Text("Salvar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Título") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Texto") },
                placeholder = { Text("Digite ou cole o texto aqui…") },
                enabled = loaded,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Descartar alterações?") },
            text = { Text("O que você escreveu não foi salvo.") },
            confirmButton = { TextButton(onClick = { onDone(null) }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Continuar editando") } },
        )
    }
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}
