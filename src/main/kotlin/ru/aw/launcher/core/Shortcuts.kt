package ru.aw.launcher.core

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import ru.aw.launcher.meta.LoaderKind
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.isRegularFile
import kotlin.io.path.exists

object Shortcuts {

    val isAvailable: Boolean get() = (Shell.isWindows || Shell.isLinux) && Shell.appExecutable != null

    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    fun createOnDesktop(title: String, versionId: String, loader: LoaderKind, pack: String? = null, build: String? = null): Path {
        val exe = Shell.appExecutable
            ?: throw IOException("Ярлыки создаются только в установленном лаунчере, не при запуске из IDE")
        if (Shell.isLinux) {
            val link = desktop().resolve(title.replace(ILLEGAL, "_").trim().ifBlank { "Minecraft" } + ".desktop")
            writeDesktopEntry(link, exe, title, buildList {
                addAll(listOf("--play", versionId))
                if (loader.isModded) addAll(listOf("--loader", loader.name.lowercase()))
                if (pack != null) addAll(listOf("--pack", pack))
                if (build != null) addAll(listOf("--build", build))
            })
            return link
        }
        val link = desktop().resolve(title.replace(ILLEGAL, "_").trim() + ".lnk")
        create(link, exe, PlayArguments.of(versionId, loader, pack, build), "AWLauncher: $title")
        Log.info("desktop shortcut created: $link")
        return link
    }

    fun appOnDesktop(app: Path): Path = desktop().resolve(app.fileName.toString().substringBeforeLast('.') + if (Shell.isLinux) ".desktop" else ".lnk")

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

    internal fun desktopArgument(value: String): String {
        require(value.none { it.isISOControl() }) { "Некорректный аргумент ярлыка" }
        return "\"" + value.replace("\\", "\\\\\\\\").replace("\"", "\\\\\"")
            .replace("\u0024", "\\\\\u0024").replace("`", "\\\\`").replace("%", "%%") + "\""
    }

    internal fun writeDesktopEntry(link: Path, app: Path, title: String, arguments: List<String>) {
        require('=' !in app.toString()) { "Некорректная папка лаунчера для ярлыка" }
        fun value(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        val icon = app.parent.parent?.resolve("lib/AWLauncher.png")?.takeIf { it.isRegularFile() }
        val entry = buildList {
            add("[Desktop Entry]")
            add("Type=Application")
            add("Name=${value(title)}")
            add("Exec=${(listOf(app.toAbsolutePath().toString()) + arguments).joinToString(" ", transform = ::desktopArgument)}")
            add("Path=${value(app.parent.toAbsolutePath().toString())}")
            if (icon != null) add("Icon=${value(icon.toAbsolutePath().toString())}")
            add("Terminal=false")
            add("Categories=Game;")
        }.joinToString("\n", postfix = "\n")
        link.parent.createDirectories()
        link.writeAtomically(entry)
        Files.setPosixFilePermissions(link, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        Log.info("desktop shortcut created: $link")
    }

    private fun desktop(): Path {
        val home = Path.of(System.getProperty("user.home"))
        if (Shell.isLinux) return runCatching {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?.takeIf { it.isAbsolute } ?: home.resolve(".config")
            val setting = config.resolve("user-dirs.dirs").readLines().firstOrNull { it.startsWith("XDG_DESKTOP_DIR=") }
                ?.substringAfter('=').orEmpty().trim().removeSurrounding("\"")
                .replace("\\\"", "\"").replace("\\\\", "\\").replace("\$HOME", home.toString())
            Path.of(setting).takeIf { setting.isNotBlank() && it.isAbsolute } ?: home.resolve("Desktop")
        }.getOrElse { home.resolve("Desktop") }
        return runCatching { Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Desktop)) }
            .getOrElse { home.resolve("Desktop") }
    }
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
