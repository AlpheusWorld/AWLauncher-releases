package ru.aw.launcher.update

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Shell
import ru.aw.launcher.finishBackgroundShutdown
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.io.path.exists

class UpdateSplashTest {
    @Test fun nativeHandoffPreservesPathsAndArgumentsWithoutShellParsing() {
        val app=Path.of("C:/Users/O'Brien/Launcher/AWLauncher.exe")
        val assets=Path.of("C:/Users/O'Brien/Cache/update")
        val arguments="/i "+34.toChar()+"C:/Users/O'Brien/update.msi"+34.toChar()+" /qn /norestart"
        val command=UpdateSplash.command(assets.resolve("AWLauncher.Update.exe"),4242,"msiexec.exe",arguments,app,assets,"1.0.26",assets.resolve("ready"))
        assertEquals("AWLauncher.Update.exe",Path.of(command.first()).fileName.toString())
        assertEquals(listOf("--run-base64","4242","msiexec.exe",Base64.getEncoder().encodeToString(arguments.toByteArray(Charsets.UTF_8)),app.toString(),assets.toString(),"1.0.26",assets.resolve("ready").toString()),command.drop(1))
    }

    @Test fun optionalBackgroundShutdownCannotHoldUpdaterIndefinitely() {
        val release=CountDownLatch(1)
        try { assertFalse(finishBackgroundShutdown(50){release.await()}) }
        finally { release.countDown() }
        assertTrue(finishBackgroundShutdown(1000){})
    }

    @Test fun nativeUpdaterSupportsMinimizeDragDpiAndRecovery(@TempDir root: Path) {
        assumeTrue(Shell.isWindows)
        val dir=Files.createDirectories(root.resolve("Player O'Brien"))
        val executable=UpdateSplash.prepare(dir)
        assertTrue(executable.exists())
        val smoke=dir.resolve("Smoke.cs")
        javaClass.getResourceAsStream("/update/SplashSmoke.cs")!!.use { Files.copy(it,smoke) }
        val check=dir.resolve("SplashSmoke.exe")
        val compiler=Path.of(System.getenv("SystemRoot"),"Microsoft.NET","Framework64","v4.0.30319","csc.exe")
        val args=listOf("/nologo","/target:exe","/platform:x64","/main:SplashSmoke","/out:"+check,
            "/win32manifest:"+dir.resolve("splash.manifest"),"/win32icon:"+dir.resolve("app.ico"),
            "/reference:System.Windows.Forms.dll","/reference:System.Drawing.dll",dir.resolve("splash.cs").toString(),smoke.toString())
        val compile="& "+Shell.psLiteral(compiler.toString())+" @("+args.joinToString(", ",transform=Shell::psLiteral)+")\nexit \$LASTEXITCODE"
        assertEquals(0,Shell.runHidden(Shell.powershell(compile),60_000))
        val run="& "+Shell.psLiteral(check.toString())+" "+Shell.psLiteral(dir.toString())+" > "+Shell.psLiteral(dir.resolve("test.log").toString())+" 2>&1\nexit \$LASTEXITCODE"
        assertEquals(0,Shell.runHidden(Shell.powershell(run),60_000),dir.resolve("test.log").toString())
        for(name in listOf("window-150.png","failure-150.png","render-100.png","render-150.png","render-200.png"))assertTrue(dir.resolve(name).exists())
    }
}
