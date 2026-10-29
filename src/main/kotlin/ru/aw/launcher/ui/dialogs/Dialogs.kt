package ru.aw.launcher.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Shell
import ru.aw.launcher.core.Storage
import ru.aw.launcher.logs.GameLogs
import ru.aw.launcher.logs.LogSource
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.Modal
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWDialog
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.LogLines
import ru.aw.launcher.ui.components.formatBytes
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens

@Composable
fun ModalHost(state: LauncherState) {
    state.exportProgress?.let { fraction ->
        AWDialog("Экспорт сборки", onDismiss = {}, width = 420.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Экспортировать в .mrpack", color = AWColors.Text)
                ru.aw.launcher.ui.components.ThinProgress(fraction, Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}%", color = AWColors.TextMuted, translate = false)
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        SettingsWindowHost(state, (maxWidth - 32.dp).coerceIn(380.dp, 980.dp), (maxHeight - 48.dp).coerceIn(320.dp, 680.dp))
    }
    when (val modal = state.modal) {
        null -> Unit
        is Modal.Delete -> DeleteDialog(state, modal.entry)
        is Modal.Logs -> LogDialog(state, modal)
        is Modal.Icon -> IconEditorDialog(state, modal.entry)
        is Modal.Groups -> LibraryGroupsDialog(state, modal.entryKeys)
        is Modal.Duplicate -> DuplicateDialog(state, modal.entry)
        is Modal.Export -> ExportDialog(state, modal.entry)
        is Modal.Migrate -> BuildMigrationDialog(state, modal.entry, modal.plan, modal.keepEnabled, modal.failure)
        is Modal.DeleteMany -> DeleteManyDialog(state, modal.entries)
        is Modal.ImportDirectory -> ru.aw.launcher.ui.screens.ImportDirectoryDialog(state, modal.directory, { state.modal = null }) {
            state.modal = null; state.importProfiles(it)
        }
        is Modal.Settings -> Unit
    }
}

private class DeleteInfo(val versionBytes: Long, val folderExists: Boolean, val folderBytes: Long, val hasWorlds: Boolean)

@Composable
private fun DeleteDialog(state: LauncherState, entry: VersionEntry) {
    val dir = state.gameDirOf(entry)
    var info by remember { mutableStateOf<DeleteInfo?>(null) }
    var withFolder by remember { mutableStateOf(false) }
    LaunchedEffect(entry) {
        info = withContext(Dispatchers.IO) {
            val ids = if (entry.pack != null || entry.build != null) emptyList() else Storage.versionIdsOf(entry.id, entry.loader, state.versions.map { it.id })
            DeleteInfo(
                versionBytes = ids.sumOf { Storage.sizeOf(Paths.versionDir(it)) },
                folderExists = dir.exists(),
                folderBytes = Storage.sizeOf(dir),
                hasWorlds = Storage.hasWorlds(dir),
            )
        }
    }
    val close = { state.modal = null }

    AWDialog(
        title = "Удалить ${entry.label}?",
        onDismiss = close,
        width = 500.dp,
        actions = {
            AWButton("Отмена", onClick = close)
            AWButton(
                when {
                    entry.build != null -> "Удалить сборку"
                    withFolder -> "Удалить всё"
                    else -> "Удалить"
                },
                style = ButtonStyle.DANGER,
                enabled = info != null,
                onClick = {
                    state.delete(entry, withFolder)
                    close()
                },
            )
        },
    ) {
        val known = info
        if (known == null) {
            Text("Считаю, сколько места освободится…", style = MaterialTheme.typography.bodyMedium, color = AWColors.TextMuted)
            return@AWDialog
        }
        if (entry.pack != null || entry.build != null) {
            Text(
                "Папка сборки — " + (if (known.hasWorlds) "миры, " else "") + "моды и настройки (${formatBytes(known.folderBytes)}) — " +
                    if (Storage.trashAvailable) "уйдёт в корзину, её можно будет восстановить." else "удалится насовсем.",
                style = MaterialTheme.typography.bodyMedium,
                color = AWColors.Text,
            )
            return@AWDialog
        }
        Text(
            if (known.versionBytes > 0) {
                "Файлы версии (${formatBytes(known.versionBytes)}) удалятся. Если снова выбрать эту версию, они скачаются заново."
            } else {
                "Файлов версии на диске уже нет."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AWColors.Text,
        )
        if (known.folderExists) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AWDimens.CornerMedium))
                    .clickable { withFolder = !withFolder }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = withFolder,
                    onCheckedChange = { withFolder = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AWColors.Danger,
                        uncheckedColor = AWColors.TextMuted,
                        checkmarkColor = AWColors.Background,
                    ),
                )
                Column(Modifier.padding(top = 12.dp)) {
                    Text(
                        "Удалить и папку сборки — " + (if (known.hasWorlds) "миры, " else "") +
                            "моды и настройки (${formatBytes(known.folderBytes)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AWColors.Text,
                    )
                    Text(
                        if (Storage.trashAvailable) "Папка уйдёт в корзину, её можно будет восстановить."
                        else "Восстановить её будет нельзя.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (Storage.trashAvailable) AWColors.TextMuted else AWColors.Danger,
                    )
                }
            }
        }
    }
}

private sealed interface ShareState {
    data object Idle : ShareState
    data object Confirm : ShareState
    data object Uploading : ShareState
    data object Copied : ShareState
    data class CopyFailed(val message: String) : ShareState
    data class Done(val url: String) : ShareState
    data class Failed(val message: String) : ShareState
}

@Composable
private fun LogDialog(state: LauncherState, modal: Modal.Logs) {
    var source by remember { mutableStateOf(modal.source) }
    val file = remember(source) { GameLogs.locate(source, modal.gameDir) }
    var lines by remember(file) { mutableStateOf<List<String>?>(null) }
    var readError by remember(file) { mutableStateOf<String?>(null) }
    var share by remember(file) { mutableStateOf<ShareState>(ShareState.Idle) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { GameLogs.readForView(file) } }
            .onSuccess { lines = it }
            .onFailure { readError = it.message }
    }

    val close = { state.modal = null }
    AWDialog(
        title = "Логи",
        subtitle = listOfNotNull(modal.title, file?.fileName?.toString()).joinToString(" · "),
        onDismiss = close,
        width = 860.dp,
        actions = {
            AWButton("Копировать", icon = AWIcons.Copy, enabled = file != null, onClick = {
                val target = file ?: return@AWButton
                scope.launch {
                    share = runCatching {
                        val text = withContext(Dispatchers.IO) { GameLogs.readForShare(target) }
                        clipboard.setText(AnnotatedString(text))
                        ShareState.Copied
                    }.getOrElse { ShareState.CopyFailed(it.message ?: "неизвестная ошибка") }
                }
            })
            AWButton("Открыть папку", icon = AWIcons.Folder, enabled = file != null, onClick = {
                file?.parent?.let(state::openFolder)
            })
            AWButton(
                "Поделиться…",
                icon = Icons.Default.Share,
                style = ButtonStyle.PRIMARY,
                enabled = file != null && share != ShareState.Uploading,
                onClick = { share = ShareState.Confirm },
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogSource.entries.forEach { option ->
                ChoiceChip(
                    option.label,
                    selected = source == option,
                    enabled = option == LogSource.LAUNCHER || modal.gameDir != null,
                    onClick = { source = option },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(AWDimens.CornerCard))
                .background(AWColors.Background),
        ) {
            val shown = lines
            when {
                file == null -> LogHint(emptyLogText(source))
                readError != null -> LogHint("Не удалось прочитать лог: $readError")
                shown == null -> LogHint("Читаю…")
                shown.isEmpty() -> LogHint("Лог пуст")
                else -> {
                    androidx.compose.runtime.key(file) { LogLines(shown, Modifier.fillMaxSize()) }
                }
            }
        }
        ShareStatus(
            share = share,
            onConfirm = {
                val target = file ?: return@ShareStatus
                scope.launch {
                    share = ShareState.Uploading
                    share = runCatching {
                        val text = withContext(Dispatchers.IO) { GameLogs.readForShare(target) }
                        val url = GameLogs.share(text)
                        clipboard.setText(AnnotatedString(url))
                        ShareState.Done(url)
                    }.getOrElse { ShareState.Failed(it.message ?: "неизвестная ошибка") }
                }
            },
            onCancel = { share = ShareState.Idle },
        )
    }
}

private fun emptyLogText(source: LogSource): String = when (source) {
    LogSource.GAME -> "Лога пока нет: эту сборку ещё не запускали."
    LogSource.CRASH -> "Крэш-репортов нет — игра в этой сборке не падала."
    LogSource.LAUNCHER -> "Лог лаунчера не найден."
}

@Composable
private fun LogHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AWColors.TextMuted)
    }
}

@Composable
private fun ShareStatus(share: ShareState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (share == ShareState.Idle) return
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AWDimens.CornerMedium))
            .background(AWColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (share) {
            ShareState.Confirm -> {
                Text(
                    "Лог уйдёт на mclo.gs, и открыть его сможет любой, у кого будет ссылка. " +
                        "Токены и путь к папке пользователя будут скрыты.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.Text,
                    modifier = Modifier.weight(1f),
                )
                AWButton("Отмена", onClick = onCancel)
                AWButton("Загрузить", style = ButtonStyle.PRIMARY, onClick = onConfirm)
            }
            ShareState.Uploading -> Text("Загружаю на mclo.gs…", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
            ShareState.Copied -> Text(
                "Лог скопирован. Токены и путь к папке пользователя в нём скрыты.",
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.TextMuted,
            )
            is ShareState.CopyFailed -> Text(
                "Не удалось скопировать: ${share.message}",
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.Danger,
            )
            is ShareState.Done -> {
                Text("Ссылка скопирована:", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                Text(
                    share.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AWColors.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(AWDimens.CornerSmall)).clickable { Shell.browse(share.url) },
                )
            }
            is ShareState.Failed -> Text(
                "Не удалось загрузить: ${share.message}",
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.Danger,
            )
            ShareState.Idle -> Unit
        }
    }
}

@Composable
private fun ExportDialog(state: LauncherState, entry: VersionEntry) {
    var paths by remember(entry.key) { mutableStateOf<List<String>?>(null) }
    var selected by remember(entry.key) { mutableStateOf<Set<String>>(emptySet()) }
    var failure by remember(entry.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.key) {
        try {
            val names = withContext(Dispatchers.IO) {
                val excluded = setOf("aw-instance.json", "aw-pack.json", "aw-build-history", "aw-migration-backups")
                java.nio.file.Files.list(state.gameDirOf(entry)).use { stream ->
                    stream.filter { !java.nio.file.Files.isSymbolicLink(it) && it.fileName.toString() !in excluded }
                        .map { it.fileName.toString() }.sorted().toList()
                }
            }
            paths = names
            selected = names.toSet()
        } catch (e: Exception) { failure = e.message ?: "Не удалось прочитать файлы сборки" }
    }
    AWDialog("Экспорт сборки", onDismiss = { state.modal = null }, width = 560.dp, actions = {
        AWButton("Отмена", onClick = { state.modal = null })
        AWButton("Сохранить .mrpack", style = ButtonStyle.PRIMARY, enabled = paths != null && failure == null && !state.libraryBusy, onClick = {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = "Сохранить сборку"
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Сборка Modrinth (*.mrpack)", "mrpack")
                selectedFile = java.io.File(entry.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".mrpack")
            }
            if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile.toPath()
                val target = if (file.fileName.toString().endsWith(".mrpack", true)) file else file.resolveSibling(file.fileName.toString() + ".mrpack")
                if (!java.nio.file.Files.exists(target) || javax.swing.JOptionPane.showConfirmDialog(null, "Заменить существующий файл?", "Экспорт сборки", javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION) {
                    state.modal = null
                    state.exportMrpack(entry, target, selected)
                }
            }
        })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Выбери файлы и папки для экспорта. Миры и другие личные данные также будут включены, если оставить их выбранными", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            if (failure != null) Text(failure.orEmpty(), color = AWColors.Danger, translate = false)
            else if (paths == null) Text("Читаю файлы…", color = AWColors.TextMuted)
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected.size == paths!!.size, onCheckedChange = { selected = if (it) paths!!.toSet() else emptySet() })
                    Text("Выбрать всё", color = AWColors.Text)
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                    items(paths!!.size) { index ->
                        val name = paths!![index]
                        Row(Modifier.fillMaxWidth().clickable { selected = if (name in selected) selected - name else selected + name }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = name in selected, onCheckedChange = { checked -> selected = if (checked) selected + name else selected - name })
                            Text(name, color = AWColors.Text, translate = false)
                        }
                    }
                }
            }
        }
    }
}
