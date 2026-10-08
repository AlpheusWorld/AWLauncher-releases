package ru.aw.launcher.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay
import ru.aw.launcher.activity.ActivityStats
import ru.aw.launcher.activity.PlaySession
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.Tag
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape
import ru.aw.launcher.ui.theme.softShadow

private val MONTHS_SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val MONTHS_OF = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val CELL = 13.dp
private val GAP = 4.dp
private val DAY_LABELS = 26.dp
private const val REFRESH_MILLIS = 60_000L
private val SESSION_ROW = 38.dp
private val SESSION_ROW_MAX = 46.dp
private val RECENT_OVERHEAD = 44.dp + 26.dp + 10.dp
private const val RECENT_MIN = 3

@Composable
fun ActivityScreen(state: LauncherState, entry: ru.aw.launcher.ui.VersionEntry? = null) {
    LaunchedEffect(Unit) {
        while (true) {
            state.loadActivity()
            delay(REFRESH_MILLIS)
        }
    }
    val stats = remember(state.activity, entry, state.builds) { if (entry == null) state.activity else state.activityOf(entry) }

    Column(
        Modifier.fillMaxSize().padding(if (entry == null) AWDimens.Gutter else 0.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (entry == null) "Активность" else "Статистика сборки",
            style = MaterialTheme.typography.headlineSmall,
            color = AWColors.Text,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        StatRow(stats)
        if ((stats?.importedMillis ?: 0) > 0) Text("Перенесённое время учтено в общем счётчике. Календарь показывает сессии, для которых сохранились логи",
            color = AWColors.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
        HeatmapCard(stats)
        val density = LocalDensity.current
        var leftHeight by remember { mutableStateOf(0.dp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).onSizeChanged { leftHeight = with(density) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (entry == null) VersionsCard(stats, Modifier.fillMaxWidth())
                ServersCard(stats, Modifier.fillMaxWidth())
            }
            val fits = ((leftHeight - RECENT_OVERHEAD) / SESSION_ROW).toInt().coerceIn(RECENT_MIN, ActivityStats.RECENT_MAX)
            val shown = minOf(fits, stats?.recent?.size ?: 0)
            val rowHeight = if (shown == fits && leftHeight > 0.dp) {
                ((leftHeight - RECENT_OVERHEAD) / fits).coerceIn(SESSION_ROW, SESSION_ROW_MAX)
            } else {
                SESSION_ROW
            }
            RecentCard(stats, fits, rowHeight, Modifier.weight(1.25f))
        }
    }
}

@Composable
private fun StatRow(stats: ActivityStats?) {
    val cards: List<@Composable RowScope.() -> Unit> = listOf(
        { StatCard(
            "Всего в игре",
            stats?.let { formatPlayTime(it.totalMillis) } ?: "…",
            AWIcons.Clock,
            AWColors.Accent,
        ) },
        { StatCard(
            "Эта неделя",
            stats?.let { formatPlayTime(it.weekMillis) } ?: "…",
            AWIcons.Calendar,
            AWColors.Info,
        ) },
        { StatCard(
            "Серия",
            stats?.let { "${it.streak} ${daysWord(it.streak)}" } ?: "…",
            AWIcons.Flame,
            AWColors.Warning,
        ) },
        { StatCard(
            "Рекорд",
            stats?.longest?.let { formatPlayTime(it.millis) } ?: if (stats == null) "…" else "—",
            AWIcons.Trophy,
            AWColors.Success,
        ) },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 650.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            cards.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { it() } }
            }
        }
    }
}

@Composable
private fun RowScope.StatCard(label: String, value: String, icon: ImageVector, tone: Color) {
    val shape = RoundedCornerShape(AWDimens.CornerCard)
    Column(
        Modifier
            .weight(1f)
                        .clip(shape)
            .background(AWColors.Surface)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(AWColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = AWColors.TextMuted, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                value,
                fontSize = when {
                    value.length <= 7 -> 26.sp
                    value.length <= 10 -> 22.sp
                    else -> 19.sp
                },
                fontWeight = FontWeight.SemiBold,
                color = AWColors.Text,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(AWDimens.CornerLarge)
    Box(
        modifier
                        .clip(shape)
            .background(AWColors.Surface)
            .padding(22.dp),
    ) { content() }
}

@Composable
private fun HeatmapCard(stats: ActivityStats?) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Heatmap(stats)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Меньше", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                (0..4).forEach { level ->
                    Box(Modifier.padding(horizontal = 2.dp).size(CELL).clip(RoundedCornerShape(3.dp)).background(levelColor(level)))
                }
                Spacer(Modifier.width(8.dp))
                Text("Больше", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Heatmap(stats: ActivityStats?) {
    val today = stats?.today ?: LocalDate.now()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val weeks = ((maxWidth - DAY_LABELS) / (CELL + GAP)).toInt().coerceIn(8, 53)
        val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
        val columns = remember(firstMonday, weeks) { (0 until weeks).map { week -> (0..6).map { firstMonday.plusDays(week * 7L + it) } } }
        val levels = remember(columns, stats?.days) { columns.flatten().map { levelOf(stats?.days?.get(it) ?: 0L) } }
        val colors = (0..4).map { levelColor(it) }
        val accent = AWColors.Accent
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        var hovered by remember(firstMonday, weeks) { mutableStateOf<LocalDate?>(null) }
        val density = LocalDensity.current
        val cellPx = with(density) { CELL.toPx() }
        val stridePx = with(density) { (CELL + GAP).toPx() }

        Column {
            Row(Modifier.padding(start = DAY_LABELS).height(16.dp)) {
                var previousMonth = -1
                columns.forEach { days ->
                    val month = days.first().monthValue
                    Box(Modifier.width(CELL + GAP)) {
                        if (month != previousMonth && days.first().dayOfMonth <= 7) {
                            Text(
                                MONTHS_SHORT[month - 1],
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = AWColors.TextMuted,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                            )
                        }
                    }
                    previousMonth = month
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Column(Modifier.width(DAY_LABELS), verticalArrangement = Arrangement.spacedBy(GAP)) {
                    listOf("пн", "", "ср", "", "пт", "", "").forEach { label ->
                        Box(Modifier.height(CELL), contentAlignment = Alignment.CenterStart) {
                            if (label.isNotEmpty()) {
                                Text(
                                    label,
                                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                    color = AWColors.TextMuted,
                                    modifier = Modifier.wrapContentHeight(unbounded = true),
                                )
                            }
                        }
                    }
                }
                TooltipArea(
                    tooltip = {
                        hovered?.let { day ->
                            val millis = stats?.days?.get(day) ?: 0L
                            Text(
                                "${day.dayOfMonth} ${MONTHS_OF[day.monthValue - 1]} · " +
                                    if (millis > 0) formatPlayTime(millis) else "без игры",
                                style = MaterialTheme.typography.bodySmall,
                                color = AWColors.Text,
                                modifier = Modifier
                                    .softShadow(RoundedCornerShape(AWDimens.CornerSmall), 12.dp)
                                    .background(AWColors.SurfaceHigh, RoundedCornerShape(AWDimens.CornerSmall))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                            )
                        }
                    },
                    delayMillis = 120,
                    tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
                ) {
                    Canvas(Modifier.width((CELL + GAP) * weeks - GAP).height(CELL * 7 + GAP * 6)
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val position = event.changes.firstOrNull()?.position
                            hovered = position?.let { heatmapDayAt(firstMonday, weeks, it.x, it.y, cellPx, stridePx, rtl) }
                        }.onPointerEvent(PointerEventType.Exit) { hovered = null }) {
                        val radius = CornerRadius(3.dp.toPx())
                        columns.forEachIndexed { week, days ->
                            val x = (if (rtl) weeks - week - 1 else week) * stridePx
                            days.forEachIndexed { row, day ->
                                val y = row * stridePx
                                if (!day.isAfter(today)) drawRoundRect(colors[levels[week * 7 + row]], Offset(x, y), Size(cellPx, cellPx), radius)
                                if (day == today) {
                                    val stroke = 1.5.dp.toPx()
                                    drawRoundRect(accent, Offset(x + stroke / 2, y + stroke / 2), Size(cellPx - stroke, cellPx - stroke), radius, style = Stroke(stroke))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun heatmapDayAt(firstMonday: LocalDate, weeks: Int, x: Float, y: Float, cell: Float, stride: Float, rtl: Boolean): LocalDate? {
    if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || !cell.isFinite() || !stride.isFinite() || cell <= 0 || stride < cell) return null
    val column = (x / stride).toInt()
    val row = (y / stride).toInt()
    if (column !in 0 until weeks || row !in 0..6 || x % stride >= cell || y % stride >= cell) return null
    val week = if (rtl) weeks - column - 1 else column
    return firstMonday.plusDays(week * 7L + row)
}

@Composable
private fun VersionsCard(stats: ActivityStats?, modifier: Modifier) {
    Card(modifier) {
        Column {
            CardTitle("Любимые версии", AWIcons.Layers, AWColors.Info)
            Spacer(Modifier.height(14.dp))
            val versions = stats?.versions.orEmpty()
            if (versions.isEmpty()) {
                Text(if (stats == null) "Считаю…" else "Пока пусто", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
            }
            val max = versions.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                versions.forEach { version ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                version.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = AWColors.Text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(formatPlayTime(version.millis), style = MaterialTheme.typography.bodySmall, color = AWColors.TextSoft)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(AWColors.SurfaceHigh)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((version.millis.toFloat() / max).coerceIn(0.04f, 1f))
                                    .fillMaxHeight()
                                    .clip(PillShape)
                                    .background(loaderTone(version.loader)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServersCard(stats: ActivityStats?, modifier: Modifier) {
    Card(modifier) {
        Column {
            CardTitle("Любимые серверы", AWIcons.Server, AWColors.Success)
            Spacer(Modifier.height(14.dp))
            val servers = stats?.servers.orEmpty()
            if (servers.isEmpty()) {
                Text(
                    if (stats == null) "Считаю…" else "Пока не заходил на серверы",
                    style = MaterialTheme.typography.bodySmall,
                    color = AWColors.TextMuted,
                )
            }
            val max = servers.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                servers.forEach { (address, millis) ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = AWColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatPlayTime(millis), style = MaterialTheme.typography.bodySmall, color = AWColors.TextSoft)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(AWColors.SurfaceHigh)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((millis.toFloat() / max).coerceIn(0.04f, 1f))
                                    .fillMaxHeight()
                                    .clip(PillShape)
                                    .background(AWColors.Success),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(stats: ActivityStats?, rows: Int, rowHeight: Dp, modifier: Modifier) {
    Card(modifier) {
        Column {
            CardTitle("Последние сессии", AWIcons.History, AWColors.Accent)
            Spacer(Modifier.height(10.dp))
            val recent = stats?.recent.orEmpty().take(rows)
            if (recent.isEmpty()) {
                Text(if (stats == null) "Считаю…" else "Пока пусто", style = MaterialTheme.typography.bodySmall, color = AWColors.TextMuted)
            }
            recent.forEach { session -> SessionRow(session, stats?.today ?: LocalDate.now(), rowHeight) }
        }
    }
}

@Composable
private fun CardTitle(text: String, icon: ImageVector, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(AWColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = AWColors.TextMuted)
    }
}

@Composable
private fun SessionRow(session: PlaySession, today: LocalDate, height: Dp) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                whenOf(session.start, today),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AWColors.Text,
                maxLines = 1,
            )
        }
        Tag(session.label, loaderTone(session.loader))
        Text(
            formatPlayTime(session.millis),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = AWColors.Text,
            modifier = Modifier.width(92.dp).padding(start = 12.dp),
            maxLines = 1,
        )
    }
}

private fun levelOf(millis: Long): Int {
    val minutes = millis / 60_000
    return when {
        millis <= 0 -> 0
        minutes < 30 -> 1
        minutes < 60 -> 2
        minutes < 120 -> 3
        else -> 4
    }
}

@Composable
private fun levelColor(level: Int): Color = when (level) {
    0 -> AWColors.SurfaceHigh
    1 -> AWColors.Accent.copy(alpha = 0.25f).compositeOver(AWColors.Surface)
    2 -> AWColors.Accent.copy(alpha = 0.48f).compositeOver(AWColors.Surface)
    3 -> AWColors.Accent.copy(alpha = 0.74f).compositeOver(AWColors.Surface)
    else -> AWColors.Accent
}

@Composable
private fun loaderTone(kind: LoaderKind): Color = when (kind) {
    LoaderKind.FABRIC -> AWColors.LoaderFabric
    LoaderKind.QUILT -> AWColors.LoaderQuilt
    LoaderKind.FORGE -> AWColors.LoaderForge
    LoaderKind.NEOFORGE -> AWColors.LoaderNeoForge
    LoaderKind.VANILLA -> AWColors.LoaderVanilla
}

internal fun formatPlayTime(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "< 1 мин"
        hours == 0L -> "$minutes мин"
        minutes % 60 == 0L -> "$hours ч"
        else -> "$hours ч ${minutes % 60} мин"
    }
}

internal fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    val ones = count % 10
    return when {
        tens in 11..14 -> many
        ones == 1 -> one
        ones in 2..4 -> few
        else -> many
    }
}

private fun daysWord(count: Int) = plural(count, "день", "дня", "дней")

private fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()


private fun whenOf(at: Long, today: LocalDate): String {
    val date = dateOf(at)
    val clock = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(CLOCK)
    val day = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> "${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}"
    }
    return "$day, $clock"
}
