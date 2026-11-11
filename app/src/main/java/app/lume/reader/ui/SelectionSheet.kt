package app.lume.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lume.reader.data.DictEntry
import app.lume.reader.data.DictResult
import app.lume.reader.data.Dictionary
import app.lume.reader.data.Highlight

/** O que o leitor selecionou ao segurar o dedo no texto: a frase e a palavra sob o dedo. */
data class TextSelection(
    val para: Int,
    val start: Int,
    val end: Int,
    val sentence: String,
    val word: String?,
)

/** Painel que abre ao segurar o dedo numa frase: marcar/desmarcar a frase e consultar o dicionário. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionSheet(
    selection: TextSelection,
    existing: Highlight?,
    dictionary: Dictionary,
    onMark: () -> Unit,
    onUnmark: (highlightId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                "“${selection.sentence}”",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (existing == null) {
                    Button(onClick = onMark) { Text("Marcar frase") }
                } else {
                    Button(onClick = { onUnmark(existing.id) }) { Text("Remover marcação") }
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(selection.sentence)) }) { Text("Copiar") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            DictionaryPanel(dictionary = dictionary, initialWord = selection.word.orEmpty())
        }
    }
}

/** Campo de busca + resultado do dicionário offline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryPanel(dictionary: Dictionary, initialWord: String) {
    var field by remember { mutableStateOf(initialWord) }
    var query by remember { mutableStateOf(initialWord) }
    var result by remember { mutableStateOf<DictResult?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        result = null
        failed = false
        if (query.isNotBlank()) {
            runCatching { dictionary.lookup(query) }
                .onSuccess { result = it }
                .onFailure { failed = true }
        }
    }

    Text("Dicionário", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = field,
        onValueChange = { field = it },
        singleLine = true,
        label = { Text("Palavra") },
        trailingIcon = { TextButton(onClick = { query = field.trim() }) { Text("Buscar") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { query = field.trim() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    val r = result
    when {
        query.isBlank() -> Text(
            "Digite uma palavra para consultar. O dicionário funciona sem internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        failed -> Text("Não consegui abrir o dicionário.", color = MaterialTheme.colorScheme.error)
        r == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        r.entries.isEmpty() -> {
            Text("Não encontrei “${r.query}” no dicionário.")
            if (r.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Palavras parecidas:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.suggestions.forEach { s ->
                        SuggestionChip(
                            onClick = {
                                field = s
                                query = s
                            },
                            label = { Text(s) },
                        )
                    }
                }
            }
        }
        else -> {
            r.entries.forEach { DictEntryView(it) }
            Text(
                "Definições do Wikcionário (CC BY-SA 4.0).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DictEntryView(entry: DictEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(entry.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val kind = listOfNotNull(entry.pos, entry.tags).joinToString(" · ")
        if (kind.isNotEmpty()) {
            Text(
                kind,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.via?.let {
            Text(
                "↳ $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        entry.senses.forEachIndexed { i, sense ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("${i + 1}. ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sense)
            }
        }
        entry.synonyms?.let {
            Text(
                "Sinônimos: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        entry.etymology?.let {
            Text(
                "Origem: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
