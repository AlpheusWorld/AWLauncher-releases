package ru.aw.launcher.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.SettingsDefaults
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.*
import javax.swing.JFileChooser

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun InstanceSettingsContent(state: LauncherState, entry: VersionEntry, section: Int = 0, modifier: Modifier = Modifier) {
    var name by remember(entry.title) { mutableStateOf(entry.title) }
    val defaults by Settings.state.collectAsState()
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AWColors.Accent,
        unfocusedBorderColor = AWColors.TextMuted.copy(alpha = 0.55f), disabledBorderColor = AWColors.TextMuted.copy(alpha = 0.25f),
        focusedContainerColor = AWColors.SurfaceHigh, unfocusedContainerColor = AWColors.SurfaceHigh)
    val options = state.selectedOptions
    val totalMemory = remember { SettingsDefaults.totalSystemMemoryMb() }
    val memoryLimit = remember(totalMemory) { SettingsDefaults.memoryLimit(totalMemory) }
    val recommended = remember(totalMemory) { SettingsDefaults.recommendedMemory(totalMemory) }
    var ownMemory by remember(entry.key, options.memoryMb) { mutableStateOf(options.memoryMb != null) }
    var memoryText by remember(entry.key, options.memoryMb, defaults.memoryMb) { mutableStateOf((options.memoryMb ?: defaults.memoryMb).toString()) }
    var ownArguments by remember(entry.key, options.jvmArgs) { mutableStateOf(options.jvmArgs != null) }
    var arguments by remember(entry.key, options.jvmArgs, defaults.jvmArgs) { mutableStateOf(options.jvmArgs ?: defaults.jvmArgs) }
    var ownJava by remember(entry.key, options.javaPath) { mutableStateOf(options.javaPath != null) }
    var javaPath by remember(entry.key, options.javaPath) { mutableStateOf(options.javaPath.orEmpty()) }
    var ownWindow by remember(entry.key, options.windowWidth, options.windowHeight) { mutableStateOf(options.windowWidth != null && options.windowHeight != null) }
    var width by remember(entry.key, options.windowWidth) { mutableStateOf((options.windowWidth ?: 1280).toString()) }
    var height by remember(entry.key, options.windowHeight) { mutableStateOf((options.windowHeight ?: 720).toString()) }
    var fullscreen by remember(entry.key, options.fullscreen) { mutableStateOf(options.fullscreen) }
    val enabled = !state.busy && !state.buildsBusy && !state.savingInstanceSettings
    val validMemory = !ownMemory || memoryText.toIntOrNull()?.let { it in 512..memoryLimit } == true
    val validWindow = !ownWindow || (width.toIntOrNull()?.let { it in 320..16384 } == true && height.toIntOrNull()?.let { it in 200..16384 } == true)
    val validArguments = !ownArguments || runCatching { ru.aw.launcher.launch.ArgumentBuilder.parseJvmArguments(arguments) }.isSuccess
    val validJava = !ownJava || javaPath.isNotBlank()
    val draft = options.copy(
        memoryMb = memoryText.toIntOrNull().takeIf { ownMemory },
        jvmArgs = arguments.takeIf { ownArguments },
        javaPath = javaPath.trim().removeSurrounding("\"").takeIf { ownJava },
        windowWidth = width.toIntOrNull().takeIf { ownWindow },
        windowHeight = height.toIntOrNull().takeIf { ownWindow },
        fullscreen = fullscreen,
    )
    val inherited = options.copy(memoryMb = null, jvmArgs = null, javaPath = null, windowWidth = null, windowHeight = null, fullscreen = null)
    LaunchedEffect(entry.key) { if (!state.isSelected(entry)) state.selectEntry(entry) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (section == 0) {
                entry.build?.let { build ->
                    Text("Название сборки", color = AWColors.Text, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AWTextField(name, onValueChange = { name = it.take(48) }, placeholder = "Название сборки", modifier = Modifier.weight(1f))
                        AWButton("Сохранить", enabled = name.isNotBlank() && name.trim() != build.name && !state.buildsBusy,
                            onClick = { state.renameBuild(build, name) })
                    }
                }
                Text("${entry.loader.label} ${entry.id}", color = AWColors.TextSoft, translate = false)
                AWButton("Открыть папку", icon = AWIcons.Folder, onClick = { state.openFolder(state.gameDirOf(entry)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AWButton("Изменить иконку", enabled = enabled && !state.libraryBusy, onClick = { state.editInstanceIcon(entry) })
                    AWButton("Изменить группу", enabled = enabled && !state.libraryBusy, onClick = { state.modal = Modal.Groups(listOf(entry.key)) })
                    AWButton(if (state.libraryOptions(entry).favorite) "Убрать из избранного" else "В избранное", enabled = enabled && !state.libraryBusy, onClick = { state.toggleFavorite(entry) })
                }
            }
            if (section == 1) {
                Text("Параметры этой сборки", color = AWColors.Text, fontWeight = FontWeight.Bold)
                Text("Общие настройки используются, пока для сборки не заданы свои параметры", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Своя память", color = AWColors.Text, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            AWSwitch(ownMemory, enabled = enabled, animateChanges = false, outlined = true) { ownMemory = it }
                        }
                        OutlinedTextField(memoryText, onValueChange = { if (it.length <= 7 && it.all(Char::isDigit)) memoryText = it },
                            label = { Text("Память, МБ") }, enabled = ownMemory && enabled, isError = !validMemory,
                            supportingText = { Text(if (ownMemory) "От 512 до $memoryLimit МБ" else "Из общих настроек: ${defaults.memoryMb} МБ") },
                            singleLine = true, colors = fieldColors, shape = RoundedCornerShape(AWDimens.CornerMedium), modifier = Modifier.fillMaxWidth())
                        Text("Рекомендуется для этого ПК: ${formatMemory(recommended)}", color = AWColors.Accent, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsDefaults.memoryPresets(totalMemory).filter { it <= memoryLimit }.forEach { mb ->
                                ChoiceChip(formatMemory(mb), outlined = true, selected = ownMemory && memoryText.toIntOrNull() == mb, enabled = enabled,
                                    onClick = { ownMemory = true; memoryText = mb.toString() })
                            }
                            AWButton("Рекомендуемая", enabled = enabled, onClick = { ownMemory = true; memoryText = recommended.toString() })
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Своя Java", color = AWColors.Text, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            AWSwitch(ownJava, enabled = enabled, animateChanges = false, outlined = true) { ownJava = it }
                        }
                        Text("По умолчанию лаунчер скачивает Java, подходящую версии Minecraft", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(javaPath, onValueChange = { javaPath = it }, label = { Text("Путь к Java или папке JDK") },
                            singleLine = true, colors = fieldColors, shape = RoundedCornerShape(AWDimens.CornerMedium), modifier = Modifier.fillMaxWidth(), enabled = ownJava && enabled)
                        AWButton("Выбрать Java", icon = AWIcons.Folder, enabled = ownJava && enabled, onClick = {
                            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES; dialogTitle = ru.aw.launcher.core.I18n.text("Выбрать Java") }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) javaPath = chooser.selectedFile.absolutePath
                        })
                        if (!validJava) Text("Укажи путь к установленной Java", color = AWColors.Danger, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Свои аргументы JVM", color = AWColors.Text, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            AWSwitch(ownArguments, enabled = enabled, animateChanges = false, outlined = true) { ownArguments = it }
                        }
                        OutlinedTextField(arguments, onValueChange = { arguments = it }, enabled = ownArguments && enabled, isError = !validArguments,
                            label = { Text("Аргументы JVM") }, colors = fieldColors, shape = RoundedCornerShape(AWDimens.CornerMedium),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), textStyle = MaterialTheme.typography.bodySmall,
                            supportingText = { Text(if (validArguments) "Для значений с пробелами используй кавычки" else "Закрой кавычки в аргументах JVM") })
                    }
                }
            }
            if (section == 2) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Свой размер окна", color = AWColors.Text, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            AWSwitch(ownWindow, enabled = enabled, animateChanges = false, outlined = true) { ownWindow = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(width, onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) width = it }, label = { Text("Ширина") },
                                enabled = ownWindow && enabled, isError = !validWindow, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(AWDimens.CornerMedium), modifier = Modifier.weight(1f))
                            OutlinedTextField(height, onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) height = it }, label = { Text("Высота") },
                                enabled = ownWindow && enabled, isError = !validWindow, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(AWDimens.CornerMedium), modifier = Modifier.weight(1f))
                        }
                        if (!validWindow) Text("Ширина: 320–16384, высота: 200–16384", color = AWColors.Danger, style = MaterialTheme.typography.bodySmall)
                        Text("Режим экрана", color = AWColors.Text, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Как в игре" to null, "В окне" to false, "Полный экран" to true).forEach { (label, value) ->
                                ChoiceChip(label, outlined = true, selected = fullscreen == value, enabled = enabled, onClick = { fullscreen = value })
                            }
                        }
                    }
                }
            }
            if (entry.build?.importedPlayTimeMillis?.let { it > 0 } == true) Text("Перенесённое время: ${formatPlayTime(entry.build.importedPlayTimeMillis)}", color = AWColors.TextMuted)
            if (entry.build?.importedLaunchCount?.let { it > 0 } == true) Text("Перенесено запусков: ${entry.build.importedLaunchCount}", color = AWColors.TextMuted)
        }
        HorizontalDivider(color = AWColors.Outline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AWButton(if (state.savingInstanceSettings) "Сохраняю…" else "Применить настройки", style = ButtonStyle.PRIMARY,
                enabled = enabled && validMemory && validWindow && validArguments && validJava && draft != options,
                onClick = { state.saveInstanceSettings(entry, draft) })
            AWButton("Использовать общие настройки", enabled = enabled && (draft != inherited || options != inherited), onClick = {
                ownMemory = false; memoryText = defaults.memoryMb.toString()
                ownArguments = false; arguments = defaults.jvmArgs
                ownJava = false; javaPath = ""
                ownWindow = false; width = "1280"; height = "720"
                fullscreen = null
                if (options != inherited) state.saveInstanceSettings(entry, inherited)
            })
            AWButton("Настройки лаунчера", enabled = enabled, onClick = { state.openSettings(section = 2) })
        }
    }
}
