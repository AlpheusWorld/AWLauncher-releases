package ru.aw.launcher.ui.dialogs

import ru.aw.launcher.ui.theme.AWDimens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import java.nio.file.Files
import java.nio.file.Path

/** Only reads one directory at a time; never scans profiles on the UI thread. */
internal fun profileDirectories(directory: Path): List<Path> =
    Files.newDirectoryStream(directory).use { children ->
        children.filter { Files.isDirectory(it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName.toString() })
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileDirectoryPicker(
    onDismiss: () -> Unit,
    onSelect: (Path) -> Unit,
    initialDirectory: Path = Path.of(System.getProperty("user.home")),
) {
    val scope = rememberCoroutineScope()
    var directory by remember { mutableStateOf(initialDirectory.toAbsolutePath().normalize()) }
    var address by remember { mutableStateOf(directory.toString()) }
    var chosen by remember { mutableStateOf(directory) }
    var folders by remember { mutableStateOf<List<Path>>(emptyList()) }
    var places by remember { mutableStateOf<List<Pair<String, Path>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    val scroll = rememberLazyListState()

    fun open(path: Path) {
        directory = path.toAbsolutePath().normalize()
        address = directory.toString()
        chosen = directory
        folders = emptyList()
        query = ""
        error = null
        loading = true
        revision++
    }

    fun openAddress() {
        runCatching {
            require(address.isNotBlank())
            val path = Path.of(address.trim().removeSurrounding("\""))
            open(if (path.isAbsolute) path else directory.resolve(path))
        }.onFailure { error = "Проверь путь к папке" }
    }

    LaunchedEffect(Unit) {
        places = withContext(Dispatchers.IO) {
            val home = Path.of(System.getProperty("user.home"))
            val oneDrive = System.getenv("OneDrive")?.let { Path.of(it) }
            val minecraft = System.getenv("APPDATA")?.let { Path.of(it).resolve(".minecraft") }
                ?: home.resolve(".minecraft")
            val shortcuts = listOfNotNull(
                "Домашняя папка" to home,
                "Рабочий стол" to (oneDrive?.resolve("Desktop")?.takeIf { Files.isDirectory(it) } ?: home.resolve("Desktop")),
                "Загрузки" to home.resolve("Downloads"),
                "Документы" to (oneDrive?.resolve("Documents")?.takeIf { Files.isDirectory(it) } ?: home.resolve("Documents")),
                "Minecraft" to minecraft,
            ).filter { Files.isDirectory(it.second) }
            shortcuts + home.fileSystem.rootDirectories.map { it.toString() to it }
        }
    }

    LaunchedEffect(directory, revision) {
        loading = true
        error = null
        try {
            folders = withContext(Dispatchers.IO) { profileDirectories(directory) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            folders = emptyList()
            error = "Не удалось открыть папку — проверь путь и доступ к ней"
        } finally {
            loading = false
        }
    }

    val visible = remember(folders, query) {
        folders.filter { it.fileName.toString().contains(query.trim(), ignoreCase = true) }
    }
    // LazyListState waits for layout; the list is only mounted after loading finishes.
    LaunchedEffect(directory, visible, loading, error) {
        if (!loading && error == null && visible.isNotEmpty()) scroll.scrollToItem(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        val short = maxHeight < 600.dp
        val browserHeight = (maxHeight - if (short) 360.dp else 420.dp).coerceIn(84.dp, 300.dp)
        AWDialog(
            title = "Папка для импорта",
            subtitle = if (short) null else "Выбери профиль Minecraft или папку с несколькими сборками",
            width = (maxWidth - 32.dp).coerceIn(360.dp, 820.dp),
            onDismiss = { if (!confirming) onDismiss() },
            actions = {
                AWButton("Отмена", enabled = !confirming, onClick = onDismiss)
                AWButton("Выбрать папку", style = ButtonStyle.PRIMARY,
                    enabled = !loading && !confirming && error == null,
                    onClick = {
                        confirming = true
                        val selected = chosen
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    // Opening the stream also catches inaccessible or removed folders.
                                    Files.newDirectoryStream(selected).use { }
                                }
                                onSelect(selected)
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                error = "Папка недоступна — выбери другую или обнови список"
                            } finally {
                                confirming = false
                            }
                        }
                    })
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(enabled = directory.parent != null && !confirming,
                        onClick = { directory.parent?.let(::open) }) {
                        Icon(Icons.Default.KeyboardArrowUp, "На папку выше", tint = AWColors.TextSoft)
                    }
                    AWTextField(address, { address = it }, "Путь к папке",
                        modifier = Modifier.weight(1f), onSubmit = ::openAddress)
                    AWButton("Перейти", enabled = !confirming, onClick = ::openAddress)
                }
                Row(Modifier.fillMaxWidth().height(browserHeight), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(if (compact) 130.dp else 174.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Быстрый доступ", style = MaterialTheme.typography.labelSmall,
                            color = AWColors.TextMuted, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(places) { (label, path) ->
                                ChoiceChip(if (compact && label == "Домашняя папка") "Домой" else label,
                                    directory == path, icon = if (compact) null else AWIcons.Folder,
                                    enabled = !confirming, modifier = Modifier.fillMaxWidth(), onClick = { open(path) })
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AWTextField(query, { query = it }, "Найти папку", leadingIcon = Icons.Default.Search,
                            clearable = true, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(AWDimens.CornerMedium)).background(AWColors.Background)) {
                            when {
                                loading -> Text("Загружаю папки…", color = AWColors.TextMuted,
                                    modifier = Modifier.align(Alignment.Center))
                                error != null -> Column(Modifier.align(Alignment.Center).padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(error!!, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall)
                                    AWButton("Обновить", onClick = { open(directory) })
                                }
                                visible.isEmpty() -> Text(if (query.isBlank()) "Внутри нет папок" else "Папки не найдены",
                                    color = AWColors.TextMuted, modifier = Modifier.align(Alignment.Center))
                                else -> {
                                    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                                        contentPadding = PaddingValues(4.dp)) {
                                        items(visible, key = { it.toString() }) { path ->
                                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                                .background(if (chosen == path) AWColors.AccentSoft else AWColors.Background)
                                                .semantics { selected = chosen == path }
                                                .onPreviewKeyEvent {
                                                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && !confirming) {
                                                        open(path); true
                                                    } else false
                                                }
                                                .combinedClickable(enabled = !confirming,
                                                    onClick = { chosen = path }, onDoubleClick = { open(path) })
                                                .padding(start = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Icon(AWIcons.Folder, null, tint = AWColors.Accent, modifier = Modifier.size(20.dp))
                                                Text(path.fileName.toString(), color = AWColors.Text, translate = false,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                                IconButton(enabled = !confirming, onClick = { open(path) }) {
                                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Открыть папку", tint = AWColors.TextMuted)
                                                }
                                            }
                                        }
                                    }
                                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                                }
                            }
                        }
                    }
                }
                if (!short) Text("Двойной щелчок открывает папку", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium)).background(AWColors.SurfaceHigh).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AWIcons.Folder, null, tint = AWColors.Accent, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Выбранная папка", color = AWColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                        Text(chosen.toString(), color = AWColors.Text, translate = false,
                            style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
