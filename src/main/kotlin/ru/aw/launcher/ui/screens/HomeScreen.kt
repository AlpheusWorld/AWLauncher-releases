package ru.aw.launcher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.io.path.exists
import ru.aw.launcher.core.Shortcuts
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.Modal
import ru.aw.launcher.ui.Screen
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.VersionGroup
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.ui.components.ContextMenuBox
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWDropdownMenu
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.AWMenuItem
import ru.aw.launcher.ui.components.MenuDivider
import ru.aw.launcher.ui.components.Panel
import ru.aw.launcher.ui.components.ReportOpen
import ru.aw.launcher.ui.components.SearchField
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.components.WithTooltip
import ru.aw.launcher.ui.components.formatBytes
import ru.aw.launcher.ui.components.formatReleaseDate
import ru.aw.launcher.ui.components.formatSpeed
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape
import ru.aw.launcher.ui.theme.glow

@Composable
fun PlayScreen(state: LauncherState) {
    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter)) {
        QuickPlayCard(state)
        Spacer(Modifier.height(14.dp))
        BuildSelectorPanel(state, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun BuildSelectorPanel(state: LauncherState, modifier: Modifier = Modifier) {
    val builds = state.buildChoices()
    Panel(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Мои сборки", style = MaterialTheme.typography.titleMedium, color = AWColors.Text, modifier = Modifier.weight(1f))
                AWButton(
                    "Управление",
                    onClick = { state.screen = Screen.BUILDS },
                    enabled = !state.buildsBusy,
                    icon = AWIcons.Layers,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (builds.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(AWIcons.Layers, null, tint = AWColors.Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Сборок пока нет", style = MaterialTheme.typography.titleMedium, color = AWColors.Text)
                    Spacer(Modifier.height(12.dp))
                    AWButton(
                        "Создать сборку",
                        onClick = { state.screen = Screen.BUILDS },
                        style = ButtonStyle.PRIMARY,
                        enabled = !state.buildsBusy,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(builds, key = { it.key }) { entry -> BuildSelectionRow(state, entry) }
                }
            }
        }
    }
}

@Composable
private fun BuildSelectionRow(state: LauncherState, entry: VersionEntry) {
    val selected = state.isSelected(entry)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AWDimens.CornerMedium))
            .background(if (selected) AWColors.AccentSoft else AWColors.SurfaceHigh)
            .clickable(enabled = !state.busy) { state.selectEntry(entry) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, color = if (selected) AWColors.Accent else AWColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Minecraft ${entry.id} · ${entry.loader.label}", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(AWIcons.Layers, null, tint = AWColors.Accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun QuickPlayCard(state: LauncherState) {
    val entry = state.busyEntry ?: state.currentBuildEntry()
    val installed = entry != null && state.isEntryInstalled(entry)

    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("К ЗАПУСКУ", style = MaterialTheme.typography.labelSmall, color = AWColors.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        entry?.title ?: "сборка не выбрана",
                        style = if (entry == null || entry.pack != null || entry.build != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                        color = if (entry == null) AWColors.TextMuted else AWColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when {
                        entry?.pack != null -> Tag("${entry.id} ${entry.loader.label}", loaderColor(entry.loader))
                        entry != null && entry.loader.isModded -> Tag(entry.loader.label, loaderColor(entry.loader))
                    }
                }
                if (entry != null) {
                    Spacer(Modifier.height(16.dp))
                    InstanceChips(state, entry)
                }
            }

            Spacer(Modifier.width(20.dp))

            Box(Modifier.width(320.dp), contentAlignment = Alignment.CenterEnd) {
                if (state.busy) {
                    ProgressArea(state)
                } else {
                    PlayButton(installed = installed, enabled = entry != null, onClick = { state.play() })
                }
            }
        }
    }
}

@Composable
private fun PlayButton(installed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        when {
            pressed && enabled -> 0.96f
            hovered && enabled -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "playScale",
    )
    val glow by animateDpAsState(if (hovered && enabled) 30.dp else 20.dp, label = "playGlow")
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = AWColors.Accent,
            contentColor = AWColors.OnAccent,
            disabledContainerColor = AWColors.SurfaceHigh,
            disabledContentColor = AWColors.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 32.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (enabled) Modifier.glow(PillShape, AWColors.Accent.copy(alpha = 0.55f), glow) else Modifier)
            .defaultMinSize(minWidth = 260.dp)
            .height(72.dp),
    ) {
        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                !enabled -> "Выбери сборку"
                installed -> "Играть"
                else -> "Установить и играть"
            },
            fontWeight = FontWeight.Black,
            fontSize = if (installed) 20.sp else 17.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun InstanceChips(state: LauncherState, entry: VersionEntry) {
    val options = state.selectedOptions

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WithTooltip("Открыть папку: миры, скриншоты, моды и настройки") {
            ChoiceChip(
                label = "Папка",
                selected = false,
                onClick = { state.openFolder(state.gameDirOf(entry)) },
                icon = AWIcons.Folder,
            )
        }

        val pack = entry.pack
        if (pack != null && pack.id in state.packUpdates) {
            WithTooltip("Вышла ${state.packUpdates[pack.id]?.version.orEmpty()}. Миры, настройки и твои моды останутся".trim()) {
                ChoiceChip(
                    label = "Обновить сборку",
                    selected = true,
                    onClick = { state.updatePack(pack) },
                    enabled = !state.busy,
                    icon = Icons.Default.Refresh,
                )
            }
        }

        EntryMenuButton(state, entry)
    }
}

@Composable
private fun EntryMenuButton(state: LauncherState, entry: VersionEntry) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    ReportOpen(open, state::trackMenu)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, "Действия со сборкой", tint = AWColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        AWDropdownMenu(expanded = open, onDismissRequest = close) {
            EntryMenuItems(state, entry, close, fromList = false)
        }
    }
}

@Composable
fun EntryMenuItems(state: LauncherState, entry: VersionEntry, close: () -> Unit, fromList: Boolean = true) {
    val installed = state.isEntryInstalled(entry)
    val hasFolder = remember(entry, state.instanceRevision) { state.gameDirOf(entry).exists() }
    val metadata = state.libraryOptions(entry)
    AWMenuItem(if (metadata.favorite) "Убрать из избранного" else "В избранное", enabled = !state.libraryBusy, onClick = { close(); state.toggleFavorite(entry) })
    AWMenuItem("Изменить иконку", enabled = !state.libraryBusy && !state.busy && !state.buildsBusy, onClick = { close(); state.editInstanceIcon(entry) })
    AWMenuItem("Изменить группу", enabled = !state.libraryBusy && !state.busy && !state.buildsBusy, onClick = { close(); state.modal = Modal.Groups(listOf(entry.key)) })
    AWMenuItem("Дублировать", enabled = hasFolder && !state.busy && !state.buildsBusy && !state.libraryBusy, onClick = { close(); state.modal = Modal.Duplicate(entry) })
    if (entry.build != null || entry.pack != null) AWMenuItem("Изменить версию Minecraft", icon = Icons.Default.Refresh,
        enabled = !state.libraryEntryBusy(entry), onClick = { close(); state.openMigration(entry) })
    MenuDivider()

    if (fromList) {
        AWMenuItem("Играть", icon = Icons.Default.PlayArrow, enabled = !state.busy, onClick = {
            close()
            state.selectEntry(entry)
            state.play()
        })
        AWMenuItem("Открыть папку", icon = AWIcons.Folder, onClick = {
            close()
            state.openFolder(state.gameDirOf(entry))
        })
    }
    AWMenuItem("Логи", icon = AWIcons.Log, onClick = {
        close()
        state.showLogs(entry)
    })
    AWMenuItem("Ярлык на рабочем столе", icon = AWIcons.OpenInNew, enabled = Shortcuts.isAvailable, onClick = {
        close()
        state.createShortcut(entry)
    })
    MenuDivider()
    if (entry.pack == null || entry.pack.customGameVersion) AWMenuItem("Скачать файлы", icon = AWIcons.Download,
        enabled = !state.downloads.containsEntry(entry.key), onClick = { close(); state.downloadVersion(entry) })
    AWMenuItem("Переустановить", icon = Icons.Default.Refresh, enabled = (installed || entry.pack != null) && !state.downloads.containsEntry(entry.key), onClick = {
        close()
        state.reinstall(entry)
    })
    AWMenuItem(
        "Удалить…",
        icon = Icons.Default.Delete,
        danger = true,
        enabled = (installed || hasFolder) && !(state.busy && state.busyEntry == entry),
        onClick = {
            close()
            state.modal = Modal.Delete(entry)
        },
    )
}

@Composable
private fun ProgressArea(state: LauncherState) {
    val progress = state.progress
    Column(Modifier.width(320.dp)) {
        Text(
            state.stage.ifBlank { "Готовлюсь" },
            style = MaterialTheme.typography.bodySmall,
            color = AWColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        ProgressBar(progress?.fraction ?: -1f)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progressDetails(progress),
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Отмена",
                style = MaterialTheme.typography.labelLarge,
                color = AWColors.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(AWDimens.CornerSmall))
                    .clickable { state.cancel() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun progressDetails(progress: DownloadProgress?): String = when {
    progress == null -> ""
    progress.totalBytes > 0 -> buildString {
        append(formatBytes(progress.completedBytes)).append(" из ").append(formatBytes(progress.totalBytes))
        formatSpeed(progress.bytesPerSecond).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
    }
    progress.totalFiles > 0 -> "${progress.completedFiles} из ${progress.totalFiles} файлов"
    else -> ""
}

@Composable
private fun ProgressBar(fraction: Float) {
    val animated by animateFloatAsState(if (fraction < 0f) 0f else fraction, label = "progress")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(8.dp)
            .clip(PillShape)
            .background(AWColors.SurfaceHigh)
    ) {
        if (fraction < 0f) {
            val sweep by rememberInfiniteTransition(label = "indeterminate").animateFloat(
                initialValue = -0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
                label = "sweep",
            )
            Box(
                Modifier.fillMaxHeight()
                    .width(maxWidth * 0.35f)
                    .offset(x = maxWidth * sweep)
                    .clip(PillShape)
                    .background(AWColors.Accent)
            )
        } else {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(PillShape)
                    .background(AWColors.Accent)
            )
        }
    }
}

@Composable
private fun ListHeader(state: LauncherState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchField(
            value = state.searchQuery,
            onValueChange = { state.searchQuery = it },
            placeholder = "Поиск версии",
            modifier = Modifier.weight(1f),
            focusRequester = state.searchFocus,
            onFocusChange = { state.searchFocused = it },
        )
        WithTooltip(if (state.onlyInstalled) "Показать все версии" else "Показать только скачанные версии и сборки") {
            ChoiceChip(
                label = "Скачанные",
                selected = state.onlyInstalled,
                onClick = state::toggleOnlyInstalled,
                icon = AWIcons.Download,
            )
        }
    }
}

private class DoubleClick {
    private var lastKey: String? = null
    private var lastAt = 0L

    fun isSecondClick(key: String): Boolean {
        val now = System.currentTimeMillis()
        val second = key == lastKey && now - lastAt < DOUBLE_CLICK_MILLIS
        lastKey = if (second) null else key
        lastAt = now
        return second
    }

    private companion object {
        const val DOUBLE_CLICK_MILLIS = 400L
    }
}

@Composable
private fun GroupedVersionList(state: LauncherState, groups: List<VersionGroup>) {
    val clicks = remember { DoubleClick() }
    LazyColumn(state = state.listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { group ->
            item(key = "header-${group.key}") {
                GroupHeader(
                    group = group,
                    expanded = state.isExpanded(group),
                    onToggle = { state.toggleGroup(group.key) },
                )
            }
            item(key = "body-${group.key}") {
                AnimatedVisibility(
                    visible = state.isExpanded(group),
                    enter = expandVertically(
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(200, delayMillis = 60)),
                    exit = shrinkVertically(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                    ) + fadeOut(tween(150)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        group.entries.forEach { entry -> EntryRow(state, entry, clicks) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: VersionGroup, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "groupArrow")
    val versions = remember(group) { group.entries.distinctBy { it.build?.id ?: it.pack?.id ?: it.id }.size }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AWDimens.CornerSmall))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = AWColors.TextMuted,
            modifier = Modifier.size(18.dp).rotate(rotation),
        )
        Text(
            group.key,
            style = MaterialTheme.typography.titleMedium,
            color = AWColors.Text,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            versions.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = AWColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(state: LauncherState, entry: VersionEntry, clicks: DoubleClick) {
    val selected = state.isSelected(entry)
    val installed = state.isEntryInstalled(entry)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> AWColors.AccentSoft
            hovered -> AWColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "rowBackground",
    )
    val dotSize by animateDpAsState(if (installed) 7.dp else 0.dp, label = "installedDot")

    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, state.keyboardMoves) {
        if (selected && state.keyboardMoves > 0) requester.bringIntoView()
    }

    ContextMenuBox(
        modifier = Modifier.padding(start = 18.dp),
        onOpenChange = { open ->
            state.trackMenu(open)
            if (open) state.selectEntry(entry)
        },
        menu = { close -> EntryMenuItems(state, entry, close) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(requester)
                .clip(RoundedCornerShape(AWDimens.CornerMedium))
                .background(background)
                .clickable(interactionSource = interaction, indication = null) {
                    state.selectEntry(entry)
                    if (clicks.isSecondClick(entry.key)) state.play()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(7.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(dotSize).clip(CircleShape).background(AWColors.Accent))
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) AWColors.Accent else AWColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Tag(entry.loader.label, loaderColor(entry.loader))
            }
            Text(
                if (entry.pack != null || entry.build != null) entry.id else formatReleaseDate(entry.version.releaseTime),
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.TextMuted,
            )
        }
    }
}

@Composable
private fun loaderColor(kind: LoaderKind) = when (kind) {
    LoaderKind.FABRIC -> AWColors.LoaderFabric
    LoaderKind.QUILT -> AWColors.LoaderQuilt
    LoaderKind.FORGE -> AWColors.LoaderForge
    LoaderKind.NEOFORGE -> AWColors.LoaderNeoForge
    LoaderKind.VANILLA -> AWColors.LoaderVanilla
}

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
    }
}
