package ru.aw.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.aw.launcher.ui.DownloadStatus
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.QueuedDownload
import ru.aw.launcher.ui.components.*
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(state: LauncherState) {
    val queue = state.downloads
    val ordered = queue.items.filter { it.status != DownloadStatus.WAITING && it.pending } +
        queue.items.filter { it.status == DownloadStatus.WAITING } + queue.items.filterNot { it.pending }.asReversed()
    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Загрузки", style = MaterialTheme.typography.headlineSmall, color = AWColors.Text, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AWButton(if (queue.paused) "Продолжить очередь" else "Приостановить очередь",
                onClick = { queue.pauseQueue(!queue.paused) }, icon = if (queue.paused) Icons.Default.PlayArrow else AWIcons.Pause,
                enabled = queue.pendingCount > 0 || queue.paused)
            AWButton("Очистить завершённые", onClick = queue::clearFinished, enabled = queue.items.any { !it.pending })
        }
        if (queue.paused) Text("Текущая задача завершится. Остальные ждут продолжения очереди", color = AWColors.TextSoft,
            style = MaterialTheme.typography.bodySmall)
        if (ordered.isEmpty()) {
            EmptyState(AWIcons.Download, "Загрузок пока нет", "Добавляй моды и сборки из каталога — они загрузятся по очереди",
                Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(ordered, key = { _, item -> item.id }) { _, item ->
                    val position = queue.items.filter { it.status == DownloadStatus.WAITING }.indexOfFirst { it.id == item.id } + 1
                    DownloadCard(item, position, state)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadCard(item: QueuedDownload, position: Int, state: LauncherState) {
    val color = when (item.status) {
        DownloadStatus.RUNNING, DownloadStatus.COMPLETED -> AWColors.Accent
        DownloadStatus.FAILED -> AWColors.Danger
        else -> AWColors.TextMuted
    }
    val shape = RoundedCornerShape(AWDimens.CornerCard)
    Column(Modifier.fillMaxWidth().background(AWColors.Surface, shape).border(1.dp,
        if (item.status == DownloadStatus.RUNNING) AWColors.Accent.copy(alpha = 0.45f) else AWColors.Outline, shape)
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.title, color = AWColors.Text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false)
        val status = when (item.status) {
            DownloadStatus.WAITING -> "В очереди · $position"
            DownloadStatus.RUNNING -> item.stage.ifBlank { "Готовлю загрузку" }
            DownloadStatus.CANCELLING -> "Отменяю загрузку…"
            DownloadStatus.COMPLETED -> "Завершено"
            DownloadStatus.CANCELLED -> "Отменено"
            DownloadStatus.FAILED -> "Не удалось загрузить"
        }
        Text(status, color = color, style = MaterialTheme.typography.bodySmall)
        item.error?.let { Text(it, color = AWColors.Danger, style = MaterialTheme.typography.bodySmall, translate = false) }
        val progress = item.progress
        if (item.status == DownloadStatus.RUNNING && progress != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${(progress.fraction * 100).toInt()}%", color = AWColors.Accent, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(
                    "${formatBytes(progress.completedBytes)} / ${formatBytes(progress.totalBytes)}".takeIf { progress.totalBytes > 0 },
                    formatSpeed(progress.bytesPerSecond).takeIf { it.isNotEmpty() },
                ).joinToString(" · "), color = AWColors.TextSoft, style = MaterialTheme.typography.bodySmall)
            }
            ThinProgress(progress.fraction, Modifier.fillMaxWidth())
            Text(progress.currentFile, color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, translate = false)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.status) {
                DownloadStatus.WAITING -> {
                    AWButton("Загрузить следующим", onClick = { state.downloads.moveFirst(item.id) }, enabled = position > 1)
                    AWButton("Убрать из очереди", onClick = { state.downloads.cancel(item.id) }, icon = Icons.Default.Close)
                }
                DownloadStatus.RUNNING -> AWButton("Отменить", onClick = { state.downloads.cancel(item.id) }, icon = Icons.Default.Close)
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> AWButton("Повторить", onClick = { state.downloads.retry(item.id) }, icon = Icons.Default.Refresh)
                else -> Unit
            }
        }
    }
}
