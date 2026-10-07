package ru.aw.launcher.core

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import ru.aw.launcher.meta.LoaderKind
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists

object Shortcuts {

    val isAvailable: Boolean get() = Shell.isWindows && Shell.appExecutable != null

    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    fun createOnDesktop(title: String, versionId: String, loader: LoaderKind, pack: String? = null, build: String? = null): Path {
        val exe = Shell.appExecutable
            ?: throw IOException("Ярлыки создаются только в установленном лаунчере, не при запуске из IDE")
        val link = desktop().resolve(title.replace(ILLEGAL, "_").trim() + ".lnk")
        create(link, exe, PlayArguments.of(versionId, loader, pack, build), "AWLauncher: $title")
        Log.info("desktop shortcut created: $link")
        return link
    }

    fun appOnDesktop(app: Path): Path = desktop().resolve(app.fileName.toString().substringBeforeLast('.') + ".lnk")

    internal fun create(link: Path, target: Path, arguments: String, description: String) {
        val script = listOf(
            "\$link = (New-Object -ComObject WScript.Shell).CreateShortcut(${Shell.psLiteral(link.toString())})",
            "\$link.TargetPath = ${Shell.psLiteral(target.toString())}",
            "\$link.Arguments = ${Shell.psLiteral(arguments)}",
            "\$link.WorkingDirectory = ${Shell.psLiteral(target.parent.toString())}",
            "\$link.IconLocation = ${Shell.psLiteral("$target,0")}",
            "\$link.Description = ${Shell.psLiteral(description)}",
            "\$link.Save()",
        ).joinToString("\n")

        val exit = Shell.runHidden(Shell.powershell(script), waitMs = 20_000)
        if (exit != 0 || !link.exists()) {
            throw IOException("Не удалось создать ярлык (код ${exit ?: "нет"})")
        }
    }

    private fun desktop(): Path =
        runCatching { Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Desktop)) }
            .getOrElse { Path.of(System.getProperty("user.home"), "Desktop") }
}

data class PlayRequest(val versionId: String, val loader: LoaderKind, val pack: String? = null, val build: String? = null)

object PlayArguments {

    fun of(versionId: String, loader: LoaderKind, pack: String? = null, build: String? = null): String =
        "--play \"$versionId\"" +
            (if (loader.isModded) " --loader ${loader.name.lowercase()}" else "") +
            (if (pack != null) " --pack \"$pack\"" else "") +
            (if (build != null) " --build \"$build\"" else "")

    fun parse(args: Array<String>): PlayRequest? {
        val version = args.valueAfter("--play")?.takeIf { it.isNotBlank() } ?: return null
        val loaderName = args.valueAfter("--loader")
        val loader = LoaderKind.entries.firstOrNull { it.name.equals(loaderName, ignoreCase = true) }
            ?: LoaderKind.VANILLA
        return PlayRequest(
            version,
            loader,
            args.valueAfter("--pack")?.takeIf { it.isNotBlank() },
            args.valueAfter("--build")?.takeIf { it.isNotBlank() },
        )
    }

    private fun Array<String>.valueAfter(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
}
