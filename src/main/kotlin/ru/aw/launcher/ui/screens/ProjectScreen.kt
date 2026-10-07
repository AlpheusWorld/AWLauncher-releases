package ru.aw.launcher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import ru.aw.launcher.core.Shell
import ru.aw.launcher.core.Settings
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.mods.ContentCatalog
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.ui.ModIcon
import ru.aw.launcher.ui.ModIcons
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens

internal data class ProjectPageData(
    val project: Modrinth.Project,
    val versions: List<Modrinth.Version>,
    val description: List<Element>,
)

@Composable
internal fun ProjectScreen(
    hit: Modrinth.SearchHit,
    busy: Boolean,
    installedVersion: String?,
    installAllowed: Boolean,
    targetLabel: String?,
    compatible: (Modrinth.Version) -> Boolean,
    onInstall: (Modrinth.Version) -> Unit,
    onBack: () -> Unit,
    progress: DownloadProgress? = null,
    installError: String? = null,
    installMessage: String? = null,
    preview: ProjectPageData? = null,
    initialTab: Int = 0,
    versionVisible: (String) -> Boolean = { true },
) {
    val settings by Settings.state.collectAsState()
    var data by remember(hit.projectId) { mutableStateOf(preview) }
    var error by remember(hit.projectId) { mutableStateOf<String?>(null) }
    var retry by remember(hit.projectId) { mutableStateOf(0) }
    var tab by remember(hit.projectId) { mutableStateOf(initialTab) }
    var gameVersion by remember(hit.projectId) { mutableStateOf("") }
    var loader by remember(hit.projectId) { mutableStateOf("") }
    var imageIndex by remember(hit.projectId) { mutableStateOf<Int?>(null) }
    var release by remember(hit.projectId) { mutableStateOf<Modrinth.Version?>(null) }

    LaunchedEffect(hit.projectId, retry) {
        if (preview != null) return@LaunchedEffect
        error = null
        try {
            data = withContext(Dispatchers.IO) {
                coroutineScope {
                    val project = async { ContentCatalog.project(hit.projectId) }
                    val versions = async { ContentCatalog.versions(hit.projectId) }
                    val info = project.await()
                    ProjectPageData(info, versions.await(), ProjectDescription.parse(info.body.ifBlank { info.description },
                        ContentCatalog.page(hit.projectId, info.projectType, info.slug)))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не получилось"
        }
    }

    val page = data
    val project = page?.project
    val gallery = project?.gallery.orEmpty().filter { it.title != "__mc_server_banner__" }.sortedBy { it.ordering }
    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AWButton("Назад в каталог", onClick = onBack)
            Spacer(Modifier.weight(1f))
            AWButton("Открыть на ${ContentCatalog.source(hit.projectId).label}", icon = AWIcons.OpenInNew, onClick = {
                Shell.browse(ContentCatalog.page(hit.projectId, project?.projectType ?: hit.projectType, project?.slug ?: hit.slug))
            })
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ModIcon(project?.iconUrl ?: hit.iconUrl, 76.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(project?.title ?: hit.title, style = MaterialTheme.typography.headlineMedium, color = AWColors.Text, translate = false)
                    Text(project?.description ?: hit.description, style = MaterialTheme.typography.bodyMedium, color = AWColors.TextSoft, maxLines = 3, translate = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${formatCount(project?.downloads ?: hit.downloads)} скачиваний", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                        if (ContentCatalog.source(hit.projectId) == ru.aw.launcher.mods.CatalogSource.MODRINTH)
                            Text("${formatCount(project?.followers ?: 0)} подписчиков", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                        if (hit.author.isNotBlank()) Text("от ${hit.author}", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                    }
                }
                AWButton(
                    if (busy) "Ставлю…" else if (installedVersion != null) "Выбрать версию" else "Установить",
                    style = ButtonStyle.PRIMARY, icon = AWIcons.Download,
                    enabled = !busy && page != null && installAllowed,
                    onClick = { tab = 1 },
                )
            }
        }
        if (!installAllowed) Text(
            "Для установки сначала выбери совместимую сборку в каталоге",
            style = MaterialTheme.typography.bodySmall, color = AWColors.Warning,
        ) else if (targetLabel != null) Text("Для $targetLabel", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            Panel(Modifier.width(220.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionTitle("Совместимость")
                    Text("Версии Minecraft", fontWeight = FontWeight.Bold, color = AWColors.Text)
                    Text(project?.gameVersions.orEmpty().takeLast(16).joinToString(", ").ifBlank { "—" }, color = AWColors.TextSoft)
                    Text("Загрузчики", fontWeight = FontWeight.Bold, color = AWColors.Text)
                    Text(project?.loaders.orEmpty().joinToString(", ").ifBlank { "—" }, color = AWColors.TextSoft)
                    SectionTitle("Ссылки")
                    listOf("Исходный код" to project?.sourceUrl, "Сообщить об ошибке" to project?.issuesUrl, "Вики" to project?.wikiUrl, "Discord" to project?.discordUrl)
                        .filter { it.second?.startsWith("https://") == true || it.second?.startsWith("http://") == true }
                        .forEach { (label, url) -> Text(label, color = AWColors.Accent, modifier = Modifier.clickable { url?.let(Shell::browse) }) }
                    SectionTitle("Информация")
                    Text("Лицензия", fontWeight = FontWeight.Bold, color = AWColors.Text)
                    Text(project?.license?.id?.ifBlank { "—" } ?: "—", color = AWColors.TextSoft, translate = false)
                    Text(project?.categories.orEmpty().joinToString(" · "), color = AWColors.TextMuted, translate = false)
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Описание", "Версии", "Галерея").forEachIndexed { index, title ->
                            ChoiceChip(title, tab == index, onClick = { tab = index })
                        }
                    }
                    when {
                        error != null -> ListHint(error ?: "Не удалось загрузить страницу проекта", "Повторить") { retry++ }
                        page == null -> ListHint("Загружаю проект…")
                        tab == 0 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                            if (page.description.isEmpty()) item { Text("Автор пока не добавил описание", color = AWColors.TextMuted) }
                            items(page.description) { element -> DescriptionBlock(element) { url, alt -> ProjectImage(url, alt) } }
                        }
                        tab == 1 -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProjectFilter("Все версии", gameVersion, page.versions.flatMap { it.gameVersions }.distinct().filter(versionVisible), onValue = { gameVersion = it })
                                ProjectFilter("Все загрузчики", loader, page.versions.flatMap { it.loaders }.distinct(), onValue = { loader = it })
                            }
                            val filtered = remember(page, gameVersion, loader, settings.showSnapshots, settings.showOldVersions, versionVisible) {
                                page.versions.filter { version ->
                                    (version.gameVersions.isEmpty() || version.gameVersions.any(versionVisible)) &&
                                        (settings.showOldVersions || version.versionType !in setOf("alpha", "beta")) &&
                                        (gameVersion.isBlank() || gameVersion in version.gameVersions) && (loader.isBlank() || loader in version.loaders)
                                }
                            }
                            if (filtered.isEmpty()) ListHint("Нет версий с такими фильтрами")
                            else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(filtered, key = { it.id }) { version ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AWColors.SurfaceHigh)
                                            .clickable { release = version }.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(version.name.ifBlank { version.versionNumber }, color = AWColors.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                                            val channel = when (version.versionType) {
                                                "release" -> "Релиз"
                                                "beta" -> "Бета"
                                                "alpha" -> "Альфа"
                                                else -> version.versionType
                                            }
                                            Text(listOf(channel, version.gameVersions.joinToString(", "), version.loaders.joinToString(", "), formatReleaseDate(version.datePublished)).joinToString(" · "), color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        AWButton(
                                            if (version.id == installedVersion) "Установлен" else if (version.primaryFile == null) "Загрузка недоступна" else if (!compatible(version)) "Несовместима" else "Установить",
                                            enabled = installAllowed && !busy && compatible(version) && version.id != installedVersion,
                                            onClick = { onInstall(version) }, icon = AWIcons.Download,
                                        )
                                    }
                                }
                            }
                        }
                        else -> if (gallery.isEmpty()) ListHint("Автор пока не добавил изображения") else {
                            LazyVerticalGrid(GridCells.Adaptive(230.dp), modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(gallery, key = { it.url }) { entry ->
                                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(AWColors.SurfaceHigh).clickable { imageIndex = gallery.indexOf(entry) }) {
                                        ProjectImage(entry.url, entry.title, Modifier.fillMaxWidth().height(150.dp), ContentScale.Crop)
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            if (!entry.title.isNullOrBlank()) Text(entry.title, color = AWColors.Text, fontWeight = FontWeight.Bold, translate = false)
                                            if (!entry.description.isNullOrBlank()) Text(entry.description, color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall, translate = false)
                                            Text(formatReleaseDate(entry.created), style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        StatusStrip(progress, installError, installMessage)
    }
    imageIndex?.let { index ->
        val selected = gallery.getOrNull(index) ?: return@let
        AWDialog(selected.title ?: "Галерея", onDismiss = { imageIndex = null }, width = 900.dp, actions = {
            AWButton("Предыдущее", enabled = index > 0, onClick = { imageIndex = index - 1 })
            AWButton("Следующее", enabled = index < gallery.lastIndex, onClick = { imageIndex = index + 1 })
        }) {
            ProjectImage(selected.url, selected.title, Modifier.fillMaxWidth().heightIn(max = 450.dp))
            selected.description?.let { Text(it, color = AWColors.TextSoft, translate = false) }
        }
    }
    release?.let { version ->
        var changesError by remember(version.id) { mutableStateOf<String?>(null) }
        var changesRetry by remember(version.id) { mutableStateOf(0) }
        val blocks by produceState<List<Element>?>(null, version.id, changesRetry) {
            changesError = null
            try {
                value = withContext(Dispatchers.IO) { ProjectDescription.parse(ContentCatalog.changelog(version),
                    ContentCatalog.page(hit.projectId, hit.projectType, hit.slug)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { changesError = e.message ?: "Не удалось загрузить список изменений" }
        }
        AWDialog(version.name.ifBlank { version.versionNumber }, onDismiss = { release = null }, width = 760.dp, actions = {
            AWButton("Установить", enabled = installAllowed && !busy && compatible(version) && version.id != installedVersion, onClick = { onInstall(version); release = null })
        }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val ready = blocks
                if (changesError != null) item { ListHint(changesError.orEmpty(), "Повторить") { changesRetry++ } }
                else if (ready == null) item { Text("Загружаю…", color = AWColors.TextMuted) }
                else if (ready.isEmpty()) item { Text("Для этой версии нет списка изменений", color = AWColors.TextMuted) }
                else items(ready) { DescriptionBlock(it) { url, alt -> ProjectImage(url, alt) } }
            }
        }
    }
}

@Composable
private fun ProjectFilter(label: String, value: String, values: List<String>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AWButton(value.ifBlank { label }, onClick = { open = true })
        AWDropdownMenu(open, onDismissRequest = { open = false }) {
            AWMenuItem(label, onClick = { onValue(""); open = false })
            values.forEach { entry -> AWMenuItem(entry, onClick = { onValue(entry); open = false }) }
        }
    }
}

@Composable
internal fun ProjectImage(url: String, alt: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit) {
    var retry by remember(url) { mutableStateOf(0) }
    var loading by remember(url) { mutableStateOf(true) }
    val bitmap by produceState<ImageBitmap?>(ModIcons.cached(url, 1280), url, retry) {
        loading = true
        value = ModIcons.load(url, 1280)
        loading = false
    }
    val image = bitmap
    if (image != null) Image(image, alt, modifier.widthIn(max = image.width.dp).aspectRatio(image.width.toFloat() / image.height), contentScale = scale)
    else Box(modifier.then(Modifier.fillMaxWidth().heightIn(min = 80.dp)).background(AWColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (loading) Text("Загружаю изображение…", color = AWColors.TextMuted)
        else AWButton("Повторить загрузку изображения", onClick = { retry++ })
    }
}
