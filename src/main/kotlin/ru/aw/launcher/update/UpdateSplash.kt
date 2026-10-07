package ru.aw.launcher.update

import ru.aw.launcher.core.Shell
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

internal object UpdateSplash {

    private val ASSETS = listOf("brand/wordmark.png", "fonts/Onest-Regular.ttf", "fonts/Onest-Bold.ttf", "update/splash.cs")

    fun prepare(dir: Path) {
        dir.createDirectories()
        ASSETS.forEach { resource ->
            val stream = UpdateSplash::class.java.getResourceAsStream("/$resource") ?: throw IOException("В лаунчере нет $resource")
            stream.use { Files.copy(it, dir.resolve(resource.substringAfterLast('/')), StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    fun script(launcher: Long, installer: String, arguments: String, app: Path, assets: Path, version: String, ready: Path): String {
        val install = if (arguments.isEmpty()) {
            "Start-Process -FilePath \$installer -Wait"
        } else {
            "Start-Process -FilePath \$installer -ArgumentList \$arguments.Replace('/qn', '/passive') -Wait"
        }
        val run = listOf(
            "\$launcher", "\$installer", "\$arguments", "\$app", "\$assets",
            Shell.psLiteral(version),
            Shell.psLiteral("Обновляю AWLauncher до $version"),
            Shell.psLiteral("Обновление не встало (код {0}), открываю прежнюю версию"),
            "\$ready",
        ).joinToString(", ")
        return listOf(
            "\$launcher = $launcher",
            "\$installer = ${Shell.psLiteral(installer)}",
            "\$arguments = ${Shell.psLiteral(arguments)}",
            "\$app = ${Shell.psLiteral(app.toString())}",
            "\$assets = ${Shell.psLiteral(assets.toString())}",
            "\$ready = ${Shell.psLiteral(ready.toString())}",
            "try {",
            "    Add-Type -Path (Join-Path \$assets 'splash.cs') -ReferencedAssemblies System.Windows.Forms, System.Drawing",
            "    [void][AwUpdateSplash]::Run($run)",
            "} catch {",
            "    Wait-Process -Id \$launcher -Timeout 60 -ErrorAction SilentlyContinue",
            "    $install",
            "    Start-Process -FilePath \$app",
            "}",
        ).joinToString("\n")
    }
}
