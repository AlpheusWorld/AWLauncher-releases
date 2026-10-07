package ru.aw.launcher.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.writeText

class CrashHintsTest {

    @Test
    fun `quilt on a too new java points to fabric`() {
        val reason = CrashHints.reason("IllegalArgumentException: Unsupported class file major version 69", LoaderKind.QUILT, "26.3")
        assertTrue(reason!!.contains("Quilt пока не поддерживает Minecraft 26.3"))
        assertTrue(reason.contains("Fabric"))
    }

    @Test
    fun `memory problems are told apart`() {
        assertTrue(CrashHints.reason("java.lang.OutOfMemoryError: Java heap space", LoaderKind.FABRIC, "1.21.1")!!.contains("не хватило памяти"))
        assertTrue(CrashHints.reason("Could not reserve enough space for 8388608KB object heap", LoaderKind.VANILLA, "1.21.1")!!.contains("Уменьши память"))
    }

    @Test
    fun `unknown crash falls back to the exit code`(@TempDir dir: Path) {
        val log = dir.resolve("latest-game.log").apply { writeText("something unexpected") }
        assertNull(CrashHints.reason("something unexpected", LoaderKind.FORGE, "1.20.1"))
        assertEquals("Игра закрылась с ошибкой (код 1). Подробности — в логах.", CrashHints.explain(log, LoaderKind.FORGE, "1.20.1", 1))
    }

    @Test
    fun `missing log still explains`() {
        assertTrue(CrashHints.explain(null, LoaderKind.VANILLA, "1.21.1", -1).contains("код -1"))
    }
}
