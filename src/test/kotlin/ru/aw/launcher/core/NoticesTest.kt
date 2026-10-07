package ru.aw.launcher.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.ui.components.formatNoticeDay
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

class NoticesTest {

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun useTempFile() {
        Notices.file = dir.resolve("notifications.json")
        Notices.clear()
        Notices.markSeen()
    }

    @AfterEach
    fun detach() {
        Notices.clear()
        Notices.file = null
    }

    @Test
    fun `newest first, unread counted from the last visit`() {
        Notices.post(NoticeLevel.INFO, "первое", now = 1_000)
        Notices.markSeen()
        Notices.post(NoticeLevel.ERROR, "второе", now = 2_000)
        Notices.post(NoticeLevel.SUCCESS, "третье", now = 3_000)
        val items = Notices.items.value
        assertEquals(listOf("третье", "второе", "первое"), items.map { it.text })
        assertEquals(2, Notices.unread(items, Notices.seen.value))
        Notices.markSeen()
        assertEquals(0, Notices.unread(Notices.items.value, Notices.seen.value))
    }

    @Test
    fun `the same message within a day is counted, not repeated`() {
        val (_, firstRepeated) = Notices.post(NoticeLevel.INFO, "FPS-буста нет", entryKey = "1.21.11#FABRIC", now = 10_000)
        val (second, repeated) = Notices.post(NoticeLevel.INFO, "FPS-буста нет", entryKey = "1.21.11#FABRIC", now = 20_000)
        assertFalse(firstRepeated)
        assertTrue(repeated)
        assertEquals(1, Notices.items.value.size)
        assertEquals(2, second.count)
        Notices.post(NoticeLevel.INFO, "FPS-буста нет", entryKey = "26.3#FABRIC", now = 30_000)
        assertEquals(2, Notices.items.value.size)
    }

    @Test
    fun `history survives a restart and is capped`() {
        repeat(Notices.LIMIT + 5) { Notices.post(NoticeLevel.INFO, "событие $it", now = 1_000L + it) }
        Notices.markSeen()
        Notices.load()
        assertEquals(Notices.LIMIT, Notices.items.value.size)
        assertEquals("событие ${Notices.LIMIT + 4}", Notices.items.value.first().text)
        assertEquals(Notices.items.value.first().id, Notices.seen.value)
    }

    @Test
    fun `resolving drops the buttons but keeps the record`() {
        val (notice, _) = Notices.post(NoticeLevel.ERROR, "моды", actions = listOf(NoticeAction.FIX_MODS), now = 5_000)
        Notices.resolve(notice.id)
        assertTrue(Notices.items.value.single().actions.isEmpty())
        Notices.remove(notice.id)
        assertTrue(Notices.items.value.isEmpty())
    }

    @Test
    fun `day headers read like people talk`() {
        val today = LocalDate.of(2026, 9, 27)
        fun at(date: LocalDate) = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("Сегодня", formatNoticeDay(at(today), today))
        assertEquals("Вчера", formatNoticeDay(at(today.minusDays(1)), today))
        assertEquals("3 сентября", formatNoticeDay(at(LocalDate.of(2026, 9, 3)), today))
        assertEquals("31 декабря 2025", formatNoticeDay(at(LocalDate.of(2025, 12, 31)), today))
    }
}
