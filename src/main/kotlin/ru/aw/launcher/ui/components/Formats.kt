package ru.aw.launcher.ui.components

import java.time.LocalDate
import java.util.Locale


private val MONTHS = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

fun formatReleaseDate(iso: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(iso.take(10)) }.getOrElse { return iso.take(10) }
    val dayMonth = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
    return if (date.year == today.year) dayMonth else "$dayMonth ${date.year}"
}

private fun decimal(value: Double, digits: Int): String =
    String.format(Locale.ROOT, "%.${digits}f", value).replace('.', ',')

fun formatMemory(mb: Int): String = when {
    mb < 1024 -> "$mb МБ"
    mb % 1024 == 0 -> "${mb / 1024} ГБ"
    else -> "${decimal(mb / 1024.0, 1)} ГБ"
}

fun formatTotalMemory(mb: Int): String = "${(mb + 512) / 1024} ГБ"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${decimal(bytes / 1024.0 / 1024 / 1024, 1)} ГБ"
    bytes >= 1024L * 1024 -> "${decimal(bytes / 1024.0 / 1024, 0)} МБ"
    bytes >= 1024 -> "${decimal(bytes / 1024.0, 0)} КБ"
    else -> "$bytes Б"
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}/с"

fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${decimal(count / 1_000_000.0, 1).removeSuffix(",0")} млн"
    count >= 1_000 -> "${count / 1_000} тыс."
    else -> count.toString()
}
