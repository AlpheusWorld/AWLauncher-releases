package ru.aw.launcher.update

import ru.aw.launcher.core.Shell
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

internal object UpdateSplash {
    private val ASSETS = listOf("brand/wordmark.png", "fonts/Onest-Regular.ttf", "fonts/Onest-Bold.ttf", "update/splash.cs", "update/splash.manifest", "update/app.ico")

    fun prepare(dir: Path): Path {
        dir.createDirectories()
        ASSETS.forEach { resource ->
            val stream = UpdateSplash::class.java.getResourceAsStream("/" + resource) ?: throw IOException("В лаунчере нет " + resource)
            stream.use { Files.copy(it, dir.resolve(resource.substringAfterLast('/')), StandardCopyOption.REPLACE_EXISTING) }
        }
        val compiler = Path.of(System.getenv("SystemRoot") ?: "C:/Windows", "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe")
        if (!Files.isRegularFile(compiler)) throw IOException("Не найден компонент .NET для окна обновления")
        val executable = dir.resolve("AWLauncher.Update.exe")
        val arguments = listOf("/nologo", "/target:winexe", "/platform:x64", "/optimize+", "/main:AwUpdateSplash",
            "/out:" + executable, "/win32icon:" + dir.resolve("app.ico"), "/win32manifest:" + dir.resolve("splash.manifest"),
            "/reference:System.Windows.Forms.dll", "/reference:System.Drawing.dll", dir.resolve("splash.cs").toString())
        val script = "& " + Shell.psLiteral(compiler.toString()) + " @(" + arguments.joinToString(", ", transform = Shell::psLiteral) +
            ") *> " + Shell.psLiteral(dir.resolve("compile.log").toString()) + "\nexit \$LASTEXITCODE"
        if (Shell.runHidden(Shell.powershell(script), waitMs = 60_000) != 0 || !Files.isRegularFile(executable))
            throw IOException("Не удалось подготовить окно обновления. Журнал: " + dir.resolve("compile.log"))
        return executable
    }

    fun command(executable: Path, launcher: Long, installer: String, arguments: String, app: Path, assets: Path, version: String, ready: Path): List<String> =
        listOf(executable.toString(), "--run", launcher.toString(), installer, arguments, app.toString(), assets.toString(), version, ready.toString())
}
