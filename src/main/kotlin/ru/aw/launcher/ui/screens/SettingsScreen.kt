package ru.aw.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.jsonPrimitive
import ru.aw.launcher.discord.DiscordPresence
import ru.aw.launcher.discord.DiscordStatus
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.SettingsDefaults
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.core.Language
import ru.aw.launcher.core.VerifyCache
import ru.aw.launcher.core.Shell
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.logs.LogSource
import ru.aw.launcher.net.Downloader
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.components.ChoiceChip
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.ThinProgress
import ru.aw.launcher.ui.components.formatSpeed
import ru.aw.launcher.ui.components.Brand
import ru.aw.launcher.ui.components.Wordmark
import ru.aw.launcher.ui.components.AWSwitch
import ru.aw.launcher.ui.components.LabeledRow
import ru.aw.launcher.ui.components.Panel
import ru.aw.launcher.ui.components.SectionTitle
import ru.aw.launcher.ui.components.formatMemory
import ru.aw.launcher.ui.components.formatTotalMemory
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.languageFont
import ru.aw.launcher.update.UpdateState
import ru.aw.launcher.update.Updater

private data class SettingsSection(val id: Int, val label: String, val description: String, val icon: ImageVector)

@Composable
fun SettingsScreen(state: LauncherState, initialSection: Int = -1, inDialog: Boolean = false, onClose: (() -> Unit)? = null) {
    val sections = listOf(
        SettingsSection(-1, "Аккаунты", "Выбранный аккаунт используется при запуске Minecraft", Icons.Default.Person),
        SettingsSection(0, "Общие", "Поведение лаунчера", Icons.Default.Settings),
        SettingsSection(2, "Игра", "Память", AWIcons.Memory),
        SettingsSection(6, "Внешний вид", "Тема", AWIcons.Image),
        SettingsSection(1, "Язык", "Язык приложения", AWIcons.Log),
        SettingsSection(3, "Загрузки", "Загрузка и целостность", AWIcons.Download),
        SettingsSection(5, "Discord", "Discord Rich Presence", AWIcons.Friends),
        SettingsSection(4, "О лаунчере", "Версия лаунчера", AWIcons.Log),
    )
    var section by remember(initialSection) { mutableStateOf(initialSection.takeIf { id -> sections.any { it.id == id } } ?: -1) }
    var query by remember { mutableStateOf("") }
    val language = ru.aw.launcher.ui.components.LocalLanguage.current
    val filtered = sections.filter { query.isBlank() || ru.aw.launcher.core.I18n.text(it.label, language).contains(query.trim(), true) ||
        ru.aw.launcher.core.I18n.text(it.description, language).contains(query.trim(), true) }
    val current = sections.first { it.id == section }
    BoxWithConstraints(Modifier.fillMaxSize().then(if (inDialog) Modifier else Modifier.padding(AWDimens.Gutter))) {
        val compact = maxWidth < 700.dp
        val shape = RoundedCornerShape(AWDimens.CornerLarge)
        Column(Modifier.fillMaxSize().clip(shape).background(AWColors.Surface).border(1.dp, AWColors.Outline, shape)) {
            if (compact) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Настройки", style = MaterialTheme.typography.titleLarge, color = AWColors.Text)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sections.forEach { item -> ChoiceChip(item.label, selected = section == item.id, onClick = { section = item.id }) }
                    }
                }
                HorizontalDivider(color = AWColors.Outline)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (!compact) {
                    Column(Modifier.width(248.dp).fillMaxHeight().background(AWColors.Sidebar).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Настройки", style = MaterialTheme.typography.titleLarge, color = AWColors.Text)
                        ru.aw.launcher.ui.components.AWTextField(query, onValueChange = { query = it }, placeholder = "Найти раздел",
                            modifier = Modifier.fillMaxWidth(), clearable = true)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            filtered.forEach { item ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium))
                                    .background(if (section == item.id) AWColors.SurfaceHigh else Color.Transparent)
                                    .clickable { section = item.id }.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(item.icon, null, tint = if (section == item.id) AWColors.Text else AWColors.TextMuted, modifier = Modifier.size(18.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.label, style = MaterialTheme.typography.titleSmall, color = AWColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.description, style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (filtered.isEmpty()) Text("Раздел не найден", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("AWLauncher ${ArgumentBuilder.LAUNCHER_VERSION}", color = AWColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall, translate = false)
                    }
                    androidx.compose.material3.VerticalDivider(color = AWColors.Outline)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 24.dp, end = if (compact) 12.dp else 18.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(current.label, color = AWColors.Text, style = MaterialTheme.typography.titleLarge)
                            Text(current.description, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, "Закрыть", tint = AWColors.TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(color = AWColors.Outline)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(if (compact) 16.dp else 24.dp)) {
                        if (section == -1) AccountsScreen(state, embedded = true)
                        else SettingsContent(state, section, separateAppearance = true)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SettingsContent(state: LauncherState, section: Int, separateAppearance: Boolean = false) {
    val settings by Settings.state.collectAsState()
    if (section == 1) {
        LanguagePanel(settings)
        return
    }
    val scroll = androidx.compose.runtime.key(section) { rememberScrollState() }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (section == 0) {
            if (!separateAppearance) AppearancePanel(settings)
            SettingsGroup(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionTitle("Поведение лаунчера")
                    SettingsRow("Не закрывать лаунчер при запуске игры", divider = false) {
                        AWSwitch(settings.keepLauncherOpen, outlined = true) { checked ->
                            Settings.update { it.copy(keepLauncherOpen = checked) }
                        }
                    }
                    SettingsRow("Игра с друзьями", divider = false) {
                        AWSwitch(settings.friendsEnabled, outlined = true) { checked ->
                            Settings.update { it.copy(friendsEnabled = checked) }
                        }
                    }
                }
            }
        }
        if (section == 6) AppearancePanel(settings)
        if (section == 2) {
            MemoryPanel(settings)

            SettingsGroup(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionTitle("Список версий")
                    SettingsRow("Показывать снапшоты") {
                        AWSwitch(settings.showSnapshots, outlined = true) { checked ->
                            Settings.update { it.copy(showSnapshots = checked) }
                        }
                    }
                    SettingsRow("Показывать alpha и beta", divider = false) {
                        AWSwitch(settings.showOldVersions, outlined = true) { checked ->
                            Settings.update { it.copy(showOldVersions = checked) }
                        }
                    }
                }
            }

            JvmArgumentsPanel(settings)

        }
        if (section == 3) DownloadsPanel(state, settings)
        if (section == 5) DiscordPanel(settings)
        if (section == 4) {
            SectionTitle("О лаунчере")
            BoxWithConstraints(Modifier.fillMaxWidth()) { Wordmark(minOf(280.dp, maxWidth)) }
            Text("Версия лаунчера: ${ArgumentBuilder.LAUNCHER_VERSION}", color = AWColors.TextMuted)
            val update by state.updates.collectAsState()
            val downloading = update as? UpdateState.Downloading
            if (downloading != null) {
                LauncherUpdateProgress(downloading, Updater::cancelDownload)
            } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AWButton("Проверить обновления", enabled = update !is UpdateState.Checking && update !is UpdateState.Installing,
                    onClick = state::checkForUpdates)
                ru.aw.launcher.ui.VersionPill(state, compact = true)
            }
            if (update is UpdateState.UpToDate) Text("Установлена последняя версия", color = AWColors.Success, style = MaterialTheme.typography.bodySmall)
            (update as? UpdateState.Failed)?.let { Text(it.message, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(color = AWColors.Outline)
            AWButton("Открыть GitHub", icon = AWIcons.OpenInNew, onClick = { Shell.browse("https://github.com/AlpheusWorld/AWLauncher-releases") })
            AWButton("Открыть папку лаунчера", icon = AWIcons.Folder, onClick = { state.openFolder(Paths.root) })
        }
    }
}

@Composable
internal fun LauncherUpdateProgress(update: UpdateState.Downloading, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(AWColors.SurfaceHigh, RoundedCornerShape(AWDimens.CornerLarge)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Загрузка файлов", color = AWColors.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${(update.fraction.coerceIn(0f, 1f) * 100).toInt()}%", color = AWColors.Accent,
                style = MaterialTheme.typography.titleMedium, translate = false)
        }
        ThinProgress(update.fraction, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatSpeed(update.bytesPerSecond), color = AWColors.TextMuted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), translate = false)
            AWButton("Отменить", onClick = onCancel)
        }
    }
}

@Composable
private fun DiscordPanel(settings: LauncherSettings) {
    val presence by DiscordPresence.current.collectAsState()
    val status by DiscordPresence.status.collectAsState()
    val card = remember(presence, settings) { DiscordPresence.activity(presence, settings) }
    SectionTitle("Discord Rich Presence")
    Text("Покажи друзьям, во что играешь", style = MaterialTheme.typography.bodyMedium, color = AWColors.TextMuted)
    SettingsGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(Brand.icon, "AWLauncher", Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("AWLauncher", style = MaterialTheme.typography.titleMedium, color = AWColors.Text, translate = false)
                    Text(card.getValue("details").jsonPrimitive.content, style = MaterialTheme.typography.bodyMedium,
                        color = AWColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
                    Text(card.getValue("state").jsonPrimitive.content, style = MaterialTheme.typography.bodySmall,
                        color = AWColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
                }
            }
            HorizontalDivider(color = AWColors.Outline)
            Text("С таймером текущей сессии и кнопкой сайта", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
        }
    }
    SettingsGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SettingsRow("Показывать активность в Discord", "Сборка, версия Minecraft и время игры") {
                AWSwitch(settings.discordPresence, outlined = true) { value -> Settings.update { it.copy(discordPresence = value) } }
            }
            SettingsRow("Показывать открытый лаунчер", "Если выключить, активность появится только во время игры") {
                AWSwitch(settings.discordShowLauncher, enabled = settings.discordPresence, outlined = true) { value -> Settings.update { it.copy(discordShowLauncher = value) } }
            }
            SettingsRow("Показывать название сборки", "Для сборок из каталога добавится кнопка их страницы") {
                AWSwitch(settings.discordShowInstance, enabled = settings.discordPresence, outlined = true) { value -> Settings.update { it.copy(discordShowInstance = value) } }
            }
            SettingsRow("Показывать адрес сервера", "Друзья увидят адрес; для публичных серверов загружается иконка", divider = false) {
                AWSwitch(settings.discordShowServer, enabled = settings.discordPresence, outlined = true) { value -> Settings.update { it.copy(discordShowServer = value) } }
            }
        }
    }
    Text(when {
        !settings.discordPresence -> "Активность выключена"
        status == DiscordStatus.CONNECTED -> "Discord подключён"
        status == DiscordStatus.NOT_CONFIGURED -> "Приложение Discord не настроено"
        else -> "Открой Discord на компьютере — лаунчер подключится автоматически"
    }, color = if (status == DiscordStatus.CONNECTED && settings.discordPresence) AWColors.Accent else AWColors.TextMuted,
        style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePanel(settings: LauncherSettings) {
    SettingsGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "Как в системе",
                    ThemeMode.DARK to "Тёмная",
                    ThemeMode.LIGHT to "Светлая",
                    ThemeMode.OLED to "OLED · чёрная",
                ).forEach { (mode, label) ->
                    ChoiceChip(label, outlined = true, selected = settings.themeMode == mode, onClick = {
                        Settings.update { it.copy(themeMode = mode) }
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryPanel(settings: LauncherSettings) {
    val total = remember { SettingsDefaults.totalSystemMemoryMb() }
    val presets = remember { SettingsDefaults.memoryPresets(total) }
    val limit = remember { SettingsDefaults.memoryLimit(total) }
    val recommended = remember { SettingsDefaults.recommendedMemory(total) }
    var memoryText by remember(settings.memoryMb) { mutableStateOf(settings.memoryMb.toString()) }
    val customMemory = memoryText.toIntOrNull()
    val validMemory = customMemory != null && customMemory in 512..limit

    SettingsGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionTitle("Память")
            Text(
                "${formatMemory(settings.memoryMb)} для игры · в компьютере ${formatTotalMemory(total)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AWColors.Text,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Рекомендуется для этого ПК: ${formatMemory(recommended)}",
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.Accent,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = (customMemory ?: settings.memoryMb).coerceIn(512, limit).toFloat(),
                onValueChange = { value -> memoryText = ((value / 256f).toInt() * 256).coerceIn(512, limit).toString() },
                valueRange = 512f..limit.toFloat(),
                colors = SliderDefaults.colors(thumbColor = AWColors.Accent, activeTrackColor = AWColors.Accent,
                    inactiveTrackColor = AWColors.Outline),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.filter { it <= limit }.forEach { mb ->
                    ChoiceChip(formatMemory(mb), outlined = true, selected = settings.memoryMb == mb, onClick = {
                        memoryText = mb.toString()
                        Settings.update { it.copy(memoryMb = mb) }
                    })
                }
                if (settings.memoryMb !in presets) {
                    ChoiceChip("${formatMemory(settings.memoryMb)} (своё)", outlined = true, selected = true, onClick = {})
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = memoryText,
                onValueChange = { value -> if (value.length <= 7 && value.all(Char::isDigit)) memoryText = value },
                label = { Text("Своя память, МБ") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AWColors.Accent,
                    unfocusedBorderColor = AWColors.TextMuted.copy(alpha = 0.55f), focusedContainerColor = AWColors.SurfaceHigh,
                    unfocusedContainerColor = AWColors.SurfaceHigh),
                supportingText = { Text("От 512 до $limit МБ · оставляем память системе") },
                isError = !validMemory,
                singleLine = true,
                shape = RoundedCornerShape(AWDimens.CornerMedium),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AWButton("Применить", enabled = validMemory && customMemory != settings.memoryMb, onClick = {
                    customMemory?.takeIf { it in 512..limit }?.let { mb -> Settings.update { it.copy(memoryMb = mb) } }
                })
                AWButton("Использовать рекомендуемую", onClick = {
                    memoryText = recommended.toString()
                    Settings.update { it.copy(memoryMb = recommended) }
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePanel(settings: LauncherSettings) {
    var query by remember { mutableStateOf("") }
    val languages = remember(query, settings.language) {
        Language.entries.filter { language ->
            query.isBlank() || listOf(language.label, language.englishName, language.tag,
                language.locale.getDisplayName(settings.language.locale)).any { it.contains(query.trim(), ignoreCase = true) }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Язык приложения")
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Поиск языка…") }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AWDimens.CornerMedium),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AWColors.Accent,
                unfocusedBorderColor = AWColors.Outline, focusedContainerColor = AWColors.SurfaceHigh,
                unfocusedContainerColor = AWColors.SurfaceHigh))
        if (languages.isEmpty()) Text("Ничего не найдено", color = AWColors.TextMuted)
        val scroll = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(languages, key = { it.name }) { language ->
                    val selected = settings.language == language
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium))
                        .background(if (selected) AWColors.AccentSoft else Color.Transparent)
                        .selectable(selected, role = Role.RadioButton, onClick = { Settings.update { it.copy(language = language) } }).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(Brand.languageFlag(language), null,
                            modifier = Modifier.size(28.dp, 21.dp).clip(RoundedCornerShape(3.dp))
                                .border(1.dp, AWColors.Outline.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(language.label, translate = false, style = MaterialTheme.typography.titleSmall.copy(fontFamily = languageFont(language)),
                                color = if (selected) AWColors.Accent else AWColors.Text)
                            Text(language.locale.getDisplayName(settings.language.locale), translate = false,
                                style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                        }
                        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AWColors.Accent, unselectedColor = AWColors.Outline))
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = ScrollbarStyle(24.dp, 5.dp, RoundedCornerShape(4.dp), 120,
                    AWColors.Text.copy(alpha = 0.18f), AWColors.Text.copy(alpha = 0.4f)))
        }
    }
}

@Composable
private fun JvmArgumentsPanel(settings: LauncherSettings) {
    SettingsGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionTitle("Аргументы JVM")
            OutlinedTextField(
                value = settings.jvmArgs,
                onValueChange = { value -> Settings.update { it.copy(jvmArgs = value) } },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(AWDimens.CornerMedium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AWColors.Accent,
                    unfocusedBorderColor = AWColors.TextMuted.copy(alpha = 0.55f),
                    focusedContainerColor = AWColors.SurfaceHigh,
                    unfocusedContainerColor = AWColors.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Spacer(Modifier.height(8.dp))
            AWButton("Сбросить к рекомендуемым", onClick = {
                Settings.update { it.copy(jvmArgs = SettingsDefaults.JVM_ARGS) }
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadsPanel(state: LauncherState, settings: LauncherSettings) {
    SettingsGroup(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionTitle("Загрузка и целостность")
            val threads = settings.downloadConcurrency.takeIf { it > 0 } ?: Downloader.DEFAULT_CONCURRENCY
            Text("Потоков загрузки: $threads", style = MaterialTheme.typography.bodyMedium, color = AWColors.Text)
            Slider(
                value = threads.toFloat(),
                onValueChange = { value -> Settings.update { it.copy(downloadConcurrency = value.toInt()) } },
                valueRange = 4f..32f,
                colors = SliderDefaults.colors(
                    thumbColor = AWColors.Accent,
                    activeTrackColor = AWColors.Accent,
                    inactiveTrackColor = AWColors.SurfaceHigh,
                ),
            )

            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AWButton("Сбросить кэш проверки", onClick = {
                    VerifyCache.clear()
                    state.inform("Кэш проверки сброшен — следующий запуск заново пересчитает хеши всех файлов")
                })
                AWButton("Открыть папку лаунчера", icon = AWIcons.Folder, onClick = { state.openFolder(Paths.root) })
                AWButton("Лог лаунчера", icon = AWIcons.Log, onClick = { state.showLogs(null, LogSource.LAUNCHER) })
            }
            Text(
                "Сброс кэша проверки помогает, когда игра вылетает из-за повреждённых файлов: " +
                    "лаунчер перепроверит и докачает всё, что не сходится.",
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AWDimens.CornerMedium))
        .background(AWColors.SurfaceHigh).border(1.dp, AWColors.Outline, RoundedCornerShape(AWDimens.CornerMedium))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

@Composable
private fun SettingsRow(label: String, hint: String? = null, divider: Boolean = true, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LabeledRow(label, hint, control)
        if (divider) HorizontalDivider(color = AWColors.TextMuted.copy(alpha = 0.3f))
    }
}
