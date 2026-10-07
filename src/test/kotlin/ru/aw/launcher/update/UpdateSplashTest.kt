package ru.aw.launcher.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Shell
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize

class UpdateSplashTest {

    @Test
    fun `the script quotes every path and falls back to the classic installer`() {
        val script = UpdateSplash.script(
            launcher = 4242,
            installer = "msiexec.exe",
            arguments = "/i \"C:\\Users\\O'Brien\\AWLauncher-1.6.1.msi\" /qn /norestart",
            app = Path.of("C:\\Users\\O'Brien\\AppData\\Local\\AWLauncher\\AWLauncher.exe"),
            assets = Path.of("C:\\Users\\O'Brien\\.aw\\cache\\update"),
            version = "1.6.1",
            ready = Path.of("C:\\Users\\O'Brien\\.aw\\cache\\update\\ready"),
        )
        val lines = script.lines()
        assertEquals("\$launcher = 4242", lines[0])
        assertEquals("\$app = 'C:\\Users\\O''Brien\\AppData\\Local\\AWLauncher\\AWLauncher.exe'", lines[3])
        assertTrue("'Обновляю AWLauncher до 1.6.1'" in script)
        assertTrue("-ArgumentList \$arguments.Replace('/qn', '/passive') -Wait" in script)
        assertTrue(lines.last() == "}")
    }

    @Test
    fun `the splash compiles in windows powershell and draws itself`(@TempDir dir: Path) {
        assumeTrue(Shell.isWindows)
        UpdateSplash.prepare(dir)
        val png = dir.resolve("splash.png")
        val command = listOf(
            "\$ErrorActionPreference = 'Stop'",
            "Add-Type -Path ${Shell.psLiteral(dir.resolve("splash.cs").toString())} -ReferencedAssemblies System.Windows.Forms, System.Drawing",
            "[AwUpdateSplash]::Render(${Shell.psLiteral(dir.toString())}, '1.6.1', 'Обновляю AWLauncher до 1.6.1', ${Shell.psLiteral(png.toString())}, 1.25, \$false)",
        ).joinToString("\n")

        assertEquals(0, Shell.runHidden(Shell.powershell(command), waitMs = 60_000))
        assertTrue(png.exists() && png.fileSize() > 10_000)
    }
}
