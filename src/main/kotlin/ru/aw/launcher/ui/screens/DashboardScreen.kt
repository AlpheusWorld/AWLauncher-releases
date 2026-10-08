package ru.aw.launcher.ui.screens

import androidx.compose.animation.animateColorAsState

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.aw.launcher.activity.PlaySession
import ru.aw.launcher.core.NoticeLevel
import ru.aw.launcher.core.Settings
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.ui.CatalogTab
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.ModIcons
import ru.aw.launcher.ui.Screen
import ru.aw.launcher.ui.SkinHead
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.Modal
import ru.aw.launcher.ui.PresetInstanceIcon
import ru.aw.launcher.instance.InstanceImages
import ru.aw.launcher.ui.VersionPill
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWMotion
import ru.aw.launcher.ui.theme.AWDimens

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DashboardScreen(state: LauncherState) {
    var query by rememberSaveable { mutableStateOf("") }
    var recentFirst by rememberSaveable { mutableStateOf(false) }
    var loader by remember { mutableStateOf<LoaderKind?>(null) }
    var onlyInstalled by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupsMenu by remember { mutableStateOf(false) }
    val settings by Settings.state.collectAsState()
    LaunchedEffect(settings.libraryGroups) { if (groupFilter != null && settings.libraryGroups.none { it.id == groupFilter }) groupFilter = null }
    val choices = remember(state.builds, state.packs, state.versions) { state.buildChoices() }
    LaunchedEffect(choices) { state.loadLibraryMetadata() }
    LaunchedEffect(choices) { selected = selected.intersect(choices.map { it.key }.toSet()) }
    val metadata = remember(choices, state.instanceRevision) { choices.associate { it.key to state.libraryOptions(it) } }
    val chosen = choices.filter { it.key in selected }
    val editable = !state.busy && !state.buildsBusy && !state.libraryBusy && !state.libraryLoading && !state.savingInstanceSettings
    val sessions = state.activity?.recent.orEmpty()
    val lastPlayed = remember(choices, state.activity) {
        choices.associate { it.key to (state.lastPlayedOf(it) ?: 0L) }
    }
    val library = remember(choices, query, recentFirst, loader, onlyInstalled, state.installed, state.profiles, lastPlayed, metadata, favoritesOnly, groupFilter) {
        choices.filter { entry ->
            (query.isBlank() || entry.title.contains(query.trim(), ignoreCase = true) || entry.id.contains(query.trim(), ignoreCase = true)) &&
                (loader == null || entry.loader == loader) && (!onlyInstalled || state.isEntryInstalled(entry)) &&
                (!favoritesOnly || metadata[entry.key]?.favorite == true) &&
                (groupFilter == null || metadata[entry.key]?.groupId == groupFilter)
        }.let { filtered ->
            filtered.sortedWith(compareByDescending<VersionEntry> { metadata[it.key]?.favorite == true }
                .thenByDescending { if (recentFirst) lastPlayed[it.key] ?: 0L else 0L }.thenBy { it.title.lowercase() })
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compactHeight = maxHeight < 720.dp
    val sidebarWidth = AWDimens.sidebarWidth(maxWidth)
    Column(Modifier.fillMaxSize().background(AWColors.Background)) {
        Row(Modifier.fillMaxSize()) {
            FileDropArea(Modifier.weight(1f).fillMaxHeight(), enabled = editable, label = "Отпусти папку или архив сборки", onFiles = state::receiveLibraryFiles) {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RecentLibrary(state, sessions, maxRows = if (compactHeight) 1 else 2)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Библиотека", color = AWColors.Text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    AWButton("Группы", icon = AWIcons.Folder, enabled = editable, onClick = { state.modal = Modal.Groups() })
                    AWButton("Новая сборка", style = ButtonStyle.PRIMARY, icon = Icons.Default.Add, enabled = !state.buildsBusy && !state.busy, onClick = { creating = true })
                }
                SearchField(query, onValueChange = { query = it }, placeholder = "Поиск сборок…", modifier = Modifier.fillMaxWidth(), focusRequester = state.searchFocus)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryChoice(if (recentFirst) "Недавно играли" else "Название", listOf("Название", "Недавно играли")) { recentFirst = it == "Недавно играли" }
                    LibraryChoice(loader?.label ?: "Все загрузчики", listOf("Все загрузчики") + LoaderKind.entries.map { it.label }) { label ->
                        loader = LoaderKind.entries.firstOrNull { it.label == label }
                    }
                    ChoiceChip("Скачанные", onlyInstalled, onClick = { onlyInstalled = !onlyInstalled })
                    ChoiceChip("Избранное", favoritesOnly, icon = Icons.Default.Star, onClick = { favoritesOnly = !favoritesOnly })
                    Box {
                        AWButton(settings.libraryGroups.firstOrNull { it.id == groupFilter }?.name ?: "Все группы", translate = groupFilter == null, icon = Icons.Default.KeyboardArrowDown, onClick = { groupsMenu = true })
                        AWDropdownMenu(groupsMenu, onDismissRequest = { groupsMenu = false }) {
                            AWMenuItem("Все группы", onClick = { groupFilter = null; groupsMenu = false })
                            settings.libraryGroups.forEach { group -> AWMenuItem(group.name, translate = false, onClick = { groupFilter = group.id; groupsMenu = false }) }
                        }
                    }
                    AWButton(if (selectionMode) "Готово" else "Выбрать", enabled = editable, onClick = { selectionMode = !selectionMode; selected = emptySet() })
                }
                if (selectionMode) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AWButton(if (library.isNotEmpty() && library.all { it.key in selected }) "Снять выделение" else "Выбрать всё", enabled = editable,
                        onClick = { selected = if (library.all { it.key in selected }) selected - library.map { it.key }.toSet() else selected + library.map { it.key } })
                    AWButton("В группу · ${chosen.size}", enabled = editable && chosen.isNotEmpty(), onClick = { state.modal = Modal.Groups(chosen.map { it.key }) })
                    val allFavorite = chosen.isNotEmpty() && chosen.all { metadata[it.key]?.favorite == true }
                    AWButton(if (allFavorite) "Убрать из избранного" else "В избранное", enabled = editable && chosen.isNotEmpty(), onClick = { state.setFavorites(chosen, !allFavorite) })
                    AWButton("Удалить", style = ButtonStyle.DANGER, enabled = editable && chosen.isNotEmpty(), onClick = { state.modal = Modal.DeleteMany(chosen) })
                }
                if (library.isEmpty()) {
                    EmptyState(
                        AWIcons.Layers,
                        if (choices.isEmpty()) "Создай первую сборку" else "Ничего не нашлось",
                        if (choices.isEmpty()) "У каждого профиля будут свои моды, миры и настройки" else "Попробуй изменить поиск или фильтры",
                        Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        if (choices.isEmpty()) {
                            AWButton("Каталог Modrinth", onClick = { state.openCatalog(tab = CatalogTab.PACKS) })
                        }
                    }
                } else LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(library, key = { it.key }) { entry ->
                        Box(Modifier.animateItem(
                            fadeInSpec = AWMotion.ItemFade,
                            placementSpec = AWMotion.ItemPlacement,
                            fadeOutSpec = AWMotion.ItemFade,
                        )) {
                            LibraryCard(state, entry, compactHeight, selectionMode, entry.key in selected) {
                                selected = if (entry.key in selected) selected - entry.key else selected + entry.key
                            }
                        }
                    }
                }
                if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { StatusStrip(state.progress, null, state.stage.ifBlank { "Готовлюсь" }) }
                    AWButton("Отменить", onClick = state::cancel)
                }
            }
            }
            if (sidebarWidth != null) DashboardSidebar(state, Modifier.width(sidebarWidth).fillMaxHeight())
        }
    }
    }
    if (creating) BuildsScreen(state, creationOnly = true, onCreationDismiss = { creating = false })
}

@Composable
private fun RecentLibrary(state: LauncherState, sessions: List<PlaySession>, maxRows: Int) {
    var expanded by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Недавнее", color = AWColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = AWColors.TextMuted)
            }
            Spacer(Modifier.weight(1f))
            Text("Активность →", color = AWColors.Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { state.screen = Screen.ACTIVITY })
        }
        if (expanded) {
            val recent = remember(sessions) { sessions.distinctBy { Triple(it.buildId ?: it.instance, it.versionId, it.server) } }
            if (recent.isEmpty()) Text("Здесь появятся последние миры и серверы", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
            else BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 760.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    recent.take(columns * maxRows).chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { session -> RecentCard(state, session, Modifier.weight(1f)) }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun recentEntry(state: LauncherState, session: PlaySession): VersionEntry? = when {
    session.buildId != null -> state.builds.firstOrNull { it.id == session.buildId }?.let(state::entryFor)
    session.pack != null -> state.packs.firstOrNull { it.id == session.instance || it.title == session.pack }?.let(state::entryFor)
    else -> state.versions.firstOrNull { it.id == session.versionId }?.let { VersionEntry(it, session.loader) }
}

@Composable
private fun RecentCard(state: LauncherState, session: PlaySession, modifier: Modifier) {
    val entry = recentEntry(state, session)
    val shape = RoundedCornerShape(AWDimens.CornerLarge)
    Row(
        modifier.clip(shape).background(AWColors.Surface).border(1.dp, AWColors.Outline.copy(alpha = 0.6f), shape)
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (session.server == null) BuildArtwork(entry, Modifier.size(42.dp), 20.dp, state)
        else Box(Modifier.size(42.dp).clip(RoundedCornerShape(AWDimens.CornerMedium)).background(AWColors.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(AWIcons.Server, null, tint = AWColors.Accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val label = entry?.title ?: session.label.takeUnless { it.startsWith("aw-build-") } ?: "Сборка недоступна"
            Text(session.server ?: label, color = AWColors.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = entry == null && label == "Сборка недоступна")
            Text("$label · ${formatReleaseDate(java.time.Instant.ofEpochMilli(session.start).toString())}", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WithTooltip(if (entry == null) "Сборка больше не найдена" else null) {
            AWButton("Играть", icon = Icons.Default.PlayArrow, style = ButtonStyle.PRIMARY, enabled = entry != null && !state.busy, modifier = Modifier.height(44.dp), onClick = {
                entry?.let { state.selectEntry(it); state.play(serverAddress = session.server) }
            })
        }
        if (entry != null) BuildMenu(state, entry)
    }
}

@Composable
private fun LibraryCard(state: LauncherState, entry: VersionEntry, compact: Boolean, selectionMode: Boolean, checked: Boolean, toggle: () -> Unit) {
    val selected = state.isSelected(entry)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered || selected) AWColors.SurfaceHigh else AWColors.Surface,
        AWMotion.Hover, label = "libraryRowHover")
    val shape = RoundedCornerShape(AWDimens.CornerMedium)
    val settings by Settings.state.collectAsState()
    val group = settings.libraryGroups.firstOrNull { it.id == state.libraryOptions(entry).groupId }?.name
    Row(Modifier.fillMaxWidth().clip(shape).background(background)
        .clickable(interactionSource = interaction, indication = null) { if (selectionMode) toggle() else state.openInstance(entry) }
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selectionMode) Checkbox(checked, onCheckedChange = { toggle() })
        BuildArtwork(entry, Modifier.size(if (compact) 44.dp else 52.dp).clip(shape), 32.dp, state)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, color = AWColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, translate = false)
            Text(listOfNotNull("${entry.loader.label} ${entry.id}", group).joinToString(" · "),
                color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, translate = false)
        }
        IconButton(onClick = { state.toggleFavorite(entry) }, enabled = !state.libraryBusy && !state.libraryLoading,
            modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Star, "Избранное", tint = if (state.libraryOptions(entry).favorite) AWColors.Warning else AWColors.TextMuted,
                modifier = Modifier.size(17.dp))
        }
        AWButton("Играть", icon = Icons.Default.PlayArrow, style = if (selected) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
            enabled = !state.busy && !state.buildsBusy, onClick = { state.selectEntry(entry); state.play() })
        BuildMenu(state, entry)
    }
}

@Composable
internal fun BuildArtwork(entry: VersionEntry?, modifier: Modifier, iconSize: Dp, state: LauncherState? = null) {
    state?.instanceRevision
    val options = entry?.let { state?.libraryOptions(it) }
    if (options?.iconPreset != null) {
        PresetInstanceIcon(options.iconPreset, options.iconBackground, modifier)
        return
    }
    val url = options?.iconUrl ?: entry?.pack?.iconUrl
    val localIcon = entry?.let { value -> state?.let { InstanceImages.resolve(it.gameDirOf(value), options?.iconImage) } }
        ?: entry?.build?.takeIf { it.iconFile == "aw-build-icon.png" }?.let { ru.aw.launcher.instance.LocalBuilds.dirOf(it).resolve("aw-build-icon.png") }
    val imageKey = localIcon?.toString() ?: url
    val bitmap by produceState<ImageBitmap?>(imageKey?.let { ModIcons.cached(it, 256) }, imageKey) {
        value = if (localIcon != null) ModIcons.loadLocal(localIcon) else url?.let { ModIcons.load(it, 256) }
    }
    val tint = loaderColor(entry?.loader ?: LoaderKind.VANILLA)
    Box(modifier.clip(RoundedCornerShape(AWDimens.CornerCard)).background(options?.iconBackground?.let(ru.aw.launcher.ui.InstanceIcons::color) ?: tint.copy(alpha = 0.10f))
        .border(1.dp, tint.copy(alpha = 0.20f), RoundedCornerShape(AWDimens.CornerCard)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, entry?.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else PresetInstanceIcon("cube", options?.iconBackground ?: when (entry?.loader) {
            LoaderKind.FABRIC -> "blue"; LoaderKind.FORGE -> "amber"; LoaderKind.QUILT -> "violet"; else -> "emerald"
        }, Modifier.fillMaxSize())
    }
}

@Composable
private fun BuildMenu(state: LauncherState, entry: VersionEntry) {
    var open by remember { mutableStateOf(false) }
    ReportOpen(open, state::trackMenu)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.MoreVert, null, tint = AWColors.TextMuted, modifier = Modifier.size(18.dp)) }
        AWDropdownMenu(open, onDismissRequest = { open = false }) { EntryMenuItems(state, entry, { open = false }) }
    }
}

@Composable
private fun LibraryChoice(label: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AWButton(label, icon = Icons.Default.KeyboardArrowDown, onClick = { open = true }, modifier = Modifier.height(44.dp))
        AWDropdownMenu(open, onDismissRequest = { open = false }) { options.forEach { option -> AWMenuItem(option, onClick = { open = false; onSelect(option) }) } }
    }
}

@Composable
internal fun DashboardSidebar(state: LauncherState, modifier: Modifier) {
    val notices by state.notices.collectAsState()
    val separator = AWColors.Outline
    Column(modifier.background(AWColors.Sidebar).drawBehind {
        drawLine(separator, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx())
    }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Последние уведомления", color = AWColors.TextMuted, style = MaterialTheme.typography.titleSmall)
        if (notices.isEmpty()) Text("Пока тихо", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        notices.take(3).forEach { notice ->
            val color = when (notice.level) {
                NoticeLevel.ERROR -> AWColors.Danger
                NoticeLevel.WARNING -> AWColors.Warning
                NoticeLevel.SUCCESS -> AWColors.Success
                NoticeLevel.INFO -> AWColors.Accent
            }
            Column(
                Modifier.fillMaxWidth().clickable { state.openNotices() }.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (notice.level == NoticeLevel.ERROR) AWIcons.Log else AWIcons.History, null, tint = color, modifier = Modifier.size(20.dp))
                notice.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AWColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(notice.text, style = MaterialTheme.typography.bodySmall, color = AWColors.TextSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("Все уведомления →", color = AWColors.Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { state.openNotices() })
        }
        SidebarAccount(state)
    }
}

@Composable
private fun SidebarAccount(state: LauncherState) {
    val accounts by state.accounts.collectAsState()
    val selected by state.selectedAccount.collectAsState()
    var open by remember { mutableStateOf(false) }
    ReportOpen(open, state::trackMenu)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Аккаунт", color = AWColors.TextMuted, style = MaterialTheme.typography.titleSmall)
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerCard)).background(AWColors.SurfaceHigh)
                    .clickable { if (accounts.isEmpty()) state.screen = Screen.ACCOUNTS else open = true }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                SkinHead(selected, 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected?.name ?: "Добавить аккаунт", translate = selected == null,
                        color = AWColors.Text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (selected == null) "Не выбран" else if (selected?.isOffline == true) "Офлайн" else "Microsoft",
                        color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.KeyboardArrowDown, "Выбрать аккаунт", tint = AWColors.TextSoft, modifier = Modifier.size(18.dp))
            }
            AWDropdownMenu(open, onDismissRequest = { open = false }) {
                accounts.forEach { account ->
                    AWMenuItem(account.name, translate = false, icon = Icons.Default.Check.takeIf { account.uuid == selected?.uuid },
                        onClick = { state.selectAccount(account.uuid); open = false })
                }
                MenuDivider()
                AWMenuItem("Аккаунты", icon = Icons.Default.Person, onClick = { open = false; state.screen = Screen.ACCOUNTS })
            }
        }
    }
}

@Composable
private fun loaderColor(loader: LoaderKind): Color = when (loader) {
    LoaderKind.FABRIC -> AWColors.LoaderFabric
    LoaderKind.QUILT -> AWColors.LoaderQuilt
    LoaderKind.FORGE -> AWColors.LoaderForge
    LoaderKind.NEOFORGE -> AWColors.LoaderNeoForge
    LoaderKind.VANILLA -> AWColors.LoaderVanilla
}
