package ru.aw.launcher.ui.dialogs

import ru.aw.launcher.ui.theme.AWDimens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.unit.dp
import ru.aw.launcher.core.Settings
import ru.aw.launcher.instance.InstanceImages
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.screens.BuildArtwork
import ru.aw.launcher.ui.theme.AWColors
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.random.Random

@Composable
internal fun IconEditorDialog(state: LauncherState, entry: VersionEntry) {
    val options = state.libraryOptions(entry)
    var symbol by remember(entry.key) { mutableStateOf(options.iconPreset ?: "grass") }
    var background by remember(entry.key) { mutableStateOf(options.iconBackground ?: "emerald") }
    var image by remember(entry.key) { mutableStateOf(InstanceImages.resolve(state.gameDirOf(entry), options.iconImage)) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image) { value = image?.let { ModIcons.loadLocal(it) } }
    AWDialog("Редактор иконок", onDismiss = { if (!state.libraryBusy) state.modal = null }, width = 790.dp, actions = {
        AWButton("По умолчанию", enabled = !state.libraryBusy, onClick = { state.saveInstanceIcon(entry, null, null, reset = true) })
        AWButton("Отмена", enabled = !state.libraryBusy, onClick = { state.modal = null })
        AWButton(if (state.libraryBusy) "Сохраняю…" else "Сохранить иконку", style = ButtonStyle.PRIMARY, enabled = !state.libraryBusy,
            onClick = { state.saveInstanceIcon(entry, symbol.takeIf { image == null }, background, image) })
    }) {
        Row(Modifier.fillMaxWidth().heightIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(198.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(180.dp).clip(RoundedCornerShape(AWDimens.CornerLarge)).background(InstanceIcons.color(background)), contentAlignment = Alignment.Center) {
                    val picture = bitmap
                    if (image != null && picture != null) androidx.compose.foundation.Image(picture, "Своя иконка", Modifier.fillMaxSize())
                    else PresetInstanceIcon(symbol, background, Modifier.fillMaxSize())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (size in listOf(44.dp,30.dp,20.dp)) {
                        val picture = bitmap
                        if (image != null && picture != null) androidx.compose.foundation.Image(picture,null,Modifier.size(size).clip(RoundedCornerShape(6.dp)))
                        else PresetInstanceIcon(symbol,background,Modifier.size(size))
                    }
                }
                Text(entry.title, color = AWColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, translate = false)
                AWButton("Случайная", enabled = !state.libraryBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                    image = null; symbol = InstanceIcons.symbols.filter { it.first != symbol }.random().first
                    background = InstanceIcons.backgrounds.filter { it.first != background }.random().first
                })
                AWButton("Загрузить свою", icon = AWIcons.Folder, enabled = !state.libraryBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                    val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("PNG, JPEG, WebP", "png", "jpg", "jpeg", "webp") }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) image = chooser.selectedFile.toPath()
                })
                Text("PNG, JPEG или WebP до 8 МБ", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Фон")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InstanceIcons.backgrounds.forEach { (id, label, color) ->
                        WithTooltip(label) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(AWDimens.CornerMedium)).background(color)
                                .border(if (background == id) 2.dp else 1.dp, if (background == id) AWColors.Text else AWColors.Outline, RoundedCornerShape(AWDimens.CornerMedium))
                                .selectable(background == id,enabled = !state.libraryBusy,role = Role.RadioButton) { background = id },contentAlignment = Alignment.Center) {
                                if(background == id) Icon(Icons.Default.Check,null,tint=AWColors.Text,modifier=Modifier.size(20.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = AWColors.Outline)
                SectionTitle("Значок")
                LazyVerticalGrid(GridCells.Fixed(4), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(InstanceIcons.symbols, key = { it.first }) { (id, label) ->
                        WithTooltip(label) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerCard))
                                .border(if (image == null && symbol == id) 2.dp else 1.dp, if (image == null && symbol == id) AWColors.Accent else AWColors.Outline, RoundedCornerShape(AWDimens.CornerCard))
                                .selectable(image == null && symbol == id,enabled = !state.libraryBusy,role = Role.RadioButton) { image = null; symbol = id }.padding(7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                PresetInstanceIcon(id, background, Modifier.fillMaxWidth().aspectRatio(1f))
                                Text(label,color=if(image == null && symbol == id) AWColors.Accent else AWColors.TextSoft,
                                    style=MaterialTheme.typography.bodySmall,maxLines=1)
                            }
                        }
                    }
                }
            }
        }
        state.libraryError?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun LibraryGroupsDialog(state: LauncherState, keys: List<String>) {
    val settings by Settings.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(keys.mapNotNull(state::entryByKey).map { state.libraryOptions(it).groupId }.distinct().singleOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    val entries = keys.mapNotNull(state::entryByKey)
    AWDialog(if (keys.isEmpty()) "Группы библиотеки" else "Группа сборок", onDismiss = { state.modal = null }, width = 540.dp, actions = {
        AWButton("Закрыть", onClick = { state.modal = null })
        if (entries.isNotEmpty()) AWButton("Применить", style = ButtonStyle.PRIMARY, enabled = !state.libraryBusy && !state.buildsBusy && !state.busy, onClick = {
            state.assignGroup(entries, chosen); state.modal = null
        })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AWTextField(name, onValueChange = { name = it.take(40); error = null }, placeholder = "Название новой группы", modifier = Modifier.weight(1f))
                AWButton("Создать", enabled = name.isNotBlank(), onClick = {
                    val created = state.createLibraryGroup(name)
                    if (created == null) error = "Проверь название: группа должна быть уникальной"
                    else { chosen = created; name = ""; error = null }
                })
            }
            error?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall) }
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isNotEmpty()) ChoiceChip("Без группы", chosen == null, onClick = { chosen = null })
                if (settings.libraryGroups.isEmpty()) Text("Создай группу для своих сборок", color = AWColors.TextMuted)
                settings.libraryGroups.forEach { group ->
                    var edited by remember(group.id, group.name) { mutableStateOf(group.name) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isNotEmpty()) RadioButton(chosen == group.id, onClick = { chosen = group.id })
                        AWTextField(edited, onValueChange = { edited = it.take(40) }, placeholder = "Название группы", modifier = Modifier.weight(1f))
                        AWButton("Сохранить", enabled = edited.isNotBlank() && edited.trim() != group.name, onClick = {
                            if (settings.libraryGroups.any { it.id != group.id && it.name.equals(edited.trim(), true) }) error = "Группа с таким названием уже есть"
                            else { state.renameLibraryGroup(group.id, edited); error = null }
                        })
                        AWButton("Удалить", style = ButtonStyle.DANGER, enabled = !state.libraryBusy && !state.buildsBusy && !state.busy, onClick = {
                            state.removeLibraryGroup(group.id); if (chosen == group.id) chosen = null
                        })
                    }
                }
            }
        }
    }
}

@Composable
internal fun DuplicateDialog(state: LauncherState, entry: VersionEntry) {
    var name by remember { mutableStateOf(entry.title.take(38) + " — копия") }
    AWDialog("Дублировать сборку", onDismiss = { state.modal = null }, width = 470.dp, actions = {
        AWButton("Отмена", onClick = { state.modal = null })
        AWButton("Создать копию", style = ButtonStyle.PRIMARY, enabled = name.isNotBlank() && !state.buildsBusy && !state.busy,
            onClick = { state.duplicateEntry(entry, name) })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AWTextField(name, onValueChange = { name = it.take(48) }, placeholder = "Название копии", modifier = Modifier.fillMaxWidth())
            Text("Моды, миры и настройки будут скопированы в независимый профиль. История запусков начнётся заново", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DeleteManyDialog(state: LauncherState, entries: List<VersionEntry>) {
    AWDialog("Удалить выбранные сборки?", onDismiss = { state.modal = null }, width = 520.dp, actions = {
        AWButton("Отмена", onClick = { state.modal = null })
        AWButton("Удалить сборки", style = ButtonStyle.DANGER, enabled = !state.busy && !state.buildsBusy,
            onClick = { state.deleteLibraryEntries(entries) })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Будут удалены папки выбранных сборок вместе с мирами, модами и настройками", color = AWColors.Warning)
            Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                entries.forEach { Text(it.title, color = AWColors.Text, modifier = Modifier.padding(vertical = 4.dp), translate = false) }
            }
        }
    }
}
