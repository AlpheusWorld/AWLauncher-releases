package ru.aw.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.aw.launcher.core.Notice
import ru.aw.launcher.core.NoticeAction
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.components.ButtonStyle
import ru.aw.launcher.ui.components.AWButton
import ru.aw.launcher.ui.components.NoticeBadge
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.components.formatNoticeDay
import ru.aw.launcher.ui.components.formatNoticeTime
import ru.aw.launcher.ui.components.noticeActionIcon
import ru.aw.launcher.ui.components.noticeActionLabel
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.softShadow

@Composable
fun NoticesScreen(state: LauncherState) {
    val items by state.notices.collectAsState()

    Column(Modifier.fillMaxSize().padding(AWDimens.Gutter)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Уведомления",
                style = MaterialTheme.typography.headlineSmall,
                color = AWColors.Text,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                AWButton("Очистить", onClick = state::clearNotices, icon = Icons.Default.Delete)
            }
        }
        Spacer(Modifier.height(20.dp))

        if (items.isEmpty()) {
            EmptyNotices()
        } else {
            val days = items.groupBy { formatNoticeDay(it.at) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                days.forEach { (day, notices) ->
                    item(key = "day-$day") {
                        Text(
                            day.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = AWColors.TextMuted,
                            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                        )
                    }
                    items(notices, key = { it.id }) { notice ->
                        NoticeCard(state, notice, fresh = notice.id > state.seenBeforeOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(state: LauncherState, notice: Notice, fresh: Boolean) {
    val shape = RoundedCornerShape(AWDimens.CornerCard)
    Row(
        Modifier
            .fillMaxWidth()
            .softShadow(shape, 10.dp)
            .clip(shape)
            .background(AWColors.Surface)
            .padding(start = 18.dp, top = 16.dp, end = 10.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NoticeBadge(notice.level, 44.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notice.title ?: notice.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AWColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (fresh) {
                    Box(Modifier.padding(start = 10.dp).size(8.dp).clip(CircleShape).background(AWColors.Accent))
                }
                Text(
                    formatNoticeTime(notice.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.TextMuted,
                    modifier = Modifier.padding(start = 10.dp),
                )
                IconButton(onClick = { state.removeNotice(notice) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Убрать", tint = AWColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
            if (notice.title != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    notice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AWColors.TextSoft,
                    modifier = Modifier.padding(end = 18.dp),
                )
            }
            if (notice.entryLabel != null || notice.count > 1) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    notice.entryLabel?.let { Tag(it, AWColors.TextSoft) }
                    if (notice.count > 1) Tag("×${notice.count}", AWColors.TextMuted)
                }
            }
            if (notice.actions.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    notice.actions.forEach { action ->
                        AWButton(
                            noticeActionLabel(action),
                            onClick = { state.runAction(notice, action) },
                            style = if (action == NoticeAction.FIX_MODS) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                            enabled = !state.busy || action == NoticeAction.LOGS,
                            icon = noticeActionIcon(action),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNotices() {
    val shape = RoundedCornerShape(AWDimens.CornerLarge)
    Box(
        Modifier.fillMaxSize().softShadow(shape).clip(shape).background(AWColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(360.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(AWColors.AccentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Notifications, null, tint = AWColors.Accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Пока тихо", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AWColors.Text)
            Spacer(Modifier.height(6.dp))
            Text(
                "Если игра упадёт или мод не обновится, здесь будет понятно, что случилось и как это исправить.",
                style = MaterialTheme.typography.bodyMedium,
                color = AWColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
