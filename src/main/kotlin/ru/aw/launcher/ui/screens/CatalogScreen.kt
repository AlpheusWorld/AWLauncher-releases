package ru.aw.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aw.launcher.core.Shell
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.ContentKind
import ru.aw.launcher.mods.IncompatibleModException
import ru.aw.launcher.mods.InstalledItem
import ru.aw.launcher.mods.ModManager
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.mods.ContentCatalog
import ru.aw.launcher.mods.CatalogSource
import ru.aw.launcher.mods.CurseForge
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.packs.Modpacks
import ru.aw.launcher.ui.CatalogSearch
import ru.aw.launcher.ui.ContentModel
import ru.aw.launcher.ui.CatalogTab
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.ui.components.ContentRow
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWDropdownMenu
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.AWMenuItem
import ru.aw.launcher.ui.components.AWSwitch
import ru.aw.launcher.ui.components.ListBox
import ru.aw.launcher.ui.components.ListHint
import ru.aw.launcher.ui.components.Panel
import ru.aw.launcher.ui.components.ReportOpen
import ru.aw.launcher.ui.components.SearchField
import ru.aw.launcher.ui.components.SearchResults
import ru.aw.launcher.ui.components.StatusStrip
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.components.WithTooltip
import ru.aw.launcher.ui.components.formatCount
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens

private val CatalogTab.kind: ContentKind?
    get() = when (this) {
        CatalogTab.MODS -> ContentKind.MOD
        CatalogTab.SHADERS -> ContentKind.SHADER
        CatalogTab.RESOURCE_PACKS -> ContentKind.RESOURCE_PACK
        CatalogTab.PACKS, CatalogTab.INSTALLED -> null
    }

private val CatalogTab.title: String
    get() = when (this) {
        CatalogTab.PACKS -> "Сборки"
        CatalogTab.MODS -> "Моды"
        CatalogTab.SHADERS -> "Шейдеры"
        CatalogTab.RESOURCE_PACKS -> "Ресурспаки"
        CatalogTab.INSTALLED -> "Установленные"
    }

internal val ContentKind.title: String
    get() = when (this) {
        ContentKind.MOD -> "Моды"
        ContentKind.SHADER -> "Шейдеры"
        ContentKind.RESOURCE_PACK -> "Ресурспаки"
    }

private val ContentKind.searchHint: String
    get() = when (this) {
        ContentKind.MOD -> "Найти мод: Sodium, JEI, Xaero's Minimap…"
        ContentKind.SHADER -> "Найти шейдер: Complementary, BSL, Photon…"
        ContentKind.RESOURCE_PACK -> "Найти ресурспак: Fresh Animations, Faithful…"
    }

internal class PacksModel(private val scope: CoroutineScope, source: CatalogSource) {
    val search = CatalogSearch(scope) { query, offset -> ContentCatalog.search(source, "modpack", query, emptyList(), null, offset) }
    var resolving by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    fun install(state: LauncherState, hit: Modrinth.SearchHit, version: Modrinth.Version) {
        if (resolving != null || state.downloads.contains("pack:${hit.projectId}")) return
        resolving = hit.projectId
        error = null
        scope.launch {
            try {
                state.installPack(Modpacks.source(hit.projectId, hit.slug.ifBlank { hit.projectId }, hit.title, hit.iconUrl, version.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                resolving = null
            }
        }
    }
}

@Composable
fun CatalogScreen(state: LauncherState) {
    val target = state.catalogTarget ?: state.currentEntry()
    val tabs = CatalogTab.entries
    val tab = state.catalogTab.takeIf { it in tabs } ?: CatalogTab.PACKS
    val source = state.catalogSource
    val packs = state.packsModel(source)
    val openedProject = state.catalogProject
    DisposableEffect(openedProject) {
        state.catalogPageTitle = openedProject?.title
        state.catalogBackAction = if (openedProject == null) null else ({ state.catalogProject = null })
        onDispose {
            state.catalogPageTitle = null
            state.catalogBackAction = null
        }
    }
    val content = target?.let { state.contentModel(it, source) }
    LaunchedEffect(state.catalogQuery, tab, target?.key) {
        val query = state.catalogQuery
        if (query.isBlank()) return@LaunchedEffect
        when (tab) {
            CatalogTab.PACKS -> packs.search.query = query
            CatalogTab.MODS, CatalogTab.SHADERS, CatalogTab.RESOURCE_PACKS ->
                tab.kind?.let { content?.searches?.get(it) }?.query = query
            CatalogTab.INSTALLED -> Unit
        }
        state.catalogQuery = ""
    }
    LaunchedEffect(content) { content?.ensureScanned() }

    val opened = openedProject
    if (opened != null) {
        val kind = ContentKind.entries.firstOrNull { it.projectType == opened.projectType }
        val isPack = opened.projectType == "modpack"
        val allowed = isPack || (target != null && kind != null && when (kind) {
            ContentKind.MOD -> target.loader.isModded
            ContentKind.SHADER -> state.supportsShaders(target)
            ContentKind.RESOURCE_PACK -> true
        })
        val installedPack = state.packs.firstOrNull { it.projectId == opened.projectId }
        val installedContent = content?.installed?.values?.flatten()?.filter { it.projectId == opened.projectId }
            ?.let { items -> items.firstOrNull { it.enabled } ?: items.firstOrNull() }
        ProjectScreen(
            hit = opened,
            initialTab = state.catalogProjectTab,
            versionVisible = { state.isVersionVisible(it) },
            busy = packs.resolving == opened.projectId || state.downloads.contains("pack:${opened.projectId}") ||
                content?.working?.let { opened.projectId in it || ContentModel.ALL in it } == true,
            installedVersion = if (isPack) installedPack?.versionId else installedContent?.versionId,
            installAllowed = allowed,
            targetLabel = if (isPack) null else target?.label,
            compatible = { version ->
                if (isPack) version.files.any { ContentCatalog.isPackFile(opened.projectId, it.filename) }
                else version.primaryFile != null && target != null && kind != null && target.id in version.gameVersions &&
                    version.loaders.any { it in ModManager.catalogLoaders(kind, target.loader) }
            },
            onInstall = { version ->
                if (isPack) packs.install(state, opened, version)
                else if (kind != null && content != null && allowed) content.install(kind, opened, version)
            },
            onBack = { state.catalogProject = null },
            progress = state.downloads.active?.takeIf {
                it.key == if (isPack) "pack:${opened.projectId}" else "content:${target?.key}:${opened.projectId}"
            }?.progress,
            installError = if (isPack) packs.error else content?.error,
            installMessage = if (isPack) null else content?.message,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter)) {
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEach { ChoiceChip(it.title, selected = tab == it, onClick = { state.catalogTab = it }) }
                }
                if (tab != CatalogTab.INSTALLED) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CatalogSource.entries.forEach { provider ->
                            ChoiceChip(provider.label, selected = source == provider,
                                enabled = packs.resolving == null,
                                onClick = { state.catalogSource = provider })
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    tab == CatalogTab.PACKS -> PacksTab(state, packs) { hit, install -> state.catalogProject = hit; state.catalogProjectTab = if (install) 1 else 0 }
                    target == null -> ListBox(Modifier.weight(1f)) {
                        ListHint("Выбери версию или сборку на экране «Играть».", "Открыть «Играть»") {
                            state.screen = ru.aw.launcher.ui.Screen.PLAY
                        }
                    }
                    tab == CatalogTab.MODS && !target.loader.isModded -> ListBox(Modifier.weight(1f)) {
                        ListHint(
                            "Для модов сначала выбери или создай сборку с Fabric, Forge, NeoForge или Quilt.",
                            "Открыть «Играть»",
                        ) { state.screen = ru.aw.launcher.ui.Screen.PLAY }
                    }
                    content != null -> ContentTab(state, target, content, tab) { state.catalogProject = it; state.catalogProjectTab = 0 }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PacksTab(state: LauncherState, model: PacksModel, onProject: (Modrinth.SearchHit, Boolean) -> Unit) {
    LaunchedEffect(model.search.query) {
        if (model.search.query.isNotBlank()) delay(350)
        model.search.run()
    }
    SearchField(
        value = model.search.query,
        onValueChange = { model.search.query = it },
        placeholder = "Найти сборку: Fabulously Optimized, Cobblemon…",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    ListBox(Modifier.weight(1f)) { listState ->
        SearchResults(model.search, listState) { hit -> PackRow(state, model, hit) { install -> onProject(hit, install) } }
    }
    StatusStrip(state.progress.takeIf { state.installingPack != null }, model.error, null)
}

@Composable
private fun PackRow(state: LauncherState, model: PacksModel, hit: Modrinth.SearchHit, onProject: (Boolean) -> Unit) {
    val pack = state.packs.firstOrNull { it.projectId == hit.projectId }
    val busy = model.resolving == hit.projectId || state.downloads.contains("pack:${hit.projectId}")
    ContentRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = listOfNotNull(
            hit.author.takeIf { it.isNotBlank() }?.let { "от $it" },
            "${formatCount(hit.downloads)} скачиваний",
            listOfNotNull(loaderOf(hit)?.label, hit.versions.lastOrNull()).joinToString(" ").ifBlank { null },
        ).joinToString(" · "),
        details = hit.description,
        onOpen = { Shell.browse(ContentCatalog.page(hit.projectId, hit.projectType, hit.slug)) },
        onClick = { onProject(false) },
    ) {
        when {
            busy -> Text(if (state.installingPack == hit.projectId) "Ставлю…" else "В очереди", style = MaterialTheme.typography.labelLarge, color = AWColors.TextMuted)
            pack != null && pack.id in state.packUpdates ->
                AWButton("Обновить", icon = Icons.Default.Refresh, enabled = !state.downloads.contains("pack:${hit.projectId}"), onClick = { state.updatePack(pack) })
            pack != null -> AWButton("Играть", icon = Icons.Default.PlayArrow, style = ButtonStyle.PRIMARY, enabled = !state.busy, onClick = {
                state.selectEntry(state.entryFor(pack))
                state.play()
            })
            else -> AWButton("Установить", icon = AWIcons.Download, enabled = model.resolving == null, onClick = {
                onProject(true)
            })
        }
    }
}

private fun loaderOf(hit: Modrinth.SearchHit): LoaderKind? =
    LoaderKind.entries.firstOrNull { it.isModded && it.name.lowercase() in hit.categories }

@Composable
private fun ColumnScope.ContentTab(state: LauncherState, target: VersionEntry, model: ContentModel, tab: CatalogTab, onProject: (Modrinth.SearchHit) -> Unit) {
    val kind = tab.kind
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TargetPicker(state, target)
        Spacer(Modifier.weight(1f))
        val updates = model.updates
        if (tab == CatalogTab.INSTALLED && updates.isNotEmpty()) {
            AWButton("Обновить все · ${updates.size}", icon = Icons.Default.Refresh, enabled = model.working.isEmpty(), onClick = model::updateAll)
        }
        WithTooltip("Открыть папку") {
            IconButton(onClick = { state.openFolder(model.folder(kind)) }, modifier = Modifier.size(36.dp)) {
                Icon(AWIcons.Folder, "Открыть папку", tint = AWColors.TextMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    when {
        kind == null -> ListBox(Modifier.weight(1f)) { InstalledList(state, target, model, it, onProject) }
        kind == ContentKind.SHADER && !state.supportsShaders(target) -> ListBox(Modifier.weight(1f)) {
            ListHint(
                if (target.loader == LoaderKind.FORGE) "Шейдеры работают через Iris, а для Forge его нет. Выбери версию с Fabric или NeoForge."
                else "Шейдерам нужен Fabric, а он пока не поддерживает ${target.id}."
            )
        }
        else -> {
            val search = model.searches.getValue(kind)
            LaunchedEffect(search, search.query) {
                if (search.query.isNotBlank()) delay(350)
                search.run()
            }
            SearchField(
                value = search.query,
                onValueChange = { search.query = it },
                placeholder = kind.searchHint,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            ListBox(Modifier.weight(1f)) { listState ->
                SearchResults(search, listState) { hit -> ContentHitRow(model, kind, hit) { onProject(hit.copy(projectType = kind.projectType)) } }
            }
        }
    }
    StatusStrip(model.progress, model.error, model.message)
}

@Composable
private fun TargetPicker(state: LauncherState, target: VersionEntry) {
    var open by remember { mutableStateOf(false) }
    ReportOpen(open, state::trackMenu)
    Box {
        AWButton("Для ${target.label}", icon = Icons.Default.KeyboardArrowDown, onClick = { open = true })
        AWDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.catalogTargets().forEach { entry ->
                AWMenuItem(entry.label, onClick = {
                    open = false
                    state.catalogTarget = entry
                })
            }
        }
    }
}

@Composable
private fun ContentHitRow(model: ContentModel, kind: ContentKind, hit: Modrinth.SearchHit, onProject: () -> Unit) {
    val installed = hit.projectId in model.installedProjects
    val busy = hit.projectId in model.working
    ContentRow(
        icon = hit.iconUrl,
        title = hit.title,
        byline = hit.author.takeIf { it.isNotBlank() }?.let { "от $it · ${formatCount(hit.downloads)} скачиваний" }
            ?: "${formatCount(hit.downloads)} скачиваний",
        details = hit.description,
        onOpen = { Shell.browse(ContentCatalog.page(hit.projectId, kind.projectType, hit.slug)) },
        onClick = onProject,
    ) {
        when {
            installed -> Tag("Установлен", AWColors.Success)
            busy -> Text(if (model.waiting(hit.projectId)) "В очереди" else "Ставлю…", style = MaterialTheme.typography.labelLarge, color = AWColors.TextMuted)
            else -> AWButton(
                "Установить",
                icon = AWIcons.Download,
                enabled = ContentModel.ALL !in model.working,
                onClick = { model.install(kind, hit) },
            )
        }
    }
}

@Composable
private fun InstalledList(state: LauncherState, target: VersionEntry, model: ContentModel, listState: LazyListState, onProject: (Modrinth.SearchHit) -> Unit) {
    val kinds = listOfNotNull(ContentKind.MOD.takeIf { target.loader.isModded }, ContentKind.SHADER, ContentKind.RESOURCE_PACK)
    val sections = kinds.map { it to model.installed[it].orEmpty() }.filter { it.second.isNotEmpty() }
    when {
        !model.scanned -> ListHint("Смотрю, что стоит в ${target.label}…")
        sections.isEmpty() -> ListHint("Пока ничего не установлено. Найди моды, шейдеры и ресурспаки в каталоге.", "Открыть каталог") {
            state.catalogTab = if (target.loader.isModded) CatalogTab.MODS else CatalogTab.SHADERS
        }
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            sections.forEach { (kind, items) ->
                item(key = "header-$kind") {
                    Text(
                        "${kind.title.uppercase()} · ${items.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AWColors.TextMuted,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 2.dp),
                    )
                }
                items(items, key = { it.rowKey }) { item -> InstalledRow(model, item, onProject) }
            }
        }
    }
}

@Composable
private fun InstalledRow(model: ContentModel, item: InstalledItem, onProject: (Modrinth.SearchHit) -> Unit) {
    val busy = item.rowKey in model.working || ContentModel.ALL in model.working
    val update = item.update
    ContentRow(
        icon = item.iconUrl,
        title = item.title,
        byline = listOfNotNull(item.versionNumber.takeIf { it.isNotBlank() }, item.fileName).joinToString(" · "),
        details = update?.let { "Доступно обновление: ${it.versionNumber}" },
        detailsAccent = update != null,
        dimmed = !item.enabled,
        badge = null,
        onClick = item.projectId?.let { id -> {
            onProject(Modrinth.SearchHit(projectId = id, title = item.title, iconUrl = item.iconUrl, projectType = item.kind.projectType))
        } },
    ) {
        if (update != null) {
            AWButton("Обновить", icon = Icons.Default.Refresh, enabled = !busy, onClick = { model.update(item) })
            Spacer(Modifier.width(8.dp))
        }
        WithTooltip(if (item.enabled) "Выключить" else "Включить") {
            AWSwitch(item.enabled, enabled = !busy) { model.toggle(item, it) }
        }
        Spacer(Modifier.width(4.dp))
        WithTooltip("Удалить") {
            IconButton(onClick = { model.remove(item) }, enabled = !busy, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить",
                    tint = if (!busy) AWColors.Danger else AWColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
