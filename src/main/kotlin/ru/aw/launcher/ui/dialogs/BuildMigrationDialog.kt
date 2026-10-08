package ru.aw.launcher.ui.dialogs

import ru.aw.launcher.ui.theme.AWDimens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.aw.launcher.instance.BuildMigration
import ru.aw.launcher.instance.MigrationPlan
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuildMigrationDialog(state: LauncherState, entry: VersionEntry, previewPlan: MigrationPlan? = null) {
    val scope = rememberCoroutineScope()
    val candidates = state.versions.filter { it.id != entry.id && state.loaderSupport.supports(entry.loader, it.id) && state.isVersionVisible(it) }
    var target by remember { mutableStateOf(previewPlan?.targetVersion ?: candidates.firstOrNull()?.id.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf(previewPlan) }
    var keep by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    AWDialog("Изменить версию Minecraft", onDismiss = { state.modal = null }, width = 760.dp, scrollable = true,
        subtitle = "${entry.title} · ${entry.id} · ${entry.loader.label}", actions = {
            AWButton("Отмена", onClick = { state.modal = null })
            AWButton(if (plan == null) "Проверить моды" else "Обновить эту сборку", style = ButtonStyle.PRIMARY,
                enabled = target.isNotBlank() && !checking && !state.libraryEntryBusy(entry), onClick = {
                    val ready = plan
                    if (ready != null) state.migrateBuild(entry, ready, keep)
                    else {
                        checking = true; error = null
                        job = scope.launch {
                            try {
                                plan = BuildMigration.analyze(state.gameDirOf(entry), entry.id, target, entry.loader)
                                keep = emptySet()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Не удалось проверить моды" }
                            finally { checking = false }
                        }
                    }
                })
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Обновляется эта сборка. Папка, миры и история сохранятся", color = AWColors.TextSoft,
                style = MaterialTheme.typography.bodySmall)
            Text("Новая версия Minecraft", color = AWColors.TextMuted, style = MaterialTheme.typography.labelLarge)
            Box {
                AWButton(target.ifBlank { "Нет доступных версий" } + " · ${entry.loader.label}", onClick = { menu = true }, enabled = !checking)
                AWDropdownMenu(menu, onDismissRequest = { menu = false }) {
                    Column(Modifier.width(320.dp).padding(10.dp)) {
                        AWTextField(query, onValueChange = { query = it }, placeholder = "Найти версию", modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.height(220.dp)) {
                            items(candidates.filter { query.isBlank() || it.id.contains(query.trim(), true) }, key = { it.id }) { version ->
                                AWMenuItem(version.id, onClick = {
                                    job?.cancel(); target = version.id; plan = null; keep = emptySet(); error = null; menu = false; query = ""
                                })
                            }
                        }
                    }
                }
            }
            if (checking) Text("Проверяю совместимость модов на Modrinth…", color = AWColors.Accent)
            error?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall, translate = false) }
            plan?.let { ready ->
                val missing = ready.mods.filter { it.replacement == null }
                Text("Совместимых модов: ${ready.mods.size - missing.size} · Без подходящей версии: ${missing.size}",
                    color = AWColors.Text, fontWeight = FontWeight.Bold)
                Text("Совместимые моды, загрузчик и Java обновятся автоматически",
                    color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall)
                if (missing.isNotEmpty()) {
                    Text("Отметь моды, которые нужно оставить включёнными. Их совместимость не подтверждена",
                        color = AWColors.Warning, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AWButton("Отключить неподходящие", onClick = { keep = emptySet() })
                        AWButton("Оставить включёнными", onClick = { keep = missing.filter { it.item.enabled }.map { it.item.fileName }.toSet() })
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = AWColors.Outline)
                for (mod in ready.mods) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (mod.replacement == null) Checkbox(mod.item.fileName in keep, enabled = mod.item.enabled,
                            onCheckedChange = { enabled -> keep = if (enabled) keep + mod.item.fileName else keep - mod.item.fileName })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(mod.item.title, color = AWColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, translate = false)
                            Text(mod.replacement?.let { "${mod.item.versionNumber.ifBlank { mod.item.fileName }} → ${it.versionNumber}" }
                                ?: mod.reason.orEmpty(), color = if (mod.replacement == null) AWColors.Warning else AWColors.Accent,
                                style = MaterialTheme.typography.bodySmall, translate = false)
                            if (!mod.item.enabled) Text("Мод был отключён", color = AWColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("Резервные файлы: aw-migration-backups. При ошибке сборка восстановится автоматически",
                    color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                Text("Перед запуском новой версии сохрани копию миров",
                    color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
