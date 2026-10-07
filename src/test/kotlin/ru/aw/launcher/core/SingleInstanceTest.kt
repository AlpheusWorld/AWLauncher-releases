package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories

class SingleInstanceTest {

    @Test
    fun `a second start hands its arguments to the running launcher`() {
        val dir = Files.createTempDirectory(Paths.cache.createDirectories(), "instance")
        val running = SingleInstance(dir)
        try {
            assertEquals(SingleInstance.Role.PRIMARY, running.start(emptyList()))
            assertEquals(SingleInstance.Role.HANDED_OVER, SingleInstance(dir).start(listOf("--play", "1.20.1")))
            assertEquals(listOf("--play", "1.20.1"), running.requests.tryReceive().getOrNull())
        } finally {
            running.close()
        }

        val next = SingleInstance(dir)
        try {
            assertEquals(SingleInstance.Role.PRIMARY, next.start(emptyList()))
        } finally {
            next.close()
        }
    }
}
