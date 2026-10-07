package ru.aw.launcher.activity

import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.instance.LocalBuild
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class VersionTime(val label: String, val loader: LoaderKind, val millis: Long)

data class ActivityStats(
    val today: LocalDate,
    val sessions: List<PlaySession>,
    val days: Map<LocalDate, Long>,
    val totalMillis: Long,
    val weekMillis: Long,
    val lastWeekMillis: Long,
    val streak: Int,
    val bestStreak: Int,
    val longest: PlaySession?,
    val versions: List<VersionTime>,
    val servers: List<Pair<String, Long>>,
    val importedMillis: Long = 0,
) {
    val recent: List<PlaySession> = sessions.sortedByDescending { it.start }.take(RECENT_MAX)
    val activeDays: Int get() = days.count { it.value > 0 }

    companion object {
        const val RECENT_MAX = 12
        const val TOP = 4

        fun of(sessions: List<PlaySession>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), imports: List<LocalBuild> = emptyList()): ActivityStats {
            val days = HashMap<LocalDate, Long>()
            sessions.forEach { session -> split(session, zone).forEach { (day, millis) -> days.merge(day, millis, Long::plus) } }

            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val week = days.filterKeys { !it.isBefore(weekStart) && !it.isAfter(today) }.values.sum()
            val lastWeek = days.filterKeys { !it.isBefore(weekStart.minusWeeks(1)) && it.isBefore(weekStart) }.values.sum()

            val played = days.filterValues { it > 0 }.keys
            var streak = 0
            var cursor = if (today in played) today else today.minusDays(1)
            while (cursor in played) {
                streak++
                cursor = cursor.minusDays(1)
            }
            var best = 0
            var run = 0
            var previous: LocalDate? = null
            played.sorted().forEach { day ->
                run = if (previous != null && previous!!.plusDays(1) == day) run + 1 else 1
                best = maxOf(best, run)
                previous = day
            }

            val extra = imports.associateWith { build ->
                val historical = sessions.filter { it.buildId == build.id }.sumOf { session ->
                    (minOf(session.end, build.importedAt ?: 0) - session.start).coerceAtLeast(0)
                }
                (build.importedPlayTimeMillis - historical).coerceAtLeast(0)
            }
            val versions = (sessions.groupBy { it.label }
                .map { (label, list) -> VersionTime(label, list.first().loader, list.sumOf { it.millis }) }
                + extra.filterValues { it > 0 }.map { (build, millis) -> VersionTime(build.name, build.loader, millis) })
                .groupBy { it.label }.map { (label, entries) -> VersionTime(label, entries.first().loader, entries.sumOf { it.millis }) }
                .sortedByDescending { it.millis }
                .take(TOP)
            val servers = sessions.filter { it.server != null }
                .groupBy { it.server!!.lowercase() }
                .map { (server, list) -> server to list.sumOf { it.millis } }
                .sortedByDescending { it.second }
                .take(TOP)

            return ActivityStats(
                today = today,
                sessions = sessions,
                days = days,
                totalMillis = sessions.sumOf { it.millis } + extra.values.sum(),
                weekMillis = week,
                lastWeekMillis = lastWeek,
                streak = streak,
                bestStreak = maxOf(best, streak),
                longest = sessions.maxByOrNull { it.millis },
                versions = versions,
                servers = servers,
                importedMillis = extra.values.sum(),
            )
        }

        internal fun split(session: PlaySession, zone: ZoneId): List<Pair<LocalDate, Long>> {
            val parts = ArrayList<Pair<LocalDate, Long>>()
            var from = session.start
            while (from < session.end) {
                val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
                val nextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val until = minOf(session.end, nextDay)
                parts += day to (until - from)
                from = until
            }
            return parts
        }
    }
}
