package ru.aw.launcher.ui.screens

import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.SettingsDefaults
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.logs.GameLogs
import ru.aw.launcher.logs.LogSource
import ru.aw.launcher.mods.*
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.*

@Composable
fun InstanceScreen(state: LauncherState, initialTab: Int = 0) {
    val entry = state.instanceEntry()
    if (entry == null) {
        EmptyState(AWIcons.Layers, "Сборка больше не найдена", "Выбери другую сборку в библиотеке", Modifier.fillMaxSize()) {
            AWButton("Библиотека", onClick = { state.screen = Screen.HOME })
        }
        return
    }
    val model = state.contentModel(entry)
    var tab by rememberSaveable(entry.key) { mutableStateOf(initialTab) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.ensureScanned() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebarWidth = AWDimens.sidebarWidth(maxWidth)
        val compact = maxHeight < 520.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(if (compact) 12.dp else AWDimens.Gutter), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    WithTooltip("Изменить иконку") { BuildArtwork(entry, Modifier.size(if (compact) 48.dp else 64.dp).clickable(enabled = !state.busy && !state.buildsBusy && !state.libraryBusy) { state.editInstanceIcon(entry) }, 38.dp, state) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
                        Text(entry.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = AWColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                        Text("${entry.loader.label} ${entry.id} · ${formatPlayTime(state.playTimeOf(entry))}",
                            color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        val last = state.lastPlayedOf(entry)
                        if (!compact) Text(if (last == null) "Ещё не запускалась" else "Последний запуск: ${formatReleaseDate(Instant.ofEpochMilli(last).toString())}",
                            color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    AWButton("Играть", icon = Icons.Default.PlayArrow, style = ButtonStyle.PRIMARY, enabled = !state.busy && !state.buildsBusy && !state.savingInstanceSettings,
                        onClick = { state.selectEntry(entry); state.play() })
                    IconButton(onClick = { state.openSettings(entry) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Settings, "Настройки", tint = AWColors.TextMuted) }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.MoreVert, "Действия", tint = AWColors.TextMuted) }
                        AWDropdownMenu(menu, onDismissRequest = { menu = false }) { EntryMenuItems(state, entry, { menu = false }) }
                    }
                }
                HorizontalDivider(color = AWColors.Outline)
                Row(Modifier.horizontalScroll(rememberScrollState()).clip(PillShape).background(AWColors.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Контент", "Файлы", "Миры", "Журнал", "Статистика", "Скриншоты").forEachIndexed { index, title ->
                        ChoiceChip(title, selected = tab == index, onClick = { tab = index })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> InstanceContent(state, entry, model)
                        1 -> InstanceFiles(state, entry)
                        2 -> InstanceFiles(state, entry, worlds = true)
                        3 -> InstanceJournal(state, entry)
                        4 -> ActivityScreen(state, entry)
                        else -> ScreenshotsScreen(state, entry)
                    }
                }
                if (state.busy && state.busyEntry?.key == entry.key) StatusStrip(state.progress, null, state.stage)
            }
            if (sidebarWidth != null) DashboardSidebar(state, Modifier.width(sidebarWidth).fillMaxHeight())
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun InstanceContent(state: LauncherState, entry: VersionEntry, model: ContentModel) {
    var query by rememberSaveable(entry.key) { mutableStateOf("") }
    var kindName by rememberSaveable(entry.key) { mutableStateOf<String?>(null) }
    var descending by rememberSaveable(entry.key) { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var selected by remember(entry.key) { mutableStateOf<Set<String>>(emptySet()) }
    var removing by remember { mutableStateOf(false) }
    var droppedFiles by remember { mutableStateOf<List<Path>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(query, kindName, descending) { listState.scrollToItem(0) }
    var sortMenu by remember { mutableStateOf(false) }
    LaunchedEffect(model.message) {
        val message = model.message ?: return@LaunchedEffect
        delay(4_000)
        if (model.message == message) model.message = null
    }
    val kind = kindName?.let { ContentKind.valueOf(it) }
    val rows = remember(model.installed, query, kindName, descending) {
        model.installed.values.flatten().filter { item ->
            (kind == null || item.kind == kind) && (query.isBlank() || item.title.contains(query, true) || item.fileName.contains(query, true))
        }.sortedBy { it.title.lowercase() }.let { if (descending) it.reversed() else it }
    }
    val busy = model.working.isNotEmpty() || state.busy || state.buildsBusy
    val chosen = model.installed.values.flatten().filter { it.rowKey in selected }
    LaunchedEffect(model.installed) { selected = selected.intersect(model.installed.values.flatten().map { it.rowKey }.toSet()) }
    FileDropArea(Modifier.fillMaxSize(), enabled = !busy, label = "Отпусти файлы, чтобы добавить их в сборку", onFiles = { files ->
        if (files.all { it.fileName.toString().endsWith(".jar", true) } && entry.loader.isModded) model.addFiles(files, ContentKind.MOD)
        else if (files.all { it.fileName.toString().endsWith(".zip", true) }) {
            if (kind == ContentKind.SHADER || kind == ContentKind.RESOURCE_PACK) model.addFiles(files, kind) else droppedFiles = files
        } else state.fail("Добавляй моды .jar или наборы ресурсов и шейдеры .zip", entry)
    }) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(query, onValueChange = { query = it }, placeholder = "Поиск контента…", modifier = Modifier.weight(1f))
            AWButton("Добавить файлы", icon = AWIcons.Folder, enabled = !busy, onClick = { adding = true })
            AWButton("Найти проекты", style = ButtonStyle.PRIMARY, enabled = !state.buildsBusy, onClick = {
                state.openCatalog(entry, when (kind) {
                    ContentKind.SHADER -> CatalogTab.SHADERS
                    ContentKind.RESOURCE_PACK -> CatalogTab.RESOURCE_PACKS
                    else -> if (entry.loader.isModded) CatalogTab.MODS else CatalogTab.RESOURCE_PACKS
                })
            })
        }
        if (chosen.isEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip("Всё", selected = kind == null, onClick = { kindName = null })
                ContentKind.entries.forEach { content -> ChoiceChip(content.title, selected = kind == content, onClick = { kindName = content.name }) }
            }
            Box {
                WithTooltip(if (descending) "Название (Я–А)" else "Название (А–Я)") {
                    IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, "Сортировка", tint = AWColors.TextMuted) }
                }
                AWDropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    AWMenuItem("Название (А–Я)", onClick = { descending = false; sortMenu = false })
                    AWMenuItem("Название (Я–А)", onClick = { descending = true; sortMenu = false })
                }
            }
            if (model.updates.isNotEmpty()) WithTooltip("Обновить всё (${model.updates.size})") {
                IconButton(onClick = model::updateAll, enabled = !busy, modifier = Modifier.size(44.dp)) { Icon(AWIcons.Download, "Обновить всё", tint = AWColors.Accent) }
            }
            WithTooltip(if (model.refreshing) "Обновляю список…" else "Обновить список") {
                IconButton(onClick = model::rescan, enabled = !busy && !model.refreshing, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "Обновить список", tint = AWColors.TextMuted) }
            }
        } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Выбрано: ${chosen.size}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            AWButton("Включить", enabled = !busy, onClick = { model.toggleMany(chosen, true); selected = emptySet() })
            AWButton("Выключить", enabled = !busy, onClick = { model.toggleMany(chosen, false); selected = emptySet() })
            AWButton("Обновить", enabled = !busy && chosen.any { it.update != null }, onClick = { model.updateMany(chosen); selected = emptySet() })
            AWButton("Удалить", style = ButtonStyle.DANGER, enabled = !busy, onClick = { removing = true })
            IconButton(onClick = { selected = emptySet() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Снять выделение", tint = AWColors.TextMuted) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val narrow = maxWidth < 650.dp
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).border(1.dp, AWColors.Outline, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().height(44.dp).background(AWColors.SurfaceHigh).padding(start = 10.dp, end = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val selectable = rows
                    Checkbox(selectable.isNotEmpty() && selectable.all { it.rowKey in selected }, enabled = !busy && selectable.isNotEmpty(), onCheckedChange = { checked ->
                        val keys = selectable.map { it.rowKey }.toSet()
                        selected = if (checked) selected + keys else selected - keys
                    }, modifier = Modifier.size(40.dp))
                    Text("Проекты (${rows.size})", color = AWColors.TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1.4f))
                    if (!narrow) Text("Версия", color = AWColors.TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("Действия", color = AWColors.TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(140.dp))
                }
                when {
                    !model.scanned -> ListHint("Смотрю, что стоит в ${entry.title}…")
                    rows.isEmpty() -> EmptyState(AWIcons.Extension, "Контент не найден", "Добавь файлы или найди проекты в каталоге", Modifier.fillMaxSize())
                    else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(end = 12.dp, top = 2.dp, bottom = 2.dp)) {
                            items(rows, key = { it.rowKey }) { item ->
                                val opacity = animateFloatAsState(if (item.enabled) 1f else 0.55f, AWMotion.Muted, label = "modRowOpacity")
                                Row(Modifier.fillMaxWidth().clickable(enabled = item.projectId != null) {
                                    item.projectId?.let { id -> state.openProject(entry, Modrinth.SearchHit(projectId = id, title = item.title,
                                        iconUrl = item.iconUrl, projectType = item.kind.projectType)) }
                                }.background(if (item.rowKey in selected) AWColors.AccentSoft else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(item.rowKey in selected, enabled = !busy, onCheckedChange = { checked ->
                                        selected = if (checked) selected + item.rowKey else selected - item.rowKey
                                    }, modifier = Modifier.size(40.dp))
                                    Row(Modifier.weight(1.4f).graphicsLayer { alpha = opacity.value }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        ModIcon(item.iconUrl, 36.dp)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(item.title, color = AWColors.Text, style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                                            Text(if (narrow) item.versionNumber.ifBlank { item.fileName } else item.kind.title, color = AWColors.TextMuted,
                                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = !narrow)
                                        }
                                    }
                                    if (!narrow) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item.versionNumber.ifBlank { "Неизвестно" }, color = AWColors.Text, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, translate = item.versionNumber.isBlank())
                                        Text(item.fileName, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, translate = false)
                                    }
                                    Row(Modifier.width(140.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                        if (item.update != null) WithTooltip("Обновить ${item.title}") { IconButton(onClick = { model.update(item) }, enabled = !busy, modifier = Modifier.size(40.dp)) {
                                            Icon(AWIcons.Download, "Обновить", tint = AWColors.Accent, modifier = Modifier.size(18.dp))
                                        } }
                                        AWSwitch(item.enabled, enabled = !busy) { model.toggle(item, it) }
                                        WithTooltip("Удалить ${item.title}") { IconButton(onClick = { model.remove(item) }, enabled = !busy, modifier = Modifier.size(40.dp)) {
                                            Icon(Icons.Default.Delete, "Удалить", tint = AWColors.TextMuted, modifier = Modifier.size(18.dp))
                                        } }
                                    }
                                }
                                HorizontalDivider(color = AWColors.Outline)
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                            style = ScrollbarStyle(minimalHeight = 24.dp, thickness = 6.dp, shape = RoundedCornerShape(3.dp), hoverDurationMillis = 120, unhoverColor = AWColors.Outline, hoverColor = AWColors.TextMuted))
                    }
                }
            }
        }
        if (model.progress != null || model.error != null) StatusStrip(model.progress, model.error, null)
        else model.message?.let { Text(it, color = AWColors.Success, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    }
    if (removing) AWDialog("Удалить выбранные проекты?", onDismiss = { removing = false }, width = 470.dp, actions = {
        AWButton("Отмена", onClick = { removing = false })
        AWButton("Удалить", style = ButtonStyle.DANGER, enabled = !busy, onClick = { model.removeMany(chosen); selected = emptySet(); removing = false })
    }) {
        Text("Будут удалены файлы выбранных модов, ресурспаков и шейдеров. Миры сохранятся", color = AWColors.TextMuted)
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) { chosen.forEach { Text(it.title, color = AWColors.Text, translate = false) } }
    }
    droppedFiles?.let { files -> AWDialog("Тип добавляемых файлов", onDismiss = { droppedFiles = null }, width = 440.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Файлов: ${files.size}", color = AWColors.TextMuted)
            AWButton("Ресурспаки", modifier = Modifier.fillMaxWidth(), onClick = { model.addFiles(files, ContentKind.RESOURCE_PACK); droppedFiles = null })
            AWButton("Шейдеры", modifier = Modifier.fillMaxWidth(), onClick = { model.addFiles(files, ContentKind.SHADER); droppedFiles = null })
        }
    } }
    if (adding) AWDialog("Добавить файлы", onDismiss = { adding = false }, width = 430.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ContentKind.entries.filter { it != ContentKind.MOD || entry.loader.isModded }.forEach { content ->
                AWButton(content.title, modifier = Modifier.fillMaxWidth(), onClick = {
                    val chooser = JFileChooser().apply { isMultiSelectionEnabled = true; fileFilter = FileNameExtensionFilter(content.title, content.extension.removePrefix(".")) }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) model.addFiles(chooser.selectedFiles.map { it.toPath() }, content)
                    adding = false
                })
            }
        }
    }
}

private data class InstanceFile(val path: Path, val directory: Boolean, val bytes: Long, val modified: Long)

@Composable
private fun InstanceFiles(state: LauncherState, entry: VersionEntry, worlds: Boolean = false) {
    val root = state.gameDirOf(entry).toAbsolutePath().normalize().let { if (worlds) it.resolve("saves") else it }
    var relative by rememberSaveable(entry.key, worlds) { mutableStateOf("") }
    var query by rememberSaveable(entry.key, worlds) { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var files by remember(root) { mutableStateOf<List<InstanceFile>?>(null) }
    var error by remember(root) { mutableStateOf<String?>(null) }
    val directory = root.resolve(relative).normalize().takeIf { it.startsWith(root) } ?: root
    LaunchedEffect(directory, revision, state.instanceRevision) {
        error = null
        files = null
        try {
            files = withContext(Dispatchers.IO) {
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) emptyList() else Files.list(directory).use { stream ->
                    stream.filter { !Files.isSymbolicLink(it) }.map { path ->
                        val folder = Files.isDirectory(path, NOFOLLOW_LINKS)
                        InstanceFile(path, folder, if (folder) 0 else Files.size(path), Files.getLastModifiedTime(path).toMillis())
                    }.toList().sortedWith(compareByDescending<InstanceFile> { it.directory }.thenBy { it.path.name.lowercase() })
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Не удалось прочитать папку" }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (directory != root) AWButton("Назад", onClick = { relative = root.relativize(directory.parent).toString() })
            SearchField(query, onValueChange = { query = it }, placeholder = if (worlds) "Поиск миров…" else "Поиск файлов…", modifier = Modifier.weight(1f))
            AWButton("Открыть папку", icon = AWIcons.Folder, onClick = { state.openFolder(directory) })
            IconButton(onClick = { revision++ }) { Icon(Icons.Default.Refresh, "Перезагрузить", tint = AWColors.TextMuted) }
        }
        Text(if (relative.isEmpty()) if (worlds) "Миры сборки" else "Папка сборки" else relative,
            color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, translate = relative.isEmpty())
        when {
            error != null -> ListHint(error.orEmpty())
            files == null -> ListHint("Читаю файлы…")
            files!!.isEmpty() -> EmptyState(if (worlds) AWIcons.Cube else AWIcons.Folder,
                if (worlds) "Миров пока нет" else "Папка пуста", if (worlds) "Создай мир в игре или перенеси сборку" else "Добавь файлы через проводник", Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(Modifier.weight(1f)) {
                items(files.orEmpty().filter { query.isBlank() || it.path.name.contains(query, true) }, key = { it.path.toString() }) { file ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = file.directory) {
                        relative = root.relativize(file.path).toString()
                    }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (file.directory) AWIcons.Folder else AWIcons.Log, null, tint = AWColors.TextMuted, modifier = Modifier.size(24.dp))
                        Text(file.path.name, color = AWColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                        Text(if (file.directory) "Папка" else formatBytes(file.bytes), color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        AWButton("Папка", onClick = { state.openFolder(if (file.directory) file.path else directory) })
                    }
                    HorizontalDivider(color = AWColors.Outline)
                }
            }
        }
    }
}

@Composable
private fun InstanceJournal(state: LauncherState, entry: VersionEntry) {
    var query by rememberSaveable(entry.key) { mutableStateOf("") }
    var lines by remember(entry.key) { mutableStateOf<List<String>?>(null) }
    var error by remember(entry.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.key) {
        var previous: Pair<Path, Long>? = null
        while (isActive) {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    val file = GameLogs.locate(LogSource.GAME, state.gameDirOf(entry))
                    val stamp = file?.let { it to it.getLastModifiedTime().toMillis() }
                    if (stamp == null) emptyList<String>() else if (stamp != previous) {
                        previous = stamp
                        GameLogs.readForView(stamp.first).map { GameLogs.redact(it) }
                    } else null
                }
                if (fresh != null) lines = fresh
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось прочитать журнал" }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(query, onValueChange = { query = it }, placeholder = "Поиск в журнале…", modifier = Modifier.weight(1f))
            AWButton("Крэш-репорт", onClick = { state.showLogs(entry, LogSource.CRASH) })
            AWButton("Открыть журнал", onClick = { state.showLogs(entry) })
        }
        when {
            error != null -> ListHint(error.orEmpty())
            lines == null -> ListHint("Читаю журнал…")
            lines!!.isEmpty() -> EmptyState(AWIcons.Log, "Журнал пока пуст", "Он появится после первого запуска игры", Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().background(AWColors.Surface).padding(12.dp)) {
                items(lines.orEmpty().filter { query.isBlank() || it.contains(query, true) }) { line ->
                    Text(line, color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), translate = false)
                }
            }
        }
    }
}
