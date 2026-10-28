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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import ru.aw.launcher.ui.ModIcon
import ru.aw.launcher.ui.VersionEntry
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.ui.components.ContentRow
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWDialog
import ru.aw.launcher.ui.components.AWDropdownMenu
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.AWMenuItem
import ru.aw.launcher.ui.components.AWSwitch
import ru.aw.launcher.ui.components.ListBox
import ru.aw.launcher.ui.components.ListHint
import ru.aw.launcher.ui.components.Panel
import ru.aw.launcher.ui.components.PageTab
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
        CatalogTab.DATA_PACKS -> ContentKind.DATA_PACK
        CatalogTab.PACKS, CatalogTab.INSTALLED -> null
    }

private val CatalogTab.title: String
    get() = when (this) {
        CatalogTab.PACKS -> "Модпаки"
        CatalogTab.MODS -> "Моды"
        CatalogTab.SHADERS -> "Шейдеры"
        CatalogTab.RESOURCE_PACKS -> "Ресурспаки"
        CatalogTab.DATA_PACKS -> "Датапаки"
        CatalogTab.INSTALLED -> "Установленные"
    }

internal val ContentKind.title: String
    get() = when (this) {
        ContentKind.MOD -> "Моды"
        ContentKind.SHADER -> "Шейдеры"
        ContentKind.RESOURCE_PACK -> "Ресурспаки"
        ContentKind.DATA_PACK -> "Датапаки"
    }

private val ContentKind.searchHint: String
    get() = when (this) {
        ContentKind.MOD -> "Найти мод: Sodium, JEI, Xaero's Minimap…"
        ContentKind.SHADER -> "Найти шейдер: Complementary, BSL, Photon…"
        ContentKind.RESOURCE_PACK -> "Найти ресурспак: Fresh Animations, Faithful…"
        ContentKind.DATA_PACK -> "Найти датапак: Terralith, Incendium…"
    }

internal class PacksModel(private val scope: CoroutineScope, source: CatalogSource) {
    val search = CatalogSearch(scope) { query, offset -> ContentCatalog.search(source, "modpack", query, emptyList(), null, offset) }
        .apply { sortedFetch = { query, offset, sort -> ContentCatalog.search(source, "modpack", query, emptyList(), null, offset, sort) } }
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
            CatalogTab.MODS, CatalogTab.SHADERS, CatalogTab.RESOURCE_PACKS, CatalogTab.DATA_PACKS ->
                tab.kind?.let { content?.searches?.get(it) }?.query = query
            CatalogTab.INSTALLED -> Unit
        }
        state.catalogQuery = ""
    }
    LaunchedEffect(content) { content?.ensureScanned() }
    if (content != null && target != null) DataPackWorldDialog(content, state.gameDirOf(target))

    val opened = openedProject
    if (opened != null) {
        val kind = ContentKind.entries.firstOrNull { it.projectType == opened.projectType }
        val isPack = opened.projectType == "modpack"
        val allowed = isPack || (target != null && kind != null && when (kind) {
            ContentKind.MOD -> target.loader.isModded
            ContentKind.SHADER -> state.supportsShaders(target)
            ContentKind.RESOURCE_PACK, ContentKind.DATA_PACK -> true
        })
        val installedPack = state.packs.firstOrNull { it.projectId == opened.projectId }
        val installedContent = content?.installed?.values?.flatten()?.filter { it.projectId == opened.projectId && kind != ContentKind.DATA_PACK }
            ?.let { items -> items.firstOrNull { it.enabled } ?: items.firstOrNull() }
        ProjectScreen(
            hit = opened,
            initialTab = state.catalogProjectTab,
            versionVisible = { state.isVersionVisible(it) },
            busy = packs.resolving == opened.projectId || state.downloads.contains("pack:${opened.projectId}") ||
                content?.working?.let { opened.projectId in it || ContentModel.ALL in it } == true,
            installedVersion = if (isPack) installedPack?.versionId else installedContent?.versionId,
            installAllowed = allowed && content?.migrating != true,
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
            onDependency = { dependency ->
                if (target != null) state.openProject(target, dependency) else state.catalogProject = dependency
            },
            progress = state.downloads.active?.takeIf {
                it.key == if (isPack) "pack:${opened.projectId}" else "content:${target?.key}:${opened.projectId}"
            }?.progress,
            installError = if (isPack) packs.error else content?.error,
            installMessage = if (isPack) null else content?.message,
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Каталог", style = MaterialTheme.typography.displaySmall, color = AWColors.Text, fontWeight = FontWeight.Bold)
                Text("Моды, шейдеры и сборки — установка в один клик", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (target != null) TargetPicker(state, target)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEach { PageTab(it.title, selected = tab == it, onClick = { state.catalogTab = it }) }
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
    RunCatalogSearch(model.search)
    SearchField(
        value = model.search.query,
        onValueChange = { model.search.query = it },
        placeholder = "Найти сборку: Fabulously Optimized, Cobblemon…",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    CatalogSort(model.search, state.catalogSource)
    Spacer(Modifier.height(10.dp))
    CatalogGrid(model.search, Modifier.weight(1f)) { hit -> PackCard(state, model, hit) { install -> onProject(hit, install) } }
    StatusStrip(state.progress.takeIf { state.installingPack != null }, model.error, null)
}

@Composable
private fun CatalogGrid(search: CatalogSearch, modifier: Modifier = Modifier, card: @Composable (Modrinth.SearchHit) -> Unit) {
    val hits = search.hits
    when {
        hits.isEmpty() && search.searching -> Box(modifier.fillMaxWidth()) { ListHint("Ищу в каталоге…") }
        hits.isEmpty() && search.failed -> Box(modifier.fillMaxWidth()) { ListHint(search.error ?: "Не удалось загрузить каталог", "Повторить") { search.run() } }
        hits.isEmpty() -> Box(modifier.fillMaxWidth()) { ListHint("Ничего не нашлось. Попробуй другое название.") }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp), modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(hits, key = { it.projectId }) { card(it) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        search.searching -> Text("Загружаю…", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                        search.failed -> AWButton("Повторить", onClick = { search.run(more = true) })
                        search.hasMore -> AWButton("Показать ещё", onClick = { search.run(more = true) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogProjectCard(hit: Modrinth.SearchHit, onClick: () -> Unit, action: @Composable () -> Unit) {
    val shape = RoundedCornerShape(AWDimens.CornerMedium)
    Column(Modifier.fillMaxWidth().background(AWColors.Surface, shape).border(1.dp, AWColors.Outline, shape)
        .clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModIcon(hit.iconUrl, 42.dp)
            Column(Modifier.weight(1f)) {
                Text(hit.title, color = AWColors.Text, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                Text("by ${hit.author}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
            }
        }
        Text(hit.description.ifBlank { "Описание отсутствует" }, color = AWColors.TextSoft,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
            translate = hit.description.isBlank())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(24.dp)) {
            hit.categories.take(2).forEach { category -> Tag(category, AWColors.TextMuted) }
        }
        Text("↓ ${formatCount(hit.downloads)}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, translate = false)
        androidx.compose.material3.HorizontalDivider(color = AWColors.Outline)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { action() }
    }
}

@Composable
private fun PackCard(state: LauncherState, model: PacksModel, hit: Modrinth.SearchHit, onProject: (Boolean) -> Unit) {
    val pack = state.packs.firstOrNull { it.projectId == hit.projectId }
    val busy = model.resolving == hit.projectId || state.downloads.contains("pack:${hit.projectId}")
    CatalogProjectCard(hit, onClick = { onProject(false) }) {
        when {
            busy -> Text("В очереди", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
            pack != null && pack.id in state.packUpdates -> AWButton("Обновить", enabled = !busy, onClick = { state.updatePack(pack) })
            pack != null -> AWButton("Играть", style = ButtonStyle.PRIMARY, enabled = !state.busy, onClick = {
                state.selectEntry(state.entryFor(pack)); state.play()
            })
            else -> AWButton("Установить", style = ButtonStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), enabled = model.resolving == null, onClick = { onProject(true) })
        }
    }
}

@Composable
private fun ColumnScope.ContentTab(state: LauncherState, target: VersionEntry, model: ContentModel, tab: CatalogTab, onProject: (Modrinth.SearchHit) -> Unit) {
    val kind = tab.kind
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Для ${target.label}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), translate = false)
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
            RunCatalogSearch(search)
            SearchField(
                value = search.query,
                onValueChange = { search.query = it },
                placeholder = kind.searchHint,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            CatalogSort(search, state.catalogSource)
            Spacer(Modifier.height(10.dp))
            CatalogGrid(search, Modifier.weight(1f)) { hit ->
                val project = { onProject(hit.copy(projectType = kind.projectType)) }
                CatalogProjectCard(hit, project) {
                    when {
                        hit.projectId in model.installedProjects && kind != ContentKind.DATA_PACK -> Tag("Установлен", AWColors.Success)
                        hit.projectId in model.working -> Text("В очереди", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        else -> AWButton("Установить", style = ButtonStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), enabled = ContentModel.ALL !in model.working, onClick = { model.install(kind, hit) })
                    }
                }
            }
        }
    }
    StatusStrip(model.progress, model.error, model.message)
}

@Composable
internal fun RunCatalogSearch(search: CatalogSearch) {
    LaunchedEffect(search, search.query, search.sort) {
        if (search.query.isNotBlank()) delay(350)
        search.run()
    }
}

@Composable
private fun CatalogSort(search: CatalogSearch, source: CatalogSource) {
    val options = listOf("downloads" to "По загрузкам", "relevance" to "По совпадению") +
        (if (source == CatalogSource.MODRINTH) listOf("follows" to "По подписчикам") else emptyList()) + listOf("updated" to "По обновлению")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (value, label) -> ChoiceChip(label, selected = search.sort == value, onClick = { search.sort = value }) }
        Text("Найдено: ${search.total}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    }
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
private fun InstalledList(state: LauncherState, target: VersionEntry, model: ContentModel, listState: LazyListState, onProject: (Modrinth.SearchHit) -> Unit) {
    val kinds = ContentKind.entries.filter { it != ContentKind.MOD || target.loader.isModded }
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
        byline = listOfNotNull(item.worldName, item.versionNumber.takeIf { it.isNotBlank() }, item.fileName).joinToString(" · "),
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

@Composable
internal fun DataPackWorldDialog(model: ContentModel, gameDir: Path) {
    val request = model.dataPackRequest
    val files = model.dataPackFiles
    if (request == null && files == null) return
    var failure by remember(request, files) { mutableStateOf<String?>(null) }
    val worlds by androidx.compose.runtime.produceState<List<Path>?>(null, request, files, gameDir) {
        try { value = withContext(Dispatchers.IO) { ModManager.dataPackWorlds(gameDir) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "Не удалось прочитать миры" }
    }
    AWDialog("Выбери мир для датапака", onDismiss = { model.dataPackRequest = null; model.dataPackFiles = null }, width = 560.dp) {
        if (request != null) Text(request.first.title, modifier = Modifier.fillMaxWidth(), maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = AWColors.Text, translate = false)
        Spacer(Modifier.height(8.dp))
        Text("Датапак устанавливается в выбранный мир. После установки открой мир заново",
            modifier = Modifier.fillMaxWidth(), color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        when {
            failure != null -> Text(failure.orEmpty(), color = AWColors.Danger, translate = false)
            worlds == null -> Text("Читаю миры…", color = AWColors.TextMuted)
            worlds!!.isEmpty() -> Text("Сначала создай мир в этой сборке или перенеси существующий", color = AWColors.TextMuted)
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(worlds!!, key = { it.toString() }) { world ->
                    AWButton(world.fileName.toString(), translate = false, modifier = Modifier.fillMaxWidth(), onClick = {
                        model.dataPackRequest = null
                        model.dataPackFiles = null
                        if (files != null) model.addFiles(files, ContentKind.DATA_PACK, world)
                        else if (request != null) model.install(ContentKind.DATA_PACK, request.first, request.second, world)
                    })
                }
            }
        }
    }
}
