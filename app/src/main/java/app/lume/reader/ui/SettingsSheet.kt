package app.lume.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lume.reader.data.ReaderTheme
import app.lume.reader.data.Settings
import kotlin.math.roundToInt

const val MIN_FONT_SIZE = 10f
const val MAX_FONT_SIZE = 56f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(settings: Settings, onDismiss: () -> Unit) {
    var fontError by remember { mutableStateOf(false) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fontError = !settings.importFont(uri)
    }
    ModalBottomSheet(
        onDismissRequest = {
            settings.persist()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            SectionTitle("Modo de tela")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ReaderTheme.entries.forEach { t ->
                    val colors = pageColors(t)
                    val selected = settings.theme == t
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.background)
                            .border(
                                BorderStroke(
                                    if (selected) 2.5.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(role = Role.RadioButton) {
                                settings.theme = t
                                settings.persist()
                            }
                            .semantics { contentDescription = "Modo ${t.label}" + if (selected) ", selecionado" else "" },
                    ) {
                        Text(t.label, color = colors.text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            SectionTitle("Tamanho da letra")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    settings.fontSize = (settings.fontSize.roundToInt() - 1f).coerceAtLeast(MIN_FONT_SIZE)
                }) { Text("A−", fontSize = 14.sp) }
                Slider(
                    value = settings.fontSize,
                    onValueChange = { settings.fontSize = it.roundToInt().toFloat() },
                    valueRange = MIN_FONT_SIZE..MAX_FONT_SIZE,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                OutlinedButton(onClick = {
                    settings.fontSize = (settings.fontSize.roundToInt() + 1f).coerceAtMost(MAX_FONT_SIZE)
                }) { Text("A+", fontSize = 18.sp) }
            }
            Text(
                "${settings.fontSize.roundToInt()} pt — no modo texto você também pode usar o gesto de pinça.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Fonte")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.fonts.forEach { f ->
                    FilterChip(
                        selected = settings.fontKey == f.key,
                        onClick = {
                            settings.fontKey = f.key
                            settings.persist()
                        },
                        label = { Text(f.label, fontFamily = f.family, fontSize = 15.sp) },
                        trailingIcon = if (f.key.startsWith("custom:")) {
                            {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remover fonte ${f.label}",
                                    modifier = Modifier.clickable { settings.deleteFont(f.key) },
                                )
                            }
                        } else null,
                    )
                }
            }
            TextButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) {
                Text("Importar outra fonte (.ttf ou .otf)")
            }
            if (fontError) {
                Text(
                    "Esse arquivo não é uma fonte válida. Escolha um .ttf ou .otf.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionTitle("Espaçamento entre linhas")
            Slider(
                value = settings.lineHeight,
                onValueChange = { settings.lineHeight = (it * 10).roundToInt() / 10f },
                valueRange = 1.1f..2.2f,
            )
            SectionTitle("Margens laterais")
            Slider(
                value = settings.margin,
                onValueChange = { settings.margin = it.roundToInt().toFloat() },
                valueRange = 8f..56f,
            )

            ToggleRow("Texto justificado", settings.justify) {
                settings.justify = it
                settings.persist()
            }
            ToggleRow("Manter a tela ligada enquanto leio", settings.keepScreenOn) {
                settings.keepScreenOn = it
                settings.persist()
            }

            SectionTitle("Velocidade de leitura")
            val speed = settings.speedVersion.let { settings.speed }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (speed.isCalibrated) {
                        "Você lê cerca de ${speed.wpm.roundToInt()} palavras por minuto. " +
                            "O tempo restante é calculado com esse ritmo."
                    } else {
                        "Ainda medindo o seu ritmo — por enquanto o tempo restante usa " +
                            "${speed.wpm.roundToInt()} palavras por minuto. Leia alguns minutos e ele se ajusta."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { settings.resetSpeed() }, enabled = speed.totalMs > 0) { Text("Zerar") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
