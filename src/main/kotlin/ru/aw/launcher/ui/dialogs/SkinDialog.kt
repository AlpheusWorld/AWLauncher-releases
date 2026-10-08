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
import ru.aw.launcher.ui.SkinHeads
import ru.aw.launcher.ui.SkinPreview
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import java.awt.image.BufferedImage
import java.awt.FileDialog
import java.awt.Frame


internal data class SkinDraft(val image: SkinImage, val model: SkinModel, val capeId: String?, val name: String, val saved: SavedSkin? = null)

internal fun chooseSkinFile(): java.nio.file.Path? = FileDialog(null as Frame?, I18n.text("Выбрать PNG скина"), FileDialog.LOAD).let { dialog ->
    try {
        dialog.file = "*.png"
        dialog.isVisible = true
        dialog.files.firstOrNull()?.toPath()
    } finally { dialog.dispose() }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SkinEditorDialog(draft: SkinDraft, capes: List<MinecraftCape>, saving: Boolean, error: String?,
                              onDismiss: () -> Unit, onSave: (SkinImage, String, SkinModel, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var image by remember(draft) { mutableStateOf(draft.image) }
    var name by remember(draft) { mutableStateOf(draft.name) }
    var model by remember(draft) { mutableStateOf(draft.model) }
    var capeId by remember(draft) { mutableStateOf(draft.capeId?.takeIf { id -> capes.any { it.id == id } }) }
    var localError by remember(draft) { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    val cape by produceState<BufferedImage?>(null, capeId, capes) {
        value = capes.firstOrNull { it.id == capeId }?.let { SkinHeads.capeTexture(it) }
    }
    AWDialog(if (draft.saved == null) "Добавление скина" else "Редактирование скина", width = 760.dp, scrollable = true,
        onDismiss = { if (!saving && !reading) onDismiss() }, actions = {
            AWButton("Отменить", enabled = !saving && !reading, onClick = onDismiss)
            AWButton(if (saving) "Сохраняю…" else if (draft.saved == null) "Добавить скин" else "Сохранить",
                style = ButtonStyle.PRIMARY, enabled = !saving && !reading && name.isNotBlank(),
                onClick = { onSave(image, name, model, capeId) })
        }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 540.dp
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Название", color = AWColors.Text, style = MaterialTheme.typography.titleSmall)
                    AWTextField(name, onValueChange = { name = it.take(80) }, placeholder = "Название скина", modifier = Modifier.fillMaxWidth())
                    Text("Текстура", color = AWColors.Text, style = MaterialTheme.typography.titleSmall)
                    AWButton("Заменить текстуру", icon = AWIcons.Folder, enabled = !saving && !reading, onClick = {
                        chooseSkinFile()?.let { path -> scope.launch {
                            reading = true; localError = null
                            try {
                                image = withContext(Dispatchers.IO) { MinecraftSkins.read(path) }
                                model = MinecraftSkins.modelOf(image.image)
                            } catch (failure: CancellationException) { throw failure }
                            catch (failure: Exception) { localError = failure.message ?: "Не удалось прочитать PNG скина" }
                            finally { reading = false }
                        } }
                    })
                    Text("Модель рук", color = AWColors.Text, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("Широкие", model == SkinModel.CLASSIC, enabled = !saving, onClick = { model = SkinModel.CLASSIC })
                        ChoiceChip("Тонкие", model == SkinModel.SLIM, enabled = !saving, onClick = { model = SkinModel.SLIM })
                    }
                    Text("Плащ", color = AWColors.Text, style = MaterialTheme.typography.titleSmall)
                    LazyVerticalGrid(GridCells.Adaptive(72.dp), Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item("none") { CapeOption(null, capeId == null, !saving) { capeId = null } }
                        items(capes, key = { it.id }) { cape -> CapeOption(cape, capeId == cape.id, !saving) { capeId = cape.id } }
                    }
                    if (capes.isEmpty()) Text("У этого аккаунта пока нет плащей", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SkinPreview(image.image, model, Modifier.fillMaxWidth().height(150.dp), reading, cape = cape)
                controls(Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                SkinPreview(image.image, model, Modifier.width(256.dp).height(400.dp), reading, cape = cape)
                controls(Modifier.weight(1f).heightIn(max = 450.dp).verticalScroll(rememberScrollState()))
            }
        }
        (localError ?: error)?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall) }
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
            .border(1.dp, if (chosen) AWColors.Accent else androidx.compose.ui.graphics.Color.Transparent, shape)
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
