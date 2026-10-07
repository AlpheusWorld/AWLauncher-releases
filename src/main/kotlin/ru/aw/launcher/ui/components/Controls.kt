package ru.aw.launcher.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import ru.aw.launcher.ui.theme.AWMotion
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SwitchDefaults
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape
import ru.aw.launcher.ui.theme.softShadow

@Composable
fun AWTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    clearable: Boolean = false,
    isError: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(
        when {
            isError -> AWColors.Danger.copy(alpha = 0.7f)
            focused -> AWColors.Accent.copy(alpha = 0.6f)
            else -> AWColors.Outline.copy(alpha = 0.75f)
        },
        animationSpec = AWMotion.Hover,
        label = "fieldOutline",
    )
    val shape = RoundedCornerShape(10.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AWColors.Text),
        cursorBrush = SolidColor(AWColors.Accent),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier
            .height(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { onFocusChange(it.isFocused) },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .background(AWColors.SurfaceHigh, shape)
                    .border(1.dp, outline, shape)
                    .padding(start = 18.dp, end = if (clearable) 8.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = AWColors.TextMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AWColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (clearable && value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Очистить", tint = AWColors.TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) = AWTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = placeholder,
    modifier = modifier,
    leadingIcon = Icons.Default.Search,
    clearable = true,
    focusRequester = focusRequester,
    onFocusChange = onFocusChange,
)

@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> AWColors.AccentSoft
            hovered && enabled -> AWColors.Outline
            else -> AWColors.SurfaceHigh
        },
        animationSpec = AWMotion.Hover,
        label = "chipBackground",
    )
    val content = when {
        !enabled -> AWColors.TextMuted.copy(alpha = 0.5f)
        selected -> AWColors.Accent
        else -> AWColors.Text
    }

    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, if (selected) AWColors.Accent.copy(alpha = 0.45f) else AWColors.Outline, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

@Composable
fun AWButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    translate: Boolean = true,
) {
    val colors = when (style) {
        ButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = AWColors.Accent,
            contentColor = AWColors.OnAccent,
            disabledContainerColor = AWColors.SurfaceHigh,
            disabledContentColor = AWColors.TextMuted,
        )
        ButtonStyle.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = AWColors.SurfaceHigh,
            contentColor = AWColors.Text,
            disabledContainerColor = AWColors.SurfaceHigh,
            disabledContentColor = AWColors.TextMuted.copy(alpha = 0.5f),
        )
        ButtonStyle.DANGER -> ButtonDefaults.buttonColors(
            containerColor = AWColors.Danger.copy(alpha = 0.14f),
            contentColor = AWColors.Danger,
            disabledContainerColor = AWColors.SurfaceHigh,
            disabledContentColor = AWColors.TextMuted.copy(alpha = 0.5f),
        )
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            !enabled -> AWColors.SurfaceHigh
            pressed && style == ButtonStyle.PRIMARY -> AWColors.AccentPressed
            hovered && style == ButtonStyle.PRIMARY -> AWColors.Accent.copy(alpha = 0.9f)
            hovered && style == ButtonStyle.SECONDARY -> AWColors.Outline
            hovered && style == ButtonStyle.DANGER -> AWColors.Danger.copy(alpha = 0.22f)
            else -> colors.containerColor
        }, animationSpec = AWMotion.Hover, label = "buttonBackground",
    )
    val pressOffset by animateFloatAsState(if (pressed && enabled) 1f else 0f, animationSpec = AWMotion.Press, label = "buttonPress")
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = colors.copy(containerColor = background),
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.height(48.dp).graphicsLayer { translationY = pressOffset * 2.dp.toPx() },
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, translate = translate)
    }
}

@Composable
fun AWSwitch(checked: Boolean, enabled: Boolean = true, animateChanges: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = SwitchDefaults.colors(
        checkedThumbColor = AWColors.OnAccent,
        checkedTrackColor = AWColors.Accent,
        uncheckedThumbColor = AWColors.TextMuted,
        uncheckedTrackColor = AWColors.SurfaceHigh,
        uncheckedBorderColor = AWColors.Outline,
    )
    // Each row starts at its actual state. Only subsequent state changes animate;
    // no Material thumb layout state can leak from a recycled lazy-list node.
    val motion = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, animateChanges) {
        if (animateChanges) motion.animateTo(if (checked) 1f else 0f, AWMotion.Toggle)
        else motion.snapTo(if (checked) 1f else 0f)
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusColor = AWColors.Text
    val offTrack = if (enabled) colors.uncheckedTrackColor else colors.disabledUncheckedTrackColor
    val onTrack = if (enabled) colors.checkedTrackColor else colors.disabledCheckedTrackColor
    val offThumb = if (enabled) colors.uncheckedThumbColor else colors.disabledUncheckedThumbColor
    val onThumb = if (enabled) colors.checkedThumbColor else colors.disabledCheckedThumbColor
    val offBorder = if (enabled) colors.uncheckedBorderColor else colors.disabledUncheckedBorderColor
    val onBorder = if (enabled) colors.checkedBorderColor else colors.disabledCheckedBorderColor
    val fraction = { if (animateChanges) motion.value else if (checked) 1f else 0f }
    Box(Modifier.size(52.dp, 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch,
        interactionSource = interaction, indication = null, onValueChange = onChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp, 32.dp).drawBehind {
            val value = fraction()
            drawRoundRect(lerp(offTrack, onTrack, value), cornerRadius = CornerRadius(16.dp.toPx()))
            drawRoundRect(if (focused && enabled) focusColor else lerp(offBorder, onBorder, value),
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
                cornerRadius = CornerRadius(15.dp.toPx()), style = Stroke(2.dp.toPx()))
        }) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = 8.dp).size(16.dp).graphicsLayer {
                translationX = fraction() * 20.dp.toPx()
                scaleX = 1f + fraction() * 0.5f
                scaleY = scaleX
            }.drawBehind { drawCircle(lerp(offThumb, onThumb, fraction())) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTooltip(text: String?, content: @Composable () -> Unit) {
    if (text == null) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = AWColors.Text,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .softShadow(RoundedCornerShape(AWDimens.CornerSmall), 12.dp)
                    .background(AWColors.SurfaceHigh, RoundedCornerShape(AWDimens.CornerSmall))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
    ) { content() }
}

@Composable
fun AWDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 200.dp),
        shape = RoundedCornerShape(AWDimens.CornerCard),
        containerColor = AWColors.SurfaceHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, AWColors.Outline.copy(alpha = 0.6f)),
        shadowElevation = 12.dp,
        properties = androidx.compose.ui.window.PopupProperties(focusable = focusable),
        content = content,
    )
}

@Composable
fun AWMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    translate: Boolean = true,
) {
    val color = if (danger) AWColors.Danger else AWColors.Text
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, translate = translate) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { vector -> { Icon(vector, null, modifier = Modifier.size(18.dp)) } },
        colors = MenuDefaults.itemColors(
            textColor = color,
            leadingIconColor = if (danger) AWColors.Danger else AWColors.TextMuted,
            disabledTextColor = AWColors.TextMuted.copy(alpha = 0.5f),
            disabledLeadingIconColor = AWColors.TextMuted.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(42.dp),
    )
}

@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(PillShape)
            .background(AWColors.Background)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(PillShape)
                .background(AWColors.Accent)
        )
    }
}

@Composable
fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(AWColors.Outline)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContextMenuBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onOpenChange: (Boolean) -> Unit = {},
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    val close = { open = false }
    ReportOpen(open, onOpenChange)

    Box(
        modifier.onPointerEvent(PointerEventType.Press) { event ->
            if (enabled && event.buttons.isSecondaryPressed) {
                val at = event.changes.first().position
                anchor = IntOffset(at.x.toInt(), at.y.toInt())
                open = true
            }
        }
    ) {
        content()
        Box(Modifier.offset { anchor }.size(1.dp)) {
            AWDropdownMenu(expanded = open, onDismissRequest = close) { menu(close) }
        }
    }
}

@Composable
fun ReportOpen(open: Boolean, onOpenChange: (Boolean) -> Unit) {
    if (!open) return
    DisposableEffect(Unit) {
        onOpenChange(true)
        onDispose { onOpenChange(false) }
    }
}

internal val LocalDialogPreview = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun AWDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    subtitle: String? = null,
    scrollable: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        BoxWithConstraints {
            val shape = RoundedCornerShape(AWDimens.CornerLarge)
            Column(
                Modifier
                    .padding(vertical = AWDimens.Gutter)
                    .width(width)
                    .then(if (scrollable) Modifier.heightIn(max = (maxHeight - AWDimens.Gutter * 2).coerceAtLeast(180.dp)) else Modifier)
                    .softShadow(shape, 32.dp)
                    .clip(shape)
                    .background(AWColors.Surface)
                    .padding(26.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = AWColors.Text)
                        if (subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = AWColors.TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Закрыть", tint = AWColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (scrollable) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
                else content()
                if (actions != null) {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
    if (LocalDialogPreview.current) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { body() }
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { body() }

}
