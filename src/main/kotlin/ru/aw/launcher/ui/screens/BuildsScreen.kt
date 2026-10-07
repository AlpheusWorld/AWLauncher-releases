package ru.aw.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.I18n
import ru.aw.launcher.core.Settings
import ru.aw.launcher.instance.LocalBuild
import ru.aw.launcher.instance.ImportProfile
import ru.aw.launcher.instance.ImportProfiles
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.mods.CatalogSource
import ru.aw.launcher.mods.ContentCatalog
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.packs.Modpack
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.CatalogTab
import ru.aw.launcher.ui.Screen
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWDialog
import ru.aw.launcher.ui.components.AWDropdownMenu
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.AWMenuItem
import ru.aw.launcher.ui.components.AWTextField
import ru.aw.launcher.ui.components.Panel
import ru.aw.launcher.ui.components.SectionTitle
import ru.aw.launcher.ui.components.EmptyState
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.components.AWSwitch
import ru.aw.launcher.ui.components.WithTooltip
import ru.aw.launcher.ui.dialogs.ProfileDirectoryPicker
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens

@Composable
fun BuildsScreen(state: LauncherState, creationOnly: Boolean = false, onCreationDismiss: () -> Unit = {}) {
    var creationOpen by remember { mutableStateOf(creationOnly) }
    var editor by remember { mutableStateOf<BuildEditor?>(null) }
    var removing by remember { mutableStateOf<LocalBuild?>(null) }
    var importSource by remember { mutableStateOf<Path?>(null) }
    var choosingDirectory by remember { mutableStateOf(false) }

    fun browseProjects(query: String, requestedTab: CatalogTab?) {
        val modTarget = state.currentBuildEntry()?.takeIf { it.loader.isModded }
            ?: state.buildChoices().firstOrNull { it.loader.isModded }
        val tab = requestedTab ?: if (modTarget != null) CatalogTab.MODS else CatalogTab.PACKS
        if (tab == CatalogTab.MODS && modTarget == null) {
            creationOpen = false
            state.screen = Screen.PLAY
            state.inform("Сначала создай сборку с Fabric, Forge, NeoForge или Quilt", level = ru.aw.launcher.core.NoticeLevel.INFO)
            return
        }
        state.openCatalog(entry = modTarget.takeIf { tab == CatalogTab.MODS }, tab = tab, query = query)
        creationOpen = false
        if (creationOnly) onCreationDismiss()
    }

    if (!creationOnly) Column(
        Modifier.fillMaxSize().padding(AWDimens.Gutter),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Сборки", style = MaterialTheme.typography.headlineLarge, color = AWColors.Text, modifier = Modifier.weight(1f))
            AWButton(
                "Создать профиль",
                onClick = { creationOpen = true },
                style = ButtonStyle.PRIMARY,
                enabled = !state.buildsBusy,
                icon = Icons.Default.Add,
            )
        }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.builds.isNotEmpty()) item(key = "profiles-heading") { SectionTitle("Мои профили") }
                if (state.builds.isEmpty()) {
                    item(key = "empty-profiles") {
                        EmptyBuilds(onCreate = { creationOpen = true }, modifier = Modifier.fillMaxWidth()
                            .then(if (state.packs.isEmpty()) Modifier.fillParentMaxHeight() else Modifier.height(300.dp)))
                    }
                } else {
                    items(state.builds, key = { "build:${it.id}" }, contentType = { "build" }) { build ->
                        LocalBuildCard(
                            state = state,
                            build = build,
                            enabled = !state.buildsBusy,
                            onRename = { editor = BuildEditor(build) },
                            onRemove = { removing = build },
                        )
                    }
                }

                if (state.packs.isNotEmpty()) {
                    item(key = "packs-heading") {
                        Spacer(Modifier.height(16.dp))
                        SectionTitle("Импортированные сборки")
                    }
                    items(state.packs, key = { "pack:${it.id}" }, contentType = { "pack" }) { pack ->
                        ImportedPackCard(state, pack)
                    }
                }

            }
    }

    if (creationOpen) {
        BuildCreationDialog(
            source = state.catalogSource,
            onSource = { state.catalogSource = it },
            onDismiss = { creationOpen = false; if (creationOnly) onCreationDismiss() },
            onCustom = {
                creationOpen = false
                editor = BuildEditor()
            },
            onSearch = ::browseProjects,
            onMrpack = {
                chooseMrpack()?.let { archive ->
                    creationOpen = false
                    editor = BuildEditor(archive = archive)
                }
            },
            onImportDirectory = {
                creationOpen = false
                choosingDirectory = true
            },
        )
    }

    if (choosingDirectory) {
        ProfileDirectoryPicker(
            onDismiss = { choosingDirectory = false; creationOpen = true },
            onSelect = { directory -> choosingDirectory = false; importSource = directory },
        )
    }

    importSource?.let { source ->
        ImportDirectoryDialog(state, source, onDismiss = { importSource = null; if (creationOnly) onCreationDismiss() }) { profiles ->
            state.importProfiles(profiles)
            importSource = null
            if (creationOnly) onCreationDismiss()
        }
    }

    editor?.let { data ->
        BuildEditorDialog(
            state = state,
            data = data,
            onDismiss = { editor = null; if (creationOnly) onCreationDismiss() },
            onSave = { name, version, loader ->
                when {
                    data.build != null -> state.renameBuild(data.build, name)
                    data.archive != null -> state.importMrpack(data.archive, name)
                    data.sourceDirectory != null -> state.importBuildDirectory(data.sourceDirectory, name, version, loader)
                    else -> state.createBuild(name, version, loader)
                }
                editor = null
                if (creationOnly) onCreationDismiss()
            },
        )
    }

    removing?.let { build ->
        AWDialog(
            title = "Удалить «${build.name}»?",
            subtitle = "Профиль и его папка с модами, настройками и мирами будут удалены",
            onDismiss = { removing = null },
            actions = {
                AWButton("Отмена", onClick = { removing = null })
                AWButton(
                    "Удалить сборку",
                    style = ButtonStyle.DANGER,
                    enabled = !state.buildsBusy,
                    onClick = {
                        state.removeBuild(build)
                        removing = null
                    },
                )
            },
        ) {
            Text(
                if (Storage.trashAvailable) "Папка отправится в корзину, откуда её можно восстановить."
                else "На этом компьютере корзина недоступна — папка удалится без возможности восстановления.",
                style = MaterialTheme.typography.bodyMedium,
                color = AWColors.TextMuted,
            )
        }
    }
}

@Composable
private fun EmptyBuilds(onCreate: () -> Unit, modifier: Modifier) {
    EmptyState(
        AWIcons.Layers, "Создай первую сборку",
        "У каждого профиля будут свои моды, миры и настройки", modifier,
    ) {
        AWButton("Создать профиль", onClick = onCreate, style = ButtonStyle.PRIMARY, icon = Icons.Default.Add)
    }
}

private data class CreationSuggestion(val hit: Modrinth.SearchHit, val tab: CatalogTab)

private suspend fun findCreationSuggestions(query: String, source: CatalogSource): List<CreationSuggestion> = withContext(Dispatchers.IO) {
    ContentCatalog.search(source, "modpack", query, emptyList(), null, 0).hits.take(6)
        .filter { it.projectType == "modpack" }
        .map { CreationSuggestion(it, CatalogTab.PACKS) }
}

@Composable
private fun CreationSuggestionRow(suggestion: CreationSuggestion, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (suggestion.tab == CatalogTab.PACKS) AWIcons.Layers else AWIcons.Extension,
            null,
            tint = AWColors.TextMuted,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(suggestion.hit.title, style = MaterialTheme.typography.bodyMedium, color = AWColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (suggestion.tab == CatalogTab.PACKS) "Сборка" else "Мод") +
                    suggestion.hit.author.takeIf { it.isNotBlank() }?.let { " · от $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Выбрать", style = MaterialTheme.typography.labelLarge, color = AWColors.Accent)
    }
}

@Composable
internal fun BuildCreationDialog(
    onDismiss: () -> Unit,
    onCustom: () -> Unit,
    onSearch: (String, CatalogTab?) -> Unit,
    onMrpack: () -> Unit,
    onImportDirectory: () -> Unit,
    source: CatalogSource = CatalogSource.MODRINTH,
    onSource: (CatalogSource) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<CreationSuggestion>>(emptyList()) }
    var loadingQuery by remember { mutableStateOf<String?>(null) }
    var failedQuery by remember { mutableStateOf<String?>(null) }
    var dismissedQuery by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
    LaunchedEffect(query, source) {
        val term = query.trim()
        suggestions = emptyList()
        failedQuery = null
        if (term.length < 2) {
            loadingQuery = null
            return@LaunchedEffect
        }
        dismissedQuery = null
        loadingQuery = term
        delay(250)
        try {
            suggestions = findCreationSuggestions(term, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedQuery = term
        } finally {
            if (loadingQuery == term) loadingQuery = null
        }
    }
    val term = query.trim()
    val suggestionsOpen = term.length >= 2 && dismissedQuery != term
    AWDialog(title = "Создание сборки", onDismiss = onDismiss, width = 560.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Уже знаете, во что сыграть?", style = MaterialTheme.typography.titleSmall, color = AWColors.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogSource.entries.forEach { provider -> ChoiceChip(provider.label, source == provider, onClick = { onSource(provider) }) }
            }
            Box {
                AWTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Поиск сборок на ${source.label}…",
                    leadingIcon = Icons.Default.Search,
                    clearable = true,
                    onSubmit = { onSearch(query.trim(), CatalogTab.PACKS) },
                    focusRequester = searchFocus,
                    modifier = Modifier.fillMaxWidth(),
                )
                AWDropdownMenu(
                    expanded = suggestionsOpen,
                    onDismissRequest = { dismissedQuery = term },
                    modifier = Modifier.width(500.dp),
                    focusable = false,
                ) {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        when {
                            loadingQuery == term -> Text("Ищу на ${source.label}…", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, modifier = Modifier.padding(14.dp))
                            failedQuery == term -> Text("Не удалось связаться с ${source.label}", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, modifier = Modifier.padding(14.dp))
                            suggestions.isEmpty() -> Text("Ничего не нашлось", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, modifier = Modifier.padding(14.dp))
                            else -> suggestions.forEach { suggestion ->
                                CreationSuggestionRow(suggestion) {
                                    dismissedQuery = term
                                    onSearch(suggestion.hit.title, suggestion.tab)
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(AWColors.Outline))
                Text("ИЛИ", style = MaterialTheme.typography.labelSmall, color = AWColors.TextMuted)
                Box(Modifier.weight(1f).height(1.dp).background(AWColors.Outline))
            }
            Text("Выберите тип сборки", style = MaterialTheme.typography.titleSmall, color = AWColors.Text)
            CreationOptionRow(
                icon = AWIcons.Layers,
                title = "Собственная настройка",
                description = "Начать с нуля, выбрав загрузчик и версию игры.",
                onClick = onCustom,
            )
            CreationOptionRow(
                icon = AWIcons.Extension,
                title = "Выбрать готовую сборку",
                description = "Найдите сборку с нужными модами и настройками в каталоге",
                onClick = { onSearch(query.trim(), CatalogTab.PACKS) },
            )
            CreationOptionRow(
                icon = AWIcons.Download,
                title = "Загрузить сборку",
                description = "Установить сборку Modrinth (.mrpack) или CurseForge (.zip)",
                onClick = onMrpack,
            )
            CreationOptionRow(
                icon = AWIcons.OpenInNew,
                title = "Импортировать сборку",
                description = "Импортировать папку профиля из Prism, CurseForge или другого лаунчера.",
                onClick = onImportDirectory,
            )
        }
    }
}

@Composable
private fun CreationOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(AWColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(15.dp))
                .border(BorderStroke(1.dp, AWColors.Outline), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = AWColors.TextSoft, modifier = Modifier.size(25.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AWColors.Text)
            Text(description, style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
        }
    }
}

private fun chooseMrpack(): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = I18n.text("Выберите сборку Minecraft")
        approveButtonText = I18n.text("Выбрать")
        locale = java.util.Locale.forLanguageTag(Settings.current.language.tag)
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        addChoosableFileFilter(FileNameExtensionFilter(I18n.text("Сборка Modrinth (*.mrpack)"), "mrpack"))
        addChoosableFileFilter(FileNameExtensionFilter(I18n.text("Сборка CurseForge (*.zip)"), "zip"))
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LocalBuildCard(
    state: LauncherState,
    build: LocalBuild,
    enabled: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val entry = state.entryFor(build)
    val installed = state.isEntryInstalled(entry)

    Panel(Modifier.fillMaxWidth().clickable { state.openInstance(entry) }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labels = maxWidth >= 940.dp
            val info: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AWColors.AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(AWIcons.Layers, null, tint = AWColors.Accent, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(build.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AWColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${build.versionId} · ${build.loader.label}", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, translate = false)
                            Text(if (installed) "Установлена" else "Скачается при запуске", style = MaterialTheme.typography.labelSmall,
                                color = if (installed) AWColors.Success else AWColors.TextMuted, maxLines = 1)
                        }
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                ProfileAction("Папка", AWIcons.Folder, labels, enabled = enabled, onClick = { state.openFolder(state.gameDirOf(entry)) })
                ProfileAction("Переименовать", Icons.Default.Edit, labels, enabled = enabled, onClick = onRename)
                AWButton("Играть", onClick = { state.openBuild(build); state.play() }, style = ButtonStyle.PRIMARY,
                    enabled = enabled && !state.busy, icon = Icons.Default.PlayArrow)
                ProfileAction("Удалить", Icons.Default.Delete, labels, enabled = enabled, danger = true, onClick = onRemove)
            }
            if (maxWidth < 520.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                info(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { actions() }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                info(Modifier.weight(1f))
                actions()
            }
        }
    }
}

@Composable
private fun ProfileAction(text: String, icon: ImageVector, label: Boolean, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    if (label) AWButton(text, onClick = onClick, enabled = enabled, icon = icon, style = if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY)
    else WithTooltip(text) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (danger) AWColors.Danger.copy(alpha = 0.14f) else AWColors.SurfaceHigh)) {
            Icon(icon, text, tint = if (!enabled) AWColors.TextMuted else if (danger) AWColors.Danger else AWColors.Text, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ImportedPackCard(state: LauncherState, pack: Modpack) {
    val entry = state.entryFor(pack)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium))
            .background(AWColors.SurfaceHigh).clickable { state.openInstance(entry) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AWIcons.Layers, null, tint = AWColors.Accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(pack.title, style = MaterialTheme.typography.titleMedium, color = AWColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Minecraft ${pack.gameVersion} · ${pack.loader.label}", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
        }
        AWButton("Папка", onClick = { state.openFolder(state.gameDirOf(entry)) }, icon = AWIcons.Folder)
        AWButton("Играть", onClick = { state.selectEntry(entry); state.play() }, style = ButtonStyle.PRIMARY, enabled = !state.busy, icon = Icons.Default.PlayArrow)
    }
}

@Composable
internal fun ImportDirectoryDialog(state: LauncherState, source: Path, onDismiss: () -> Unit, onImport: (List<ImportProfile>) -> Unit) {
    var profiles by remember(source) { mutableStateOf<List<ImportProfile>?>(null) }
    var selected by remember(source) { mutableStateOf<Set<Path>>(emptySet()) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var retry by remember(source) { mutableStateOf(0) }
    val settings by Settings.state.collectAsState()
    val versions = remember(state.versions, settings.showSnapshots, settings.showOldVersions) { state.selectableVersions() }
    LaunchedEffect(source, retry) {
        error = null
        try {
            val found = withContext(Dispatchers.IO) { ImportProfiles.discover(source) }
            profiles = found
            selected = found.map { it.gameDir }.toSet()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Не удалось прочитать профили" }
    }
    val chosen = profiles.orEmpty().filter { it.gameDir in selected }
    AWDialog("Перенос сборок", onDismiss = onDismiss, width = 680.dp, actions = {
        AWButton("Отмена", onClick = onDismiss)
        AWButton("Импортировать", style = ButtonStyle.PRIMARY,
            enabled = chosen.isNotEmpty() && chosen.all { !it.versionId.isNullOrBlank() } && !state.busy && !state.buildsBusy,
            onClick = { onImport(chosen) })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Миры, моды, настройки, скриншоты и доступная статистика будут перенесены автоматически", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            when {
                error != null -> {
                    Text(error.orEmpty(), color = AWColors.Danger)
                    AWButton("Повторить", onClick = { retry++ })
                }
                profiles == null -> Text("Смотрю, какие сборки есть в папке…", color = AWColors.TextMuted)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 370.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(profiles.orEmpty(), key = { it.gameDir.toString() }) { profile ->
                        var versionMenu by remember(profile.gameDir) { mutableStateOf(false) }
                        var loaderMenu by remember(profile.gameDir) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AWColors.SurfaceHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AWSwitch(profile.gameDir in selected) { checked -> selected = if (checked) selected + profile.gameDir else selected - profile.gameDir }
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.Bold, color = AWColors.Text, translate = false)
                                    Text(profile.launcher, style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, translate = false)
                                }
                                if (profile.playTimeMillis > 0) Text(formatPlayTime(profile.playTimeMillis), color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box {
                                    AWButton(profile.versionId ?: "Выбрать версию", onClick = { versionMenu = true })
                                    AWDropdownMenu(versionMenu, onDismissRequest = { versionMenu = false }) {
                                        LazyColumn(Modifier.width(300.dp).height(260.dp)) {
                                            items(versions, key = { it.id }) { version -> AWMenuItem(version.id, onClick = {
                                                profiles = profiles.orEmpty().map { if (it.gameDir == profile.gameDir) it.copy(versionId = version.id) else it }
                                                versionMenu = false
                                            }) }
                                        }
                                    }
                                }
                                Box {
                                    AWButton(profile.loader.label, onClick = { loaderMenu = true })
                                    AWDropdownMenu(loaderMenu, onDismissRequest = { loaderMenu = false }) {
                                        LoaderKind.entries.forEach { loader -> AWMenuItem(loader.label, onClick = {
                                            profiles = profiles.orEmpty().map { if (it.gameDir == profile.gameDir) it.copy(loader = loader, loaderVersion = null) else it }
                                            loaderMenu = false
                                        }) }
                                    }
                                }
                                profile.loaderVersion?.let { Text(it, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp), translate = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class BuildEditor(
    val build: LocalBuild? = null,
    val archive: Path? = null,
    val sourceDirectory: Path? = null,
    val key: String = UUID.randomUUID().toString(),
)

@Composable
private fun BuildEditorDialog(
    state: LauncherState,
    data: BuildEditor,
    onDismiss: () -> Unit,
    onSave: (String, String, LoaderKind) -> Unit,
) {
    val settings by Settings.state.collectAsState()
    val selectableVersions = remember(state.versions, settings.showSnapshots, settings.showOldVersions) { state.selectableVersions() }
    val current = state.currentEntry()
    val defaultName = data.build?.name
        ?: data.archive?.fileName?.toString()?.substringBeforeLast('.')
        ?: data.sourceDirectory?.fileName?.toString()
        ?: ""
    var name by remember(data.key) { mutableStateOf(defaultName) }
    var versionId by remember(data.key) {
        mutableStateOf(data.build?.versionId ?: current?.id?.takeIf { id -> selectableVersions.any { it.id == id } } ?: selectableVersions.firstOrNull()?.id.orEmpty())
    }
    var loader by remember(data.key) {
        mutableStateOf(data.build?.loader ?: current?.loader ?: LoaderKind.VANILLA)
    }
    var versionQuery by remember(data.key) { mutableStateOf("") }
    var versionMenu by remember { mutableStateOf(false) }
    var loaderMenu by remember { mutableStateOf(false) }
    val editVersion = data.build == null && data.archive == null
    LaunchedEffect(selectableVersions, editVersion) {
        if (editVersion && data.sourceDirectory == null && selectableVersions.none { it.id == versionId }) {
            versionId = selectableVersions.firstOrNull()?.id.orEmpty()
        }
    }
    val availableLoaders = remember(versionId, state.loaderSupport) { state.loaderSupport.loadersFor(versionId) }
    val selectedLoader = loader.takeIf { it in availableLoaders } ?: LoaderKind.VANILLA
    val title = when {
        data.build != null -> "Переименовать сборку"
        data.archive != null -> "Импорт сборки"
        data.sourceDirectory != null -> "Импорт профиля"
        else -> "Собственная настройка"
    }

    AWDialog(
        title = title,
        onDismiss = onDismiss,
        width = 500.dp,
        actions = {
            AWButton("Отмена", onClick = onDismiss)
            AWButton(
                when {
                    data.build != null -> "Сохранить"
                    data.archive != null || data.sourceDirectory != null -> "Импортировать"
                    else -> "Создать"
                },
                style = ButtonStyle.PRIMARY,
                enabled = name.isNotBlank() && (!editVersion || versionId.isNotBlank()) && !state.buildsBusy && !state.busy,
                onClick = { onSave(name, versionId, selectedLoader) },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                label = { Text("Название сборки") },
                singleLine = true,
                shape = RoundedCornerShape(AWDimens.CornerMedium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AWColors.Accent,
                    unfocusedBorderColor = AWColors.Outline,
                    focusedContainerColor = AWColors.SurfaceHigh,
                    unfocusedContainerColor = AWColors.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (data.archive != null) {
                Text(
                    "Версия Minecraft и загрузчик будут взяты из сборки",
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.TextMuted,
                )
            }
            if (editVersion) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Версия Minecraft", style = MaterialTheme.typography.labelLarge, color = AWColors.TextMuted)
                    Box {
                        BuildChoice("$versionId · ${state.versions.firstOrNull { it.id == versionId }?.kind?.label ?: "Minecraft"}") {
                            versionMenu = true
                        }
                        AWDropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }, modifier = Modifier.width(360.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                AWTextField(
                                    value = versionQuery,
                                    onValueChange = { versionQuery = it; versionMenu = true },
                                    placeholder = "Найти версию",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                val candidates = selectableVersions.filter { version ->
                                    versionQuery.isBlank() || version.id.contains(versionQuery.trim(), ignoreCase = true)
                                }
                                LazyColumn(Modifier.width(336.dp).height(260.dp)) {
                                    items(candidates, key = { it.id }) { version ->
                                        AWMenuItem("${version.id} · ${version.kind.label}", onClick = {
                                            versionId = version.id
                                            loader = state.loaderSupport.loadersFor(version.id).firstOrNull() ?: LoaderKind.VANILLA
                                            versionMenu = false
                                            versionQuery = ""
                                        })
                                    }
                                    if (candidates.isEmpty()) {
                                        item { Text("Нет такой версии в списке Mojang", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, modifier = Modifier.padding(12.dp)) }
                                    }
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Загрузчик", style = MaterialTheme.typography.labelLarge, color = AWColors.TextMuted)
                        Box {
                            BuildChoice(selectedLoader.label) { loaderMenu = true }
                            AWDropdownMenu(expanded = loaderMenu, onDismissRequest = { loaderMenu = false }) {
                                availableLoaders.forEach { kind ->
                                    AWMenuItem(kind.label, onClick = { loader = kind; loaderMenu = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildChoice(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium))
            .background(AWColors.SurfaceHigh).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AWColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.KeyboardArrowDown, null, tint = AWColors.TextMuted)
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
