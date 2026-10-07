package ru.aw.launcher.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import ru.aw.launcher.ui.theme.AWMotion
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.ui.CatalogSearch
import ru.aw.launcher.ui.ModIcon
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape

@Composable
fun ListBox(modifier: Modifier = Modifier, content: @Composable (LazyListState) -> Unit) {
    val listState = rememberLazyListState()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AWDimens.CornerCard))
            .background(AWColors.Background),
    ) {
        content(listState)
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            style = ScrollbarStyle(
                minimalHeight = 24.dp,
                thickness = 6.dp,
                shape = PillShape,
                hoverDurationMillis = 250,
                unhoverColor = AWColors.Text.copy(alpha = 0.18f),
                hoverColor = AWColors.Text.copy(alpha = 0.40f),
            ),
        )
    }
}

@Composable
fun SearchResults(search: CatalogSearch, listState: LazyListState, row: @Composable (Modrinth.SearchHit) -> Unit) {
    val hits = search.hits
    when {
        hits.isEmpty() && search.searching -> ListHint("Ищу в каталоге…")
        hits.isEmpty() && search.failed -> ListHint(search.error ?: "Не удалось загрузить каталог", "Повторить") { search.run() }
        hits.isEmpty() -> ListHint("Ничего не нашлось. Попробуй другое название.")
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(hits, key = { it.projectId }) { row(it) }
            item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        search.searching -> Text("Загружаю…", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                        search.failed -> AWButton("Повторить", onClick = { search.run(more = true) })
                        search.hasMore -> AWButton("Показать ещё", onClick = { search.run(more = true) })
                    }
                }
            }
        }
    }
}

@Composable
fun ContentRow(
    icon: String?,
    title: String,
    byline: String,
    details: String?,
    detailsAccent: Boolean = false,
    dimmed: Boolean = false,
    badge: String? = null,
    onOpen: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val iconAlpha = animateFloatAsState(if (dimmed) 0.45f else 1f, AWMotion.Muted, label = "contentIconOpacity")
    val textAlpha = animateFloatAsState(if (dimmed) 0.55f else 1f, AWMotion.Muted, label = "contentTextOpacity")
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.graphicsLayer { alpha = iconAlpha.value }) { ModIcon(icon) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).graphicsLayer { alpha = textAlpha.value }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AWColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    translate = false,
                )
                if (badge != null) Tag(badge, AWColors.Accent)
                if (onOpen != null) {
                    IconButton(onClick = onOpen, modifier = Modifier.size(24.dp)) {
                        Icon(AWIcons.OpenInNew, "Открыть сайт проекта", tint = AWColors.TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Text(byline, style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!details.isNullOrBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailsAccent) AWColors.Accent else AWColors.Text.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    translate = detailsAccent,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun ListHint(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AWColors.TextMuted)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            AWButton(action, onClick = onAction)
        }
    }
}

@Composable
fun StatusStrip(progress: DownloadProgress?, error: String?, message: String?) {
    if (progress == null && error == null && message == null) return
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AWDimens.CornerMedium))
            .background(AWColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when {
            progress != null -> {
                Text(
                    (message?.takeIf { it.isNotBlank() } ?: "Скачиваю ${progress.currentFile}".trimEnd()) +
                        if (progress.totalFiles > 1) " · ${progress.completedFiles}/${progress.totalFiles}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${(progress.fraction * 100).toInt()}%", color = AWColors.Accent,
                        style = MaterialTheme.typography.labelLarge, translate = false)
                    Text(if (progress.totalBytes > 0) "${formatBytes(progress.completedBytes)} / ${formatBytes(progress.totalBytes)}" else formatBytes(progress.completedBytes),
                        color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, translate = false)
                }
                Spacer(Modifier.height(6.dp))
                ThinProgress(progress.fraction, Modifier.fillMaxWidth())
            }
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = AWColors.Danger)
            message != null -> Text(message, style = MaterialTheme.typography.bodySmall, color = AWColors.Success)
        }
    }
}
