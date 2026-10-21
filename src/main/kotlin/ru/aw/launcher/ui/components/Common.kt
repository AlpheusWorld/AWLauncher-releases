package ru.aw.launcher.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape
import ru.aw.launcher.ui.theme.softShadow

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = AWColors.Text,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(AWDimens.CornerLarge)
    Box(
        modifier = modifier
            .background(AWColors.Surface, shape)
            .padding(20.dp),
    ) { content() }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = AWColors.TextMuted, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = AWColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(hint, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        action?.let {
            Spacer(Modifier.height(16.dp))
            it()
        }
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun LabeledRow(
    label: String,
    hint: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = AWColors.Text)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
            }
        }
        control()
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun LogLines(
    lines: List<String>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var followTail by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val scrolling = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) followTail = !listState.canScrollForward
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { forward ->
            if (!forward) followTail = true
        }
    }
    suspend fun scrollToEnd() {
        if (lines.isEmpty()) return
        listState.scrollToItem(lines.lastIndex)
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull { it.index == lines.lastIndex } ?: return
        listState.scrollBy((last.offset + last.size - layout.viewportEndOffset).coerceAtLeast(0).toFloat())
    }
    LaunchedEffect(lines) {
        if (followTail) {
            // Wait for the new lazy item content to be measured, including a changed final row.
            withFrameNanos { }
            scrollToEnd()
        }
    }
    Box(modifier.nestedScroll(scrolling)) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line.replace("\t", "    "),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = when {
                            line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception") ||
                                line.startsWith("\tat ") || line.startsWith("Caused by") -> AWColors.Danger
                            line.contains("WARN") -> AWColors.Warning
                            else -> AWColors.TextSoft
                        },
                        translate = false,
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 3.dp)
                .onPointerEvent(PointerEventType.Press) { followTail = false },
            style = ScrollbarStyle(24.dp, 8.dp, RoundedCornerShape(4.dp), 120, AWColors.Text.copy(alpha = 0.30f), AWColors.Text.copy(alpha = 0.55f)),
        )
        if (!followTail && listState.canScrollForward) {
            AWButton("В конец", modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 12.dp), onClick = {
                scope.launch {
                    scrollToEnd()
                    followTail = true
                }
            })
        }
    }
}
