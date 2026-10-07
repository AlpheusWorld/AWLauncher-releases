package ru.aw.launcher.ui.dialogs

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.screens.InstanceSettingsContent
import ru.aw.launcher.ui.screens.SettingsContent
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWMotion
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.core.I18n

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SettingsWindowHost(state: LauncherState, width: Dp, height: Dp) {
    val requested = state.modal as? Modal.Settings
    var retained by remember { mutableStateOf(requested) }
    val visibility = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.modal) {
        if (requested != null) {
            retained = requested
        } else if (state.modal != null) {
            visibility.snapTo(0f)
            retained = null
        }
    }
    retained?.let { modal ->
        // This effect starts after the panel's focus nodes have actually been attached.
        LaunchedEffect(requested) {
            if (requested != null) {
                focus.requestFocus()
                visibility.animateTo(1f, AWMotion.PanelEnter)
            } else {
                visibility.animateTo(0f, AWMotion.PanelExit)
                retained = null
            }
        }
        DisposableEffect(Unit) {
            state.trackMenu(true)
            onDispose { state.trackMenu(false); focusManager.clearFocus(force = true) }
        }
        val labels = if (modal.entry == null) listOf("Внешний вид", "Язык", "Игра", "Загрузки", "О лаунчере", "Discord")
            else listOf("Общие", "Память и Java", "Окно игры")
        var section by remember(modal.entry?.key, modal.section) { mutableStateOf(modal.section.coerceIn(labels.indices)) }
        val sectionVisibility = remember(modal.entry?.key, section) { Animatable(0f) }
        LaunchedEffect(sectionVisibility) {
            sectionVisibility.animateTo(1f, AWMotion.SectionEnter)
        }
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.openMenus <= 1) {
                state.modal = null
                true
            } else false
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = visibility.value * 0.48f))
            }.pointerInput(Unit) { detectTapGestures { state.modal = null } })
            Column(Modifier.width(width).height(height).graphicsLayer {
                alpha = if (visibility.value == 0f) 0f else 1f
                translationY = (1f - visibility.value) * 8.dp.toPx()
            }.background(AWColors.Surface, RoundedCornerShape(18.dp)).border(1.dp, AWColors.Outline, RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .focusRequester(focus).focusProperties { exit = { FocusRequester.Cancel } }.focusable()
                .semantics { paneTitle = I18n.text(if (modal.entry == null) "Настройки" else "Настройки сборки") }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (modal.entry == null) "Настройки" else "Настройки сборки", style = MaterialTheme.typography.headlineSmall, color = AWColors.Text)
                        modal.entry?.let { Text(it.title, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, translate = false) }
                    }
                    IconButton(onClick = { state.modal = null }) { Icon(Icons.Default.Close, "Закрыть", tint = AWColors.TextMuted) }
                }
                HorizontalDivider(color = AWColors.Outline)
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(190.dp).fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (modal.entry == null) "Лаунчер" else "Сборка", color = AWColors.TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                            val sectionOrder = if (modal.entry == null) listOf(0, 1, 2, 3, 5, 4) else labels.indices.toList()
                            sectionOrder.forEach { index ->
                                val label = labels[index]
                                Row(Modifier.fillMaxWidth().background(if (section == index) AWColors.AccentSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable { section = index }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, color = if (section == index) AWColors.Accent else AWColors.TextSoft, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                        Text("AWLauncher ${ArgumentBuilder.LAUNCHER_VERSION}", color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, translate = false)
                    }
                    VerticalDivider(color = AWColors.Outline)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(20.dp).graphicsLayer {
                        alpha = if (sectionVisibility.value == 0f) 0f else 1f
                        translationY = (1f - sectionVisibility.value) * 4.dp.toPx()
                    }) {
                        if (modal.entry == null) SettingsContent(state, section)
                        else InstanceSettingsContent(state, modal.entry, section)
                    }
                }
            }
        }
    }
}
