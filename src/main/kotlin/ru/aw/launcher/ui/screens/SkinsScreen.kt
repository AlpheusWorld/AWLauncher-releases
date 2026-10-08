package ru.aw.launcher.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import ru.aw.launcher.auth.*
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.dialogs.SkinDraft
import ru.aw.launcher.ui.dialogs.SkinEditorDialog
import ru.aw.launcher.ui.dialogs.chooseSkinFile
import ru.aw.launcher.ui.theme.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private data class DefaultSkin(val name: String, val hash: String, val model: SkinModel) {
    val url get() = "https://textures.minecraft.net/texture/$hash"
}

// Public texture identifiers of the nine standard Minecraft Java characters.
private val defaultSkins = listOf(
    DefaultSkin("Steve", "31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb", SkinModel.CLASSIC),
    DefaultSkin("Alex", "46acd06e8483b176e8ea39fc12fe105eb3a2a4970f5100057e9d84d4b60bdfa7", SkinModel.SLIM),
    DefaultSkin("Ari", "4c05ab9e07b3505dc3ec11370c3bdce5570ad2fb2b562e9b9dd9cf271f81aa44", SkinModel.CLASSIC),
    DefaultSkin("Efe", "fece7017b1bb13926d1158864b283b8b930271f80a90482f174cca6a17e88236", SkinModel.SLIM),
    DefaultSkin("Kai", "e5cdc3243b2153ab28a159861be643a4fc1e3c17d291cdd3e57a7f370ad676f3", SkinModel.CLASSIC),
    DefaultSkin("Makena", "7cb3ba52ddd5cc82c0b050c3f920f87da36add80165846f479079663805433db", SkinModel.SLIM),
    DefaultSkin("Noor", "6c160fbd16adbc4bff2409e70180d911002aebcfa811eb6ec3d1040761aea6dd", SkinModel.SLIM),
    DefaultSkin("Sunny", "a3bd16079f764cd541e072e888fe43885e711f98658323db0f9a6045da91ee7a", SkinModel.CLASSIC),
    DefaultSkin("Zuri", "f5dddb41dcafef616e959c2817808e0be741c89ffbfed39134a13e75b811863d", SkinModel.CLASSIC),
)

@Composable
internal fun SkinsScreen(state: LauncherState, preview: SkinImage? = null) {
    val accounts by state.accounts.collectAsState()
    val selectedAccount by state.selectedAccount.collectAsState()
    val licensed = accounts.filterNot { it.isOffline }
    var accountId by remember { mutableStateOf(state.skinAccountUuid ?: selectedAccount?.uuid) }
    val account = licensed.firstOrNull { it.uuid == accountId } ?: licensed.firstOrNull()
    val library = remember { SkinLibrary() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<List<SavedSkin>>(emptyList()) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var current by remember(account?.uuid) { mutableStateOf(preview) }
    var currentModel by remember(account?.uuid) { mutableStateOf(account?.skinModel ?: SkinModel.CLASSIC) }
    var currentCape by remember(account?.uuid) { mutableStateOf(account?.capeId) }
    var choice by remember(account?.uuid) { mutableStateOf("current") }
    var model by remember(account?.uuid) { mutableStateOf(currentModel) }
    var capeId by remember(account?.uuid) { mutableStateOf(currentCape) }
    var editor by remember(account?.uuid) { mutableStateOf<SkinDraft?>(null) }
    var deleting by remember { mutableStateOf<SavedSkin?>(null) }
    var accountsOpen by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(true) }
    var defaultsOpen by remember { mutableStateOf(true) }
    val working = saving || state.skinWorkingUuid != null
    LaunchedEffect(state.skinAccountUuid) { state.skinAccountUuid?.let { accountId = it } }

    LaunchedEffect(Unit) {
        try { saved = library.list() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось открыть библиотеку скинов" }
    }
    LaunchedEffect(account?.uuid, retry) {
        loading = true
        try {
            current = preview ?: account?.let { SkinHeads.texture(it) }?.let(MinecraftSkins::fromImage)
            val refreshed = if (preview == null && account != null) AccountManager.skinProfile(account.uuid) else account
            current = preview ?: refreshed?.let { SkinHeads.texture(it) }?.let(MinecraftSkins::fromImage)
                ?: SkinHeads.texture(defaultSkins.first().url)?.let(MinecraftSkins::fromImage)
            currentModel = refreshed?.skinModel ?: SkinModel.CLASSIC
            currentCape = refreshed?.capeId
            if (choice == "current") { model = currentModel; capeId = currentCape }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить скин" }
        finally { loading = false }
    }
    val picked by produceState<SkinImage?>(null, choice, current, saved, libraryRevision) {
        value = null
        try {
            value = if (choice == "current") current else saved.firstOrNull { it.id == choice }?.let { library.image(it) }
                ?: defaultSkins.firstOrNull { it.name == choice }?.let { SkinHeads.texture(it.url)?.let(MinecraftSkins::fromImage) }
            if (value == null && !loading) error = "Не удалось загрузить скин"
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить скин" }
    }
    val cape by produceState<java.awt.image.BufferedImage?>(null, capeId, account?.capes) {
        value = account?.capes?.firstOrNull { it.id == capeId }?.let { SkinHeads.capeTexture(it) }
    }
    val skinChanged = picked != null && (current == null || !picked!!.png.contentEquals(current!!.png) || model != currentModel)
    val pending = skinChanged || capeId != currentCape
    val selectedSaved = saved.firstOrNull { it.id == choice }
    val selectedName = selectedSaved?.name ?: defaultSkins.firstOrNull { it.name == choice }?.name ?: "Текущий скин"
    fun edit() { picked?.let { editor = SkinDraft(it, model, capeId, selectedName, selectedSaved) } }
    fun add(path: java.nio.file.Path) { scope.launch {
        error = null
        try {
            val png = withContext(Dispatchers.IO) { MinecraftSkins.read(path) }
            editor = SkinDraft(png, MinecraftSkins.modelOf(png.image), null, path.fileName.toString().substringBeforeLast('.'))
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось прочитать PNG скина" }
    } }
    fun move(id: String, position: Int) { scope.launch {
        try { saved = library.move(id, position) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить порядок скинов" }
    } }

    FileDropArea(Modifier.fillMaxSize(), !working, "Отпусти PNG, чтобы добавить скин", { files -> files.firstOrNull()?.let(::add) }) {
        Column(Modifier.fillMaxSize().padding(AWDimens.Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Выбор скина", style = MaterialTheme.typography.headlineSmall, color = AWColors.Text, modifier = Modifier.weight(1f))
                Box {
                    AWButton(account?.name ?: "Войти", icon = Icons.Default.Person, enabled = !working, onClick = {
                        if (licensed.isEmpty()) state.screen = Screen.ACCOUNTS else accountsOpen = true
                    })
                    AWDropdownMenu(accountsOpen, onDismissRequest = { accountsOpen = false }) {
                        licensed.forEach { item -> AWMenuItem(item.name, translate = false, onClick = {
                            accountId = item.uuid; state.openSkinEditor(item); error = null; accountsOpen = false
                        }) }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxWidth < 700.dp
                val columns = if (maxWidth < 420.dp) 2 else 3
                val previewContent: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(account?.name ?: "Minecraft", translate = false, color = AWColors.TextSoft, style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            SkinPreview(picked?.image, model, Modifier.fillMaxSize(), loading || picked == null, cape = cape)
                            if (pending) Text("Предпросмотр", color = AWColors.Accent, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.TopCenter).background(AWColors.SurfaceHigh, RoundedCornerShape(6.dp)).padding(8.dp))
                        }
                        if (pending) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AWButton("Сбросить", enabled = !working, onClick = { choice = "current"; model = currentModel; capeId = currentCape })
                            AWButton(if (working) "Применяю…" else "Применить", style = ButtonStyle.PRIMARY,
                                enabled = account != null && picked != null && !working && !loading, onClick = {
                                    val image = picked ?: return@AWButton
                                    state.applySkin(account!!.uuid, image, model, capeId, capeId != currentCape, skinChanged,
                                        onApplied = { updated -> current = image; currentModel = updated.skinModel; currentCape = updated.capeId })
                                })
                        } else AWButton("Редактировать скин", icon = Icons.Default.Edit, enabled = picked != null && !working, onClick = ::edit)
                        if (account == null) Text("Войди через Microsoft, чтобы применить скин", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                val gallery: @Composable (Modifier) -> Unit = { modifier ->
                    val grid = rememberLazyGridState()
                    val bounds = remember { mutableStateMapOf<String, Rect>() }
                    var dragged by remember { mutableStateOf<String?>(null) }
                    var dragOffset by remember { mutableStateOf(Offset.Zero) }
                    Box(modifier) {
                        LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (compact) item("preview", span = { GridItemSpan(maxLineSpan) }) {
                                previewContent(Modifier.fillMaxWidth().height(330.dp))
                            }
                            item("saved-heading", span = { GridItemSpan(maxLineSpan) }) {
                                SkinSectionTitle("Сохранённые скины", savedOpen) { savedOpen = !savedOpen }
                            }
                            if (savedOpen) {
                                item("add") {
                                    Column(Modifier.fillMaxWidth().aspectRatio(31f / 40f).clip(RoundedCornerShape(20.dp)).background(AWColors.Surface)
                                        .clickable(enabled = !working) { chooseSkinFile()?.let(::add) }, verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Add, "Добавить скин", tint = AWColors.TextSoft, modifier = Modifier.size(32.dp))
                                        Spacer(Modifier.height(12.dp)); Text("Добавить скин", color = AWColors.TextSoft, style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                                item("current") {
                                    SkinCard("Текущий скин", currentModel, choice == "current", !working, { current },
                                        { choice = "current"; model = currentModel; capeId = currentCape }, revision = current, menu = { close ->
                                            AWMenuItem("Вернуть стандартный скин", enabled = account != null && !loading, onClick = {
                                                close()
                                                account?.let { owner -> state.applySkin(owner.uuid, null, currentModel, onApplied = { updated ->
                                                    scope.launch {
                                                        current = SkinHeads.texture(updated)?.let(MinecraftSkins::fromImage)
                                                        choice = "current"; currentModel = updated.skinModel; model = currentModel
                                                        currentCape = updated.capeId; capeId = currentCape
                                                    }
                                                }) }
                                            })
                                        })
                                }
                                itemsIndexed(saved, key = { _, skin -> skin.id }) { index, skin ->
                                    SkinCard(skin.name, skin.model, choice == skin.id, !working, { library.image(skin) },
                                        { choice = skin.id; model = skin.model; capeId = skin.capeId?.takeIf { id -> account?.capes?.any { it.id == id } == true } },
                                        menu = { close ->
                                            AWMenuItem("Редактировать", onClick = { close(); scope.launch {
                                                try { editor = SkinDraft(library.image(skin), skin.model, skin.capeId, skin.name, skin) }
                                                catch (failure: CancellationException) { throw failure }
                                                catch (failure: Exception) { error = failure.message }
                                            } })
                                            AWMenuItem("Влево", enabled = index > 0, onClick = { close(); move(skin.id, index - 1) })
                                            AWMenuItem("Вправо", enabled = index < saved.lastIndex, onClick = { close(); move(skin.id, index + 1) })
                                            AWMenuItem("Удалить", danger = true, onClick = { close(); deleting = skin })
                                        }, revision = skin.id to libraryRevision, modifier = Modifier
                                            .onGloballyPositioned { c -> bounds[skin.id] = Rect(c.positionInRoot(), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat())) }
                                            .zIndex(if (dragged == skin.id) 1f else 0f).graphicsLayer {
                                                if (dragged == skin.id) { translationX = dragOffset.x; translationY = dragOffset.y }
                                            }.pointerInput(skin.id, saved, working) {
                                                if (!working) detectDragGesturesAfterLongPress(onDragStart = { dragged = skin.id; dragOffset = Offset.Zero },
                                                    onDragCancel = { dragged = null; dragOffset = Offset.Zero },
                                                    onDragEnd = {
                                                        val center = bounds[skin.id]?.center?.plus(dragOffset)
                                                        val visible = grid.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                                                        val target = saved.indexOfFirst { it.id in visible && it.id != skin.id && center != null && bounds[it.id]?.contains(center) == true }
                                                        if (target >= 0) move(skin.id, target)
                                                        dragged = null; dragOffset = Offset.Zero
                                                    }) { change, delta -> change.consume(); dragOffset += delta }
                                            })
                                }
                            }
                            item("defaults-heading", span = { GridItemSpan(maxLineSpan) }) {
                                SkinSectionTitle("Стандартные скины", defaultsOpen) { defaultsOpen = !defaultsOpen }
                            }
                            if (defaultsOpen) items(defaultSkins, key = { it.name }) { skin ->
                                SkinCard(skin.name, skin.model, choice == skin.name, !working,
                                    { SkinHeads.texture(skin.url)?.let(MinecraftSkins::fromImage) },
                                    { choice = skin.name; model = skin.model; capeId = null })
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
                if (compact) gallery(Modifier.fillMaxSize())
                else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    previewContent(Modifier.weight(1f).fillMaxHeight())
                    gallery(Modifier.weight(2.5f).fillMaxHeight())
                }
            }
            (error ?: state.skinError)?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
                IconButton(onClick = { error = null; account?.let(state::openSkinEditor); retry++ }, enabled = !working) { Icon(Icons.Default.Refresh, "Обновить профиль", tint = AWColors.TextMuted) }
            } }
        }
    }
    editor?.let { draft -> SkinEditorDialog(draft, account?.capes.orEmpty(), saving, error,
        onDismiss = { if (!saving) { editor = null; error = null } }, onSave = { image, name, variant, cape ->
            scope.launch {
                saving = true; error = null
                try {
                    val entry = library.save(image, name, variant, cape, draft.saved?.id)
                    saved = library.list(); libraryRevision++; choice = entry.id; model = entry.model; capeId = entry.capeId
                    editor = null
                } catch (failure: CancellationException) { throw failure }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить скин" }
                finally { saving = false }
            }
        }) }
    deleting?.let { skin -> AWDialog("Удалить скин?", subtitle = skin.name, onDismiss = { deleting = null }, actions = {
        AWButton("Отменить", onClick = { deleting = null })
        AWButton("Удалить", style = ButtonStyle.DANGER, onClick = { deleting = null; scope.launch {
            try { saved = library.remove(skin.id); if (choice == skin.id) { choice = "current"; model = currentModel; capeId = currentCape } }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: "Не удалось удалить скин" }
        } })
    }) { Text("Удалится только сохранённый скин из библиотеки AWLauncher", color = AWColors.TextMuted) } }
}

@Composable
private fun SkinSectionTitle(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AWColors.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = AWColors.TextMuted)
    }
}

@Composable
private fun SkinCard(name: String, model: SkinModel, chosen: Boolean, enabled: Boolean,
                     load: suspend () -> SkinImage?, onSelect: () -> Unit, menu: (@Composable (() -> Unit) -> Unit)? = null,
                     revision: Any? = name, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, revision, model) {
        try { value = load()?.let { image -> withContext(Dispatchers.Default) {
            SkinRenderer(image.image).use { renderer -> Surface.makeRasterN32Premul(320, 380).use { surface ->
                renderer.draw(surface.canvas, 320f, 380f, model, -22.5f, 0f, 1f, true)
                surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    ImageIO.read(ByteArrayInputStream(data.bytes)).toComposeImageBitmap()
                } }
            } }
        } } } catch (failure: CancellationException) { throw failure } catch (_: Exception) { value = null }
    }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().aspectRatio(31f / 40f).clip(shape).background(if (chosen) AWColors.SurfaceHigh else AWColors.Surface)
        .border(2.dp, if (chosen) AWColors.Accent else Color.Transparent, shape)
        .clickable(enabled = enabled, onClick = onSelect).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            bitmap?.let { Image(it, name, Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
                ?: Icon(AWIcons.Image, name, tint = AWColors.TextMuted, modifier = Modifier.align(Alignment.Center))
            if (chosen) Icon(Icons.Default.Check, "Выбрано", tint = AWColors.Accent, modifier = Modifier.align(Alignment.TopStart).size(18.dp))
            if (menu != null) Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.MoreVert, "Действия", tint = AWColors.TextMuted) }
                AWDropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
            }
        }
        Text(name, translate = false, color = AWColors.Text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
