package ru.aw.launcher.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FormatsTest {

    private val today = LocalDate.of(2026, 9, 23)

    @Test
    fun `release dates read like dates, the year only when it is not this one`() {
        assertEquals("15 сен", formatReleaseDate("2026-09-15T12:32:10+00:00", today))
        assertEquals("9 дек 2025", formatReleaseDate("2025-12-09T10:00:00+00:00", today))
        assertEquals("1 мая 2011", formatReleaseDate("2011-05-01T00:00:00+00:00", today))
        assertEquals("garbage", formatReleaseDate("garbage", today))
    }

    @Test
    fun `memory is shown in gigabytes, installed RAM rounded the way it is sold`() {
        assertEquals("4 ГБ", formatMemory(4096))
        assertEquals("1,5 ГБ", formatMemory(1536))
        assertEquals("512 МБ", formatMemory(512))
        assertEquals("32 ГБ", formatTotalMemory(32559))
        assertEquals("16 ГБ", formatTotalMemory(16264))
        assertEquals("1,5 ГБ", formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun `download counts read like a person would say them`() {
        assertEquals("950", formatCount(950))
        assertEquals("12 тыс.", formatCount(12_345))
        assertEquals("1,2 млн", formatCount(1_234_567))
        assertEquals("3 млн", formatCount(3_000_000))
    }

    @Test
    fun `decimals keep the russian comma even without russian locale data`() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("1,5 ГБ", formatMemory(1536))
            assertEquals("171,1 млн", formatCount(171_100_000))
            assertEquals("2,3 ГБ", formatBytes(2_469_606_195L))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
