package ru.aw.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.layout.SubcomposeSlotReusePolicy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import ru.aw.launcher.core.NoticeLevel
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.NoticeToast
import ru.aw.launcher.ui.components.WithTooltip
import ru.aw.launcher.ui.components.Wordmark
import ru.aw.launcher.ui.components.Brand
import ru.aw.launcher.ui.components.formatSpeed
import ru.aw.launcher.ui.dialogs.ModalHost
import ru.aw.launcher.ui.screens.AccountsScreen
import ru.aw.launcher.ui.screens.CatalogScreen
import ru.aw.launcher.ui.screens.ActivityScreen
import ru.aw.launcher.ui.screens.DashboardScreen
import ru.aw.launcher.ui.screens.DashboardSidebar
import ru.aw.launcher.ui.screens.BuildsScreen
import ru.aw.launcher.ui.screens.NoticesScreen
import ru.aw.launcher.ui.screens.SettingsScreen
import ru.aw.launcher.ui.screens.ScreenshotsScreen
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWMotion
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape
import ru.aw.launcher.ui.theme.softShadow
import ru.aw.launcher.update.UpdateState

@Composable
fun App(
    state: LauncherState,
    onGameStarted: (Process) -> Unit,
    titleBar: @Composable () -> Unit = { LauncherTitleBar(state) },
) {
    SideEffect { state.onGameStarted = onGameStarted }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(AWColors.Background)) {
            titleBar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                CompactNavRail(state)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ScreenHost(state)
                }
            }
        }
        NoticeToast(
            state,
            Modifier.align(Alignment.BottomEnd).padding(end = AWDimens.Gutter + 8.dp, bottom = AWDimens.Gutter + 8.dp),
        )
        if (state.modal != null && state.modal !is Modal.Settings) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
        ModalHost(state)
    }
}

@Composable
private fun ScreenHost(state: LauncherState) {
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val slots = remember { SubcomposeLayoutState(SubcomposeSlotReusePolicy(3)) }
    val screen = state.screen
    // Slide the cached page layer without fading the entire text/image surface.
    // Whole-page opacity adds a full-window offscreen compositing pass.
    val instanceKey = state.instanceKey.takeIf { screen == Screen.INSTANCE }
    val entrance = remember(screen, instanceKey) { Animatable(0f) }
    LaunchedEffect(entrance) { entrance.animateTo(1f, AWMotion.PageEnter) }
    SubcomposeLayout(slots, Modifier.fillMaxSize().clipToBounds()) { constraints ->
        val placeables = subcompose(screen) {
            saved.SaveableStateProvider(screen) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (entrance.value == 0f) 0f else 1f
                    translationX = (1f - entrance.value) * 8.dp.toPx()
                }) {
                    when (screen) {
                        Screen.HOME -> DashboardScreen(state)
                        Screen.INSTANCE -> ru.aw.launcher.ui.screens.InstanceScreen(state)
                        Screen.PLAY, Screen.BUILDS -> PrimaryScreenLayout(state) { BuildsScreen(state) }
                        Screen.CATALOG -> PrimaryScreenLayout(state) { CatalogScreen(state) }
                        Screen.SCREENSHOTS -> ScreenshotsScreen(state)
                        Screen.DOWNLOADS -> ru.aw.launcher.ui.screens.DownloadsScreen(state)
                        Screen.ACTIVITY -> ActivityScreen(state)
                        Screen.NOTICES -> NoticesScreen(state)
                        Screen.SETTINGS -> SettingsScreen(state)
                        Screen.ACCOUNTS -> AccountsScreen(state)
                    }
                }
            }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.placeRelative(0, 0) } }
    }
}

@Composable
private fun PrimaryScreenLayout(state: LauncherState, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebarWidth = AWDimens.sidebarWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            if (sidebarWidth != null) DashboardSidebar(state, Modifier.width(sidebarWidth).fillMaxHeight())
        }
    }
}

@Composable
private fun CompactNavRail(state: LauncherState) {
    val notices by state.notices.collectAsState()
    val seen by state.noticesSeen.collectAsState()
    val unread = notices.filter { it.id > seen }
    BoxWithConstraints(Modifier.width(AWDimens.RailWidth).fillMaxHeight()) {
    val short = maxHeight < 520.dp
    val itemHeight = if (short) 36.dp else 46.dp
    Column(
        Modifier.width(AWDimens.RailWidth).fillMaxHeight().background(AWColors.Sidebar).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 8.dp),
    ) {
        NavItem("Главная", Icons.Default.Home, state.screen == Screen.HOME, compact = true, itemHeight = itemHeight) { state.screen = Screen.HOME }
        NavItem("Играть", Icons.Default.PlayArrow, state.screen in setOf(Screen.PLAY, Screen.BUILDS, Screen.INSTANCE), compact = true, itemHeight = itemHeight) { state.screen = Screen.PLAY }
        NavItem("Каталог", Icons.Default.Search, state.screen == Screen.CATALOG, compact = true, itemHeight = itemHeight) { state.openCatalog() }
        NavItem("Скриншоты", AWIcons.Image, state.screen == Screen.SCREENSHOTS, compact = true, itemHeight = itemHeight) { state.screen = Screen.SCREENSHOTS }
        NavItem("Загрузки", AWIcons.Download, state.screen == Screen.DOWNLOADS, badge = state.downloads.pendingCount, compact = true, itemHeight = itemHeight) { state.screen = Screen.DOWNLOADS }
        Spacer(Modifier.weight(1f))
        NavItem("Активность", AWIcons.Activity, state.screen == Screen.ACTIVITY, compact = true, itemHeight = itemHeight) { state.screen = Screen.ACTIVITY }
        NavItem("Уведомления", Icons.Default.Notifications, state.screen == Screen.NOTICES, badge = unread.size, badgeAlert = unread.any { it.level == NoticeLevel.ERROR }, compact = true, itemHeight = itemHeight) { state.openNotices() }
        NavItem("Аккаунты", Icons.Default.Person, state.screen == Screen.ACCOUNTS, compact = true, itemHeight = itemHeight) { state.screen = Screen.ACCOUNTS }
        NavItem("Настройки", Icons.Default.Settings, state.modal is Modal.Settings, compact = true, itemHeight = itemHeight) { state.openSettings() }
    }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Int = 0,
    badgeAlert: Boolean = false,
    compact: Boolean = false,
    itemHeight: Dp = 46.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            selected -> AWColors.AccentSoft
            hovered -> AWColors.SurfaceHigh
            else -> Color.Transparent
        },
        animationSpec = AWMotion.Hover,
        label = "navBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> AWColors.Accent
            hovered -> AWColors.Text
            else -> AWColors.TextSoft
        },
        animationSpec = AWMotion.Hover,
        label = "navContent",
    )

    WithTooltip(if (compact) label else null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .clip(PillShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 14.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-7).dp)
                        .defaultMinSize(minWidth = 17.dp)
                        .height(17.dp)
                        .clip(PillShape)
                        .background(if (badgeAlert) AWColors.Danger else AWColors.Accent)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = if (badgeAlert) AWColors.Background else AWColors.OnAccent,
                        style = TextStyle(
                            lineHeight = 10.sp,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                        ),
                    )
                }
            }
        }
        if (!compact) {
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = content,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
    }
    }
}

@Composable
internal fun VersionPill(state: LauncherState, compact: Boolean = false) {
    val update by state.updates.collectAsState()
    val current = update
    val retry = (current as? UpdateState.Failed)?.update
    val progress = when (current) {
        is UpdateState.Downloading -> current.fraction
        is UpdateState.Installing -> 1f
        else -> null
    }
    val text = when {
        current is UpdateState.Available -> "Обновить до ${current.update.version}"
        current is UpdateState.Downloading ->
            listOf("${(current.fraction * 100).toInt()}%", formatSpeed(current.bytesPerSecond)).filter { it.isNotEmpty() }.joinToString(" · ")
        current is UpdateState.Installing -> "Устанавливаю ${current.update.version}"
        retry != null -> "Не обновилось · ещё раз"
        else -> "v${ArgumentBuilder.LAUNCHER_VERSION}"
    }
    val onClick: (() -> Unit)? = when {
        current is UpdateState.Available -> { { state.installUpdate(current.update) } }
        retry != null -> { { state.installUpdate(retry) } }
        else -> null
    }
    val (background, color) = when {
        current is UpdateState.Available -> AWColors.Accent to AWColors.OnAccent
        retry != null -> AWColors.Danger.copy(alpha = 0.16f) to AWColors.Danger
        progress != null -> AWColors.SurfaceHigh to AWColors.Text
        else -> AWColors.SurfaceHigh to AWColors.TextMuted
    }
    val progressColor = AWColors.Accent.copy(alpha = 0.35f)
    val hint = when {
        current is UpdateState.Available -> "Скачать и установить ${current.update.version}"
        current is UpdateState.Failed -> current.message
        else -> null
    }

    WithTooltip(hint) {
        Box(
            Modifier
                .padding(start = if (compact) 0.dp else 12.dp, top = if (compact) 0.dp else 8.dp, bottom = if (compact) 0.dp else 22.dp)
                .height(20.dp)
                .clip(PillShape)
                .background(background)
                .drawBehind {
                    if (progress != null) drawRect(progressColor, size = Size(size.width * progress, size.height))
                }
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = color,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                maxLines = 1,
                modifier = Modifier.offset(y = (-1).dp),
            )
        }
    }
}
