package app.lume.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lume.reader.data.Highlight
import app.lume.reader.data.Library

/** Lista, em tópicos, das frases marcadas de um livro. Tocar numa frase abre o livro nela. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(bookId: String, library: Library, onBack: () -> Unit, onOpen: (Highlight) -> Unit) {
    val book = library.find(bookId)
    val highlights = library.highlightsOf(bookId)
    var deleting by remember { mutableStateOf<Highlight?>(null) }

    // sem livro ou sem frases (apagou a última): volta para a biblioteca
    LaunchedEffect(book == null || highlights.isEmpty()) {
        if (book == null || highlights.isEmpty()) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (highlights.size == 1) "1 frase marcada" else "${highlights.size} frases marcadas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(highlights, key = { it.id }) { h ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Abrir o livro nesta frase") { onOpen(h) }
                        .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                ) {
                    Text("•", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(h.text, style = MaterialTheme.typography.bodyLarge)
                        if (h.page > 0) {
                            Text(
                                "Página ${h.page}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    IconButton(onClick = { deleting = h }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remover marcação",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    deleting?.let { h ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remover marcação?") },
            text = { Text("“${h.text}”", maxLines = 5, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    library.removeHighlight(bookId, h.id)
                    deleting = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}
