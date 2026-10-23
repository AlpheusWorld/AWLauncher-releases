package ru.aw.launcher.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import ru.aw.launcher.instance.BuildHistory
import ru.aw.launcher.instance.BuildHistoryEntry
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun InstanceHistory(state: LauncherState, entry: VersionEntry) {
    var history by remember(entry.key) { mutableStateOf<List<BuildHistoryEntry>?>(null) }
    var error by remember(entry.key) { mutableStateOf<String?>(null) }
    var restoring by remember(entry.key) { mutableStateOf<BuildHistoryEntry?>(null) }
    var deleting by remember(entry.key) { mutableStateOf<BuildHistoryEntry?>(null) }
    var restoreWorlds by remember(entry.key) { mutableStateOf(false) }
    var expanded by remember(entry.key) { mutableStateOf<Set<String>>(emptySet()) }
    val busy = state.libraryEntryBusy(entry) || state.savingInstanceSettings || state.buildsBusy || state.libraryBusy
    val includeWorlds = state.libraryOptions(entry).historyIncludeWorlds
    val dir = state.gameDirOf(entry)
    LaunchedEffect(entry.key, state.instanceRevision, busy) {
        if (busy) return@LaunchedEffect
        try { history = BuildHistory.list(dir); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Не удалось прочитать историю сборки" }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Состояния до изменений", color = AWColors.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Снимки сохраняют моды, настройки и другой контент перед обновлениями и массовыми изменениями. Выбери снимок, чтобы отменить обновление",
            color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AWButton("Вернуть рабочее состояние", style = ButtonStyle.PRIMARY, enabled = !busy && !history.isNullOrEmpty(), onClick = {
                restoring = history?.firstOrNull(); restoreWorlds = false
            })
            AWButton("Сохранить состояние", enabled = !busy, onClick = { state.createHistorySnapshot(entry) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AWSwitch(includeWorlds, enabled = !busy) { state.saveHistoryWorlds(entry, it) }
            Text("Сохранять миры в новых снимках", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (includeWorlds) "Миры могут занимать много места и увеличивать время сохранения" else "Миры не копируются и при обычном откате сохраняют текущий прогресс",
            color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        when {
            error != null -> ListHint(error.orEmpty())
            history == null -> ListHint("Читаю историю…")
            history!!.isEmpty() -> EmptyState(AWIcons.Log, "История пока пуста", "Сохрани состояние вручную или обнови контент сборки", Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history.orEmpty(), key = { it.id }) { saved ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerCard)).background(AWColors.Surface).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(saved.title, color = AWColors.Text, style = MaterialTheme.typography.titleSmall, translate = false)
                        Text(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(saved.createdAt)) +
                            " · Minecraft ${saved.gameVersion} · ${saved.loader.label}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, translate = false)
                        Text((if (saved.includesWorlds) "Снимок с мирами" else "Снимок без миров") + " · ${formatBytes(saved.sizeBytes)}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        if (!saved.completed || saved.interrupted) Text("Операция прервана или не завершена — сохранено состояние до её начала", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        if (saved.changes.isNotEmpty()) {
                            Text("Изменено проектов: ${saved.changes.size}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            if (saved.id in expanded) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                saved.changes.forEach { change ->
                                    Text(change.title, color = AWColors.Text, style = MaterialTheme.typography.bodySmall, translate = false)
                                    Text("${change.before ?: "Не установлен"} → ${change.after ?: "Удалён"}", color = AWColors.Accent, style = MaterialTheme.typography.bodySmall, translate = false)
                                }
                            }
                        } else Text("Сохранены файлы и настройки сборки", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AWButton("Восстановить", enabled = !busy, onClick = { restoring = saved; restoreWorlds = false })
                            if (saved.changes.isNotEmpty()) AWButton(if (saved.id in expanded) "Скрыть изменения" else "Показать изменения", onClick = {
                                expanded = if (saved.id in expanded) expanded - saved.id else expanded + saved.id
                            })
                            AWButton("Удалить", style = ButtonStyle.DANGER, enabled = !busy, onClick = { deleting = saved })
                        }
                    }
                }
            }
        }
    }
    restoring?.let { saved ->
        AWDialog("Вернуть сохранённое состояние?", onDismiss = { restoring = null }, width = 520.dp, actions = {
            AWButton("Отмена", onClick = { restoring = null })
            AWButton("Восстановить", style = ButtonStyle.PRIMARY, enabled = !busy, onClick = {
                state.restoreHistorySnapshot(entry, saved, restoreWorlds); restoring = null
            })
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Файлы и настройки будут возвращены к состоянию до выбранного изменения. Перед откатом сохранится текущее состояние",
                    color = AWColors.TextMuted)
                Text("Minecraft ${saved.gameVersion} · ${saved.title}", color = AWColors.Text, translate = false)
                if (saved.includesWorlds) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AWSwitch(restoreWorlds) { restoreWorlds = it }
                        Text("Восстановить и миры", color = AWColors.Text)
                    }
                    if (restoreWorlds) Text("Прогресс миров после этого снимка будет отменён", color = AWColors.TextMuted)
                }
                if (!restoreWorlds) Text("Прогресс миров сохранится. Датапаки вернутся к состоянию из снимка", color = AWColors.TextMuted)
            }
        }
    }
    deleting?.let { saved ->
        AWDialog("Удалить снимок?", onDismiss = { deleting = null }, width = 440.dp, actions = {
            AWButton("Отмена", onClick = { deleting = null })
            AWButton("Удалить", style = ButtonStyle.DANGER, enabled = !busy, onClick = {
                state.deleteHistorySnapshot(entry, saved.id); deleting = null
            })
        }) { Text("Этот снимок исчезнет из истории. Текущие файлы сборки сохранятся", color = AWColors.TextMuted) }
    }
}
