package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.exists

@EnabledOnOs(OS.WINDOWS)
class ShellTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a hidden process reports its exit code`() {
        assertEquals(3, Shell.runHidden("cmd.exe /c exit 3"))
    }

    @Test
    fun `a powershell script survives any quoting`() {
        val script = "if (${Shell.psLiteral("it's \"quoted\"")} -eq 'it''s \"quoted\"') { exit 7 } else { exit 1 }"
        assertEquals(7, Shell.runHidden(Shell.powershell(script)))
    }

    @Test
    fun `a shortcut is written, even with a quote in its name`() {
        val link = dir.resolve("Minecraft it's 26.3.lnk")
        val notepad = Path.of(System.getenv("WINDIR") ?: "C:\\Windows", "notepad.exe")
        Shortcuts.create(link, notepad, PlayArguments.of("26.3", LoaderKind.FABRIC), "test")
        assertTrue(link.exists())
    }

    @Test
    fun `play arguments read back what a shortcut writes`() {
        val written = PlayArguments.of("3D Shareware v1.34", LoaderKind.VANILLA)
        assertEquals("--play \"3D Shareware v1.34\"", written)
        assertEquals(
            PlayRequest("26.3", LoaderKind.FABRIC),
            PlayArguments.parse(arrayOf("--play", "26.3", "--loader", "fabric")),
        )
        assertEquals(PlayRequest("1.20.1", LoaderKind.VANILLA), PlayArguments.parse(arrayOf("--play", "1.20.1")))
        assertEquals(null, PlayArguments.parse(emptyArray()))
    }

    @Test
    fun `a pack shortcut names the pack as well`() {
        assertEquals("--play \"26.2\" --loader fabric --pack \"cobblemon\"", PlayArguments.of("26.2", LoaderKind.FABRIC, "cobblemon"))
        assertEquals(
            PlayRequest("26.2", LoaderKind.FABRIC, "cobblemon"),
            PlayArguments.parse(arrayOf("--play", "26.2", "--loader", "fabric", "--pack", "cobblemon")),
        )
    }
}
