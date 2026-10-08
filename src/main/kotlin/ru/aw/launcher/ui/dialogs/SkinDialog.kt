package ru.aw.launcher.ui.dialogs

import ru.aw.launcher.ui.theme.AWDimens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import ru.aw.launcher.auth.*
import ru.aw.launcher.core.I18n
import ru.aw.launcher.core.Settings
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.SkinHeads
import ru.aw.launcher.ui.SkinPreview
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import java.awt.image.BufferedImage
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SkinDialog(state: LauncherState, uuid: String, preview: SkinImage? = null, initialTab: Int = 0) {
    val accounts by state.accounts.collectAsState()
    val account = accounts.firstOrNull { it.uuid == uuid } ?: return
    val scope = rememberCoroutineScope()
    var draft by remember(uuid) { mutableStateOf(preview) }
    var texture by remember(uuid) { mutableStateOf<BufferedImage?>(null) }
    var model by remember(uuid) { mutableStateOf(account.skinModel) }
    var filename by remember(uuid) { mutableStateOf<String?>(null) }
    var error by remember(uuid) { mutableStateOf<String?>(null) }
    var loading by remember(uuid) { mutableStateOf(preview == null) }
    var retry by remember(uuid) { mutableStateOf(0) }
    var selectedCape by remember(uuid) { mutableStateOf(account.capeId) }
    var capeTexture by remember(uuid) { mutableStateOf<BufferedImage?>(null) }
    var appearanceTab by remember(uuid) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val saving = state.skinWorkingUuid == uuid
    val skinChanged = draft != null || (texture != null && model != account.skinModel)
    val capeChanged = selectedCape != account.capeId
    val apply: () -> Unit = {
        scope.launch {
            loading = true; error = null
            try {
                val image = draft ?: texture?.let { withContext(Dispatchers.IO) { MinecraftSkins.fromImage(it) } }
                if (!skinChanged || image != null) state.applySkin(uuid, image, model,
                    capeId = selectedCape, changeCape = capeChanged, changeSkin = skinChanged)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: "Не удалось прочитать PNG скина" }
            finally { loading = false }
        }
    }
    LaunchedEffect(uuid, retry) {
        if (preview != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val current = AccountManager.skinProfile(uuid)
            selectedCape = current.capeId
            if (draft == null) { model = current.skinModel; texture = SkinHeads.texture(current) }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить скин" }
        finally { loading = false }
    }
    LaunchedEffect(selectedCape, account.capes) {
        capeTexture = null
        capeTexture = account.capes.firstOrNull { it.id == selectedCape }?.let { SkinHeads.capeTexture(it) }
    }
    AWDialog("Скин и плащ Minecraft", subtitle = account.name, width = 760.dp, scrollable = true,
        onDismiss = { if (!saving) state.modal = null }, actions = {
            AWButton("Стандартный", enabled = !loading && !saving, onClick = { state.applySkin(uuid, null, model) })
            AWButton("Отмена", enabled = !saving, onClick = { state.modal = null })
            AWButton(if (saving) "Применяю…" else "Применить к аккаунту", style = ButtonStyle.PRIMARY,
                enabled = (skinChanged || capeChanged) && !loading && !saving, onClick = apply)
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Внешний вид изменится в профиле Minecraft Java и будет доступен в других лаунчерах", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val previewWidth = (maxWidth * 0.44f).coerceAtMost(316.dp)
                val previewHeight = if (maxWidth < 600.dp) 240.dp else 396.dp
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    SkinPreview(draft?.image ?: texture, model, Modifier.width(previewWidth).height(previewHeight), loading, cape = capeTexture, showBack = appearanceTab == 1)
                    Column(Modifier.weight(1f).height(previewHeight).verticalScroll(rememberScrollState()).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceChip("Скин", selected = appearanceTab == 0, onClick = { appearanceTab = 0 })
                            ChoiceChip("Плащ", selected = appearanceTab == 1, onClick = { appearanceTab = 1 })
                            WithTooltip("Обновить профиль") {
                                IconButton(onClick = { retry++ }, enabled = !loading && !saving, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Refresh, "Обновить профиль", tint = AWColors.TextMuted)
                                }
                            }
                        }
                        if (appearanceTab == 0) {
                            Text("Модель персонажа", color = AWColors.Text, style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SkinModel.entries.forEach { variant -> ChoiceChip(variant.label, selected = model == variant, enabled = !loading && !saving,
                                    onClick = { model = variant }) }
                            }
                            Text(if (model == SkinModel.SLIM) "Тонкие руки · 3 пикселя" else "Классические руки · 4 пикселя", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(color = AWColors.Outline)
                            AWButton("Выбрать PNG", icon = AWIcons.Folder, enabled = !loading && !saving, onClick = {
                                val chooser = JFileChooser().apply {
                                    dialogTitle = I18n.text("Выбрать PNG скина")
                                    locale = Settings.current.language.locale
                                    isAcceptAllFileFilterUsed = false
                                    fileFilter = FileNameExtensionFilter("PNG", "png")
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val path = chooser.selectedFile.toPath()
                                    scope.launch {
                                        loading = true; error = null
                                        try { draft = withContext(Dispatchers.IO) { MinecraftSkins.read(path) }; filename = path.fileName.toString() }
                                        catch (failure: CancellationException) { throw failure }
                                        catch (failure: Exception) { error = failure.message ?: "Не удалось прочитать PNG скина" }
                                        finally { loading = false }
                                    }
                                }
                            })
                            filename?.let { Text(it, translate = false, color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            Text("PNG 64×64 или 64×32 · до 1 МБ", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Плащ", color = AWColors.Text, style = MaterialTheme.typography.titleSmall)
                            Text("Доступные плащи твоего аккаунта Minecraft", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            LazyVerticalGrid(columns = GridCells.Adaptive(78.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item("none") { CapeOption(null, selectedCape == null, !loading && !saving) { selectedCape = null } }
                                items(account.capes, key = { it.id }) { cape -> CapeOption(cape, selectedCape == cape.id, !loading && !saving) { selectedCape = cape.id } }
                            }
                            if (!loading && account.capes.isEmpty()) Text("У этого аккаунта пока нет плащей", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            (error ?: state.skinError)?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall) }
            Text("Игра и сервер могут показать новый скин и плащ после повторного входа", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapeOption(cape: MinecraftCape?, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val texture by produceState<BufferedImage?>(null, cape?.url) { value = cape?.let { SkinHeads.capeTexture(it) } }
    val thumbnail = remember(texture) { texture?.getSubimage(12, 1, 10, 16)?.toComposeImageBitmap() }
    val shape = RoundedCornerShape(AWDimens.CornerMedium)
    val name = cape?.alias?.takeIf { it.isNotBlank() } ?: if (cape == null) "Без плаща" else "Плащ"
    WithTooltip(name) {
        Column(Modifier.fillMaxWidth().clip(shape).background(if (chosen) AWColors.AccentSoft else AWColors.SurfaceHigh)
            .border(1.dp, if (chosen) AWColors.Accent else AWColors.Outline, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).semantics { selected = chosen }
            .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.height(64.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (thumbnail != null) Image(thumbnail!!, name, Modifier.size(36.dp, 58.dp), filterQuality = FilterQuality.None)
                else Icon(if (cape == null) Icons.Default.Close else AWIcons.Image, name, tint = AWColors.TextMuted, modifier = Modifier.size(24.dp))
            }
            Text(name, color = if (chosen) AWColors.Accent else AWColors.TextMuted, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, translate = cape == null)
        }
    }
}
