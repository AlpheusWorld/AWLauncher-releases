package ru.aw.launcher.ui

import androidx.compose.animation.animateColorAsState
import ru.aw.launcher.ui.theme.AWMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import java.awt.Rectangle
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.components.WithTooltip
import ru.aw.launcher.ui.components.Wordmark
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens

@Composable
fun LauncherTitleBar(
    state: LauncherState,
    windowScope: WindowScope? = null,
    windowState: WindowState? = null,
    gameRunning: Boolean = false,
    onClose: () -> Unit = state.onQuit,
    nativeFrame: Boolean = false,
) {
    val captionEnabled = state.modal == null && state.openMenus == 0
    SideEffect { windowScope?.window?.let { WindowChrome.captionEnabled(it, captionEnabled) } }
    val maximized = windowState?.placement == WindowPlacement.Maximized
    val toggleMaximize = {
        windowState?.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    val title = when (state.screen) {
        Screen.HOME -> "Главная"
        Screen.PLAY, Screen.BUILDS -> "Сборки"
        Screen.CATALOG -> state.catalogPageTitle ?: "Каталог"
        Screen.INSTANCE -> state.instanceEntry()?.title ?: "Сборка"
        Screen.SCREENSHOTS -> "Скриншоты"
        Screen.SKINS -> "Выбор скина"
        Screen.DOWNLOADS -> "Загрузки"
        Screen.ACTIVITY -> "Активность"
        Screen.NOTICES -> "Уведомления"
        Screen.SETTINGS -> "Настройки"
        Screen.ACCOUNTS -> "Аккаунты"
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(AWColors.Sidebar)) {
        val compact = maxWidth < 900.dp
        val brandingWidth = if (maxWidth >= 1160.dp) 188.dp else 176.dp
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(AWDimens.TitleBarHeight), verticalAlignment = Alignment.CenterVertically) {
                DragArea(windowScope, Modifier.width(brandingWidth).fillMaxHeight(), nativeFrame, toggleMaximize) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Wordmark(148.dp)
                    }
                }
                WithTooltip("Назад") {
                    IconButton(state::navigateBack, enabled = state.canNavigateBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", modifier = Modifier.size(17.dp),
                            tint = if (state.canNavigateBack) AWColors.TextSoft else AWColors.TextMuted.copy(alpha = 0.35f))
                    }
                }
                WithTooltip("Вперёд") {
                    IconButton(state::navigateForward, enabled = state.canNavigateForward, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд", modifier = Modifier.size(17.dp),
                            tint = if (state.canNavigateForward) AWColors.TextSoft else AWColors.TextMuted.copy(alpha = 0.35f))
                    }
                }
                DragArea(windowScope, Modifier.weight(1f).fillMaxHeight(), nativeFrame, toggleMaximize) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = AWColors.Text,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            translate = state.screen != Screen.INSTANCE && (state.screen != Screen.CATALOG || state.catalogPageTitle == null))
                    }
                }
                if (!compact) {
                    VersionPill(state, compact = true)
                    Spacer(Modifier.width(12.dp))
                    Row(
                        Modifier.widthIn(max = 210.dp).background(AWColors.Surface, RoundedCornerShape(AWDimens.CornerMedium))
                            .clickable { state.screen = Screen.DOWNLOADS }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val color = if (state.busy || gameRunning) AWColors.Accent else AWColors.TextMuted
                        Canvas(Modifier.size(6.dp)) { drawCircle(color) }
                        Text(when {
                            state.busy -> state.stage.ifBlank { "Готовлюсь" }
                            gameRunning -> "Игра запущена"
                            else -> "Нет активных сборок"
                        }, style = MaterialTheme.typography.bodySmall, color = AWColors.TextSoft,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                WindowButton("Свернуть", enabled = windowState != null, onClick = { windowState?.isMinimized = true }) { color ->
                    drawLine(color, Offset(size.width * 0.2f, size.height * 0.65f), Offset(size.width * 0.8f, size.height * 0.65f), 1.3.dp.toPx())
                }
                WindowButton(if (maximized) "Восстановить окно" else "Развернуть", enabled = windowState != null, onClick = toggleMaximize,
                    modifier = nativeCaptionRegion(windowScope, nativeFrame, maximizeButton = true)) { color ->
                    val stroke = Stroke(1.2.dp.toPx())
                    if (maximized) {
                        drawRect(color, Offset(size.width * 0.36f, size.height * 0.16f), Size(size.width * 0.48f, size.height * 0.48f), style = stroke)
                        drawRect(color, Offset(size.width * 0.16f, size.height * 0.36f), Size(size.width * 0.48f, size.height * 0.48f), style = stroke)
                    } else drawRect(color, Offset(size.width * 0.2f, size.height * 0.2f), Size(size.width * 0.6f, size.height * 0.6f), style = stroke)
                }
                WindowButton("Закрыть", danger = true, onClick = onClose) { color ->
                    val low = size.width * 0.22f
                    val high = size.width * 0.78f
                    drawLine(color, Offset(low, low), Offset(high, high), 1.3.dp.toPx())
                    drawLine(color, Offset(low, high), Offset(high, low), 1.3.dp.toPx())
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AWColors.Outline))
        }
    }
}

@Composable
private fun nativeCaptionRegion(scope: WindowScope?, enabled: Boolean, maximizeButton: Boolean = false): Modifier {
    val key = remember { Any() }
    val window = scope?.window
    DisposableEffect(window, enabled) { onDispose { window?.let { WindowChrome.captionRegion(it, key, null) } } }
    if (!enabled || window == null) return Modifier
    return Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        WindowChrome.captionRegion(window, key, Rectangle(bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.width.roundToInt(), bounds.height.roundToInt()), maximizeButton)
    }
}

@Composable
private fun DragArea(scope: WindowScope?, modifier: Modifier, nativeFrame: Boolean, toggleMaximize: () -> Unit, content: @Composable () -> Unit) {
    if (scope == null) Box(modifier) { content() }
    else if (nativeFrame) Box(modifier.then(nativeCaptionRegion(scope, true))) { content() }
    else with(scope) { WindowDraggableArea(modifier.pointerInput(toggleMaximize) { detectTapGestures(onDoubleTap = { toggleMaximize() }) }) { content() } }
}

@Composable
private fun WindowButton(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    drawIcon: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (!hovered || !enabled) Color.Transparent else if (danger) Color(0xFFE34F55) else AWColors.SurfaceHigh,
        animationSpec = AWMotion.Hover,
        label = "windowControlHover",
    )
    val tint = if (!enabled) AWColors.TextMuted.copy(alpha = 0.35f) else if (hovered && danger) Color.White else AWColors.TextSoft
    WithTooltip(label) {
        Box(
            modifier.width(46.dp).fillMaxHeight().background(background)
                .semantics { contentDescription = label }
                .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) { drawIcon(tint) }
        }
    }
}
