package app.lume.reader.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lume.reader.data.Book
import app.lume.reader.data.BookType
import app.lume.reader.data.ImportException
import app.lume.reader.data.Library
import app.lume.reader.data.Settings
import app.lume.reader.data.TextTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    library: Library,
    settings: Settings,
    incomingUri: Uri?,
    onIncomingHandled: () -> Unit,
    onOpen: (String) -> Unit,
    onNewText: () -> Unit,
    onEditText: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Book?>(null) }
    var deleting by remember { mutableStateOf<Book?>(null) }

    fun import(uri: Uri, openAfter: Boolean) {
        if (importing) return
        importing = true
        importProgress = 0f
        scope.launch {
            try {
                val book = library.importUri(uri) { importProgress = it }
                if (openAfter) onOpen(book.id)
            } catch (e: ImportException) {
                error = e.message
            } catch (e: Exception) {
                error = "Não consegui importar o arquivo."
            } finally {
                importing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import(uri, openAfter = false)
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            onIncomingHandled()
            import(incomingUri, openAfter = true)
        }
    }

    // lê a versão para recompor o tempo restante quando a velocidade muda
    val wpm = settings.speedVersion.let { settings.speed.wpm }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Minha biblioteca", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { showSettings = true }) {
                        Text("Aa", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Adicionar") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Importar PDF ou arquivo de texto") },
                        onClick = {
                            addMenu = false
                            picker.launch(arrayOf("application/pdf", "text/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Escrever ou colar um texto") },
                        onClick = {
                            addMenu = false
                            onNewText()
                        },
                    )
                }
            }
        },
    ) { padding ->
        if (library.books.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sua biblioteca está vazia", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Importe um PDF ou cole um texto para começar a ler.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { picker.launch(arrayOf("application/pdf", "text/*")) }) {
                    Text("Importar PDF")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onNewText) { Text("Colar um texto") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(library.books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        library = library,
                        wpm = wpm,
                        onClick = { onOpen(book.id) },
                        onRename = { renaming = book },
                        onEdit = { onEditText(book.id) },
                        onDelete = { deleting = book },
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings = settings, onDismiss = { showSettings = false })
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importando…") },
            text = {
                Column {
                    Text("Preparando o livro para leitura. PDFs grandes podem levar um minutinho.")
                    Spacer(Modifier.height(16.dp))
                    if (importProgress > 0f) {
                        LinearProgressIndicator(progress = { importProgress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("${(importProgress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            title = { Text("Não deu certo") },
            text = { Text(msg) },
        )
    }

    renaming?.let { book ->
        var name by remember(book.id) { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        library.find(book.id)?.let { library.save(it.copy(title = name.trim())) }
                        renaming = null
                    },
                ) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir livro?") },
            text = { Text("\"${book.title}\" e o seu progresso de leitura serão apagados do app.") },
            confirmButton = {
                TextButton(onClick = {
                    library.delete(book.id)
                    deleting = null
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

private val coverColors = listOf(
    Color(0xFF2B3A55), Color(0xFF7A4E21), Color(0xFF3F6B57), Color(0xFF7B3B4B), Color(0xFF4B4A7A), Color(0xFF5A6B2E),
)

@Composable
private fun BookCard(
    book: Book,
    library: Library,
    wpm: Double,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val cover by produceState<ImageBitmap?>(initialValue = null, book.id) {
        value = withContext(Dispatchers.IO) {
            val f = library.coverFile(book.id)
            if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null
        }
    }
    Column(modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(coverColors[(book.id.hashCode() and 0x7fffffff) % coverColors.size]),
        ) {
            val c = cover
            if (c != null) {
                Image(
                    bitmap = c,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = book.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(4.dp))
                val pct = (book.progress * 100).roundToInt()
                val status = when {
                    book.lastOpened == 0L -> "Novo"
                    pct >= 100 -> "Concluído"
                    else -> "$pct% · faltam " + TextTools.formatDuration(book.totalWords * (1 - book.progress) / wpm)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Opções de ${book.title}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; onRename() })
                    if (book.type == BookType.TEXT) {
                        DropdownMenuItem(text = { Text("Editar texto") }, onClick = { menu = false; onEdit() })
                    }
                    DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
