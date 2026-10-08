package ru.aw.launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.ModIcons
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

internal data class ScreenshotFolder(val key: String, val label: String, val gameDir: Path)
internal data class ScreenshotFile(val path: Path, val folder: ScreenshotFolder, val bytes: Long, val modified: Long) {
    val stamp: String get() = "$modified:$bytes"
}
internal data class ScreenshotScan(val files: List<ScreenshotFile>, val errors: List<String>)

internal suspend fun scanScreenshots(folders: List<ScreenshotFolder>): ScreenshotScan {
    val files = mutableListOf<ScreenshotFile>()
    val errors = linkedSetOf<String>()
    val supported = setOf("png", "jpg", "jpeg", "webp")
    for (folder in folders.distinctBy { it.gameDir.toAbsolutePath().normalize() }) {
        coroutineContext.ensureActive()
        val directory = folder.gameDir.toAbsolutePath().normalize().resolve("screenshots")
        try {
            if (!Files.exists(directory, NOFOLLOW_LINKS)) continue
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                errors += "Не удалось прочитать папку скриншотов: ${folder.label}"
                continue
            }
            Files.list(directory).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    coroutineContext.ensureActive()
                    val path = iterator.next()
                    if (path.fileName.toString().substringAfterLast('.', "").lowercase() !in supported) continue
                    try {
                        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attributes.isRegularFile) files += ScreenshotFile(path, folder, attributes.size(), attributes.lastModifiedTime().toMillis())
                    } catch (_: IOException) { errors += "Не удалось прочитать часть снимков: ${folder.label}" }
                }
            }
        } catch (_: IOException) { errors += "Не удалось прочитать папку скриншотов: ${folder.label}" }
        catch (_: UncheckedIOException) { errors += "Не удалось прочитать папку скриншотов: ${folder.label}" }
        catch (_: SecurityException) { errors += "Нет доступа к скриншотам: ${folder.label}" }
    }
    return ScreenshotScan(files.sortedWith(compareByDescending<ScreenshotFile> { it.modified }.thenBy { it.path.toString() }), errors.toList())
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ScreenshotsScreen(state: LauncherState, entry: VersionEntry? = null) {
    val entries = if (entry != null) listOf(entry) else (state.buildChoices() + listOfNotNull(state.currentEntry())).distinctBy { it.key }
    val folders = entries.map { ScreenshotFolder(it.key, it.title, state.gameDirOf(it)) }
        .distinctBy { it.gameDir.toAbsolutePath().normalize() }
    var scan by remember { mutableStateOf<ScreenshotScan?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableStateOf(0) }
    var group by rememberSaveable(entry?.key) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(entry?.key) { mutableStateOf("") }
    var selectedPath by rememberSaveable(entry?.key) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    ReportOpen(menu, state::trackMenu)
    LaunchedEffect(folders, revision, state.instanceRevision) {
        loading = true
        while (isActive) {
            scan = withContext(Dispatchers.IO) { scanScreenshots(folders) }
            loading = false
            delay(5_000)
        }
    }
    val selectedGroup = group?.takeIf { key -> folders.any { it.key == key } }
    val files = scan?.files.orEmpty().filter { (selectedGroup == null || it.folder.key == selectedGroup) &&
        (query.isBlank() || it.path.fileName.toString().contains(query, true) || it.folder.label.contains(query, true)) }
    val selectedIndex = files.indexOfFirst { it.path.toString() == selectedPath }
    val selected = files.getOrNull(selectedIndex)
    val viewerFocus = remember { FocusRequester() }
    LaunchedEffect(selected != null) { if (selected != null) viewerFocus.requestFocus() }
    val outer = if (entry == null) Modifier.fillMaxSize().padding(AWDimens.Gutter) else Modifier.fillMaxSize()
    Column(outer.onPreviewKeyEvent { event ->
        if (selected == null || event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.Escape -> { selectedPath = null; true }
            Key.DirectionLeft -> { files.getOrNull(selectedIndex - 1)?.let { selectedPath = it.path.toString() }; true }
            Key.DirectionRight -> { files.getOrNull(selectedIndex + 1)?.let { selectedPath = it.path.toString() }; true }
            else -> false
        }
    }.focusRequester(viewerFocus).focusable(selected != null), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected != null) AWButton("Все скриншоты", onClick = { selectedPath = null })
            else {
                if (entry == null) Text("Скриншоты", color = AWColors.Text, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.CenterVertically))
                if (entry == null) Box {
                    AWButton(folders.firstOrNull { it.key == selectedGroup }?.label ?: "Все сборки", translate = selectedGroup == null,
                        icon = Icons.Default.KeyboardArrowDown, onClick = { menu = true })
                    AWDropdownMenu(menu, onDismissRequest = { menu = false }) {
                        AWMenuItem("Все сборки", onClick = { group = null; menu = false })
                        folders.forEach { folder -> AWMenuItem(folder.label, translate = false, onClick = { group = folder.key; menu = false }) }
                    }
                }
                AWButton("Обновить", icon = Icons.Default.Refresh, enabled = !loading, onClick = { revision++ })
            }
        }
        if (selected != null) {
            ScreenshotImage(selected, Modifier.weight(1f).fillMaxWidth(), full = true)
            Text(selected.path.fileName.toString(), translate = false, color = AWColors.Text, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${selected.folder.label} · ${screenshotDate(selected.modified)} · ${selectedIndex + 1} / ${files.size}",
                translate = false, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AWButton("Предыдущее", enabled = selectedIndex > 0, onClick = { selectedPath = files[selectedIndex - 1].path.toString() })
                AWButton("Следующее", enabled = selectedIndex < files.lastIndex, onClick = { selectedPath = files[selectedIndex + 1].path.toString() })
                if (entry == null) AWButton("Открыть сборку", onClick = {
                    entries.firstOrNull { it.key == selected.folder.key }?.let { selectedPath = null; state.openInstance(it) }
                })
            }
        } else {
            SearchField(query, onValueChange = { query = it }, placeholder = "Поиск скриншотов…", modifier = Modifier.fillMaxWidth())
            Text(if (loading) "Читаю скриншоты…" else "Скриншотов: ${files.size}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            when {
                scan == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Читаю скриншоты…", color = AWColors.TextMuted) }
                files.isEmpty() -> EmptyState(AWIcons.Image, if (query.isBlank()) "Скриншотов пока нет" else "Ничего не найдено",
                    if (query.isBlank()) "Сделай снимок в Minecraft клавишей F2 — он появится здесь" else "Попробуй другое имя файла или сборки",
                    Modifier.weight(1f).fillMaxWidth())
                else -> LazyVerticalGrid(GridCells.Adaptive(230.dp), state = grid, modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(files, key = { it.path.toString() }) { file ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerCard)).background(AWColors.Surface)
                            .clickable { selectedPath = file.path.toString() }) {
                            ScreenshotImage(file, Modifier.fillMaxWidth().height(150.dp))
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(file.path.fileName.toString(), translate = false, color = AWColors.Text, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(file.folder.label, translate = false, color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(screenshotDate(file.modified), translate = false, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            scan?.errors?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("\n"), translate = false,
                color = AWColors.Warning, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun screenshotDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

@Composable
private fun ScreenshotImage(file: ScreenshotFile, modifier: Modifier, full: Boolean = false) {
    var loading by remember(file.path, file.stamp, full) { mutableStateOf(true) }
    val bitmap by produceState<ImageBitmap?>(null, file.path, file.stamp, full) {
        value = null
        value = ModIcons.loadLocal(file.path, if (full) 1280 else 384, stamp = file.stamp, maxBytes = 32 * 1024 * 1024)
        loading = false
    }
    Box(modifier.clip(RoundedCornerShape(AWDimens.CornerCard)).background(AWColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, file.path.fileName.toString(), Modifier.fillMaxSize(), contentScale = if (full) ContentScale.Fit else ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Icon(AWIcons.Image, null, tint = AWColors.TextMuted, modifier = Modifier.size(28.dp))
            Text(if (loading) "Загружаю…" else if (file.bytes > 32 * 1024 * 1024) "Снимок больше 32 МБ" else "Не удалось открыть снимок",
                color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
