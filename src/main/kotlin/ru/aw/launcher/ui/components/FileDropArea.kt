package ru.aw.launcher.ui.components

import ru.aw.launcher.ui.theme.AWDimens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.unit.dp
import ru.aw.launcher.ui.theme.AWColors
import java.net.URI
import java.nio.file.Path

internal fun fileDropPaths(values: List<String>): List<Path> = values.mapNotNull { value ->
    runCatching {
        if (value.startsWith("file:", true)) Path.of(URI(value))
        else if ("://" !in value) Path.of(value) else return@mapNotNull null
    }.getOrNull()?.toAbsolutePath()?.normalize()
}.distinct()

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FileDropArea(modifier: Modifier, enabled: Boolean, label: String, onFiles: (List<Path>) -> Unit, content: @Composable BoxScope.() -> Unit) {
    val active by rememberUpdatedState(enabled)
    val receive by rememberUpdatedState(onFiles)
    var hovering by remember { mutableStateOf(false) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                if (!active) return false
                val files = (event.dragData() as? DragData.FilesList)?.readFiles()?.let(::fileDropPaths).orEmpty()
                if (files.isEmpty()) return false
                receive(files)
                return true
            }
        }
    }
    Box(modifier.dragAndDropTarget(shouldStartDragAndDrop = { active && it.dragData() is DragData.FilesList }, target = target)) {
        content()
        if (hovering && enabled) Box(Modifier.matchParentSize().background(AWColors.AccentSoft)
            .border(2.dp, AWColors.Accent, RoundedCornerShape(AWDimens.CornerCard)), contentAlignment = Alignment.Center) {
            LocalizedText(label, color = AWColors.Text, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(AWColors.Surface, RoundedCornerShape(AWDimens.CornerMedium)).padding(18.dp))
        }
    }
}
