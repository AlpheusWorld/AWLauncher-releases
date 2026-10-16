package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText

class LinuxDesktopTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `desktop argument quoting preserves spaces and cannot introduce field codes or new keys`() {
        assertEquals("\"pack %%f with spaces\"", Shortcuts.desktopArgument("pack %f with spaces"))
        assertEquals("\"quote\\\\\"name\"", Shortcuts.desktopArgument("quote\"name"))
        assertThrows(IllegalArgumentException::class.java) { Shortcuts.desktopArgument("line\nExec=bad") }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `desktop entries are executable and accepted by the desktop parser`() {
        val app = dir.resolve("launcher with spaces").resolve("AWLauncher")
        Files.createDirectories(app.parent)
        Files.writeString(app, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(app, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        val entry = dir.resolve("Minecraft.desktop")
        Shortcuts.writeDesktopEntry(entry, app, "Title\nExec=not-a-command", listOf("--play", "26.3", "--build", "pack %f with spaces"))
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(entry))
        assertTrue("Name=Title\\nExec=not-a-command" in entry.readText())
        val validation = ProcessBuilder("desktop-file-validate", entry.toString()).redirectErrorStream(true).start()
        val output = validation.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, validation.waitFor(), output)
    }
}
