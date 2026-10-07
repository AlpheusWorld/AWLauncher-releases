package ru.aw.launcher.runtime

import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.RuleEnvironment
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import ru.aw.launcher.net.Http
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

object JavaManager {

    private const val ALL_URL =
        "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"

    private const val MARKER = ".aw-runtime"

    private fun runtimeDir(component: String): Path = Paths.runtimes.resolve(component)

    fun executableIn(dir: Path, env: RuleEnvironment = RuleEnvironment.current): Path = when (env.osName) {
        "windows" -> dir.resolve("bin/javaw.exe").takeIf { it.exists() } ?: dir.resolve("bin/java.exe")
        "osx" -> dir.resolve("jre.bundle/Contents/Home/bin/java").takeIf { it.exists() } ?: dir.resolve("bin/java")
        else -> dir.resolve("bin/java")
    }

    fun isInstalled(component: String): Boolean {
        val dir = runtimeDir(component)
        return dir.resolve(MARKER).exists() && executableIn(dir).exists()
    }

    suspend fun ensure(
        component: String,
        forceRecheck: Boolean = false,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Path {
        val dir = runtimeDir(component)
        if (!forceRecheck && isInstalled(component)) {
            Log.debug("runtime $component already installed")
            return executableIn(dir)
        }

        val entry = findEntry(component)
            ?: throw IOException("Mojang ships no '$component' runtime for ${JavaPlatform.key()}")
        val manifestRef = entry.manifest
            ?: throw IOException("runtime entry for $component has no manifest")

        Log.info("installing runtime $component (${entry.version?.name ?: "unknown"})")

        val manifest = Json.decodeFromString<RuntimeManifest>(Http.getString(manifestRef.url))

        manifest.files.filterValues { it.type == "directory" }.keys.forEach {
            dir.resolve(it).createDirectories()
        }

        val tasks = manifest.files.mapNotNull { (relative, file) ->
            val raw = file.downloads?.raw ?: return@mapNotNull null
            if (file.type != "file") return@mapNotNull null
            DownloadTask(
                url = raw.url,
                dest = dir.resolve(relative),
                sha1 = raw.sha1,
                size = raw.size,
                label = relative,
            )
        }

        Downloader().run(tasks, onProgress)

        manifest.files.forEach { (relative, file) ->
            val target = dir.resolve(relative)
            when (file.type) {
                "link" -> file.target?.let { linkTarget -> createLink(target, linkTarget) }
                "file" -> if (file.executable) markExecutable(target)
            }
        }

        val exe = executableIn(dir)
        if (!exe.exists()) throw IOException("runtime $component installed but $exe is missing")

        dir.resolve(MARKER).writeAtomically(manifestRef.sha1.orEmpty())
        Log.info("runtime $component ready at $exe")
        return exe
    }

    private fun findEntry(component: String): RuntimeEntry? {
        val all = runCatching {
            Json.decodeFromString<Map<String, Map<String, List<RuntimeEntry>>>>(Http.getString(ALL_URL))
        }.onFailure { Log.warn("could not read java runtime index: ${it.message}") }.getOrNull() ?: return null

        for (platform in JavaPlatform.fallbacks()) {
            val entry = all[platform]?.get(component)?.firstOrNull { it.manifest != null }
            if (entry != null) {
                if (platform != JavaPlatform.key()) {
                    Log.info("no native $component for ${JavaPlatform.key()}, using $platform build")
                }
                return entry
            }
        }
        return null
    }

    private fun createLink(link: Path, target: String) {
        runCatching {
            link.parent?.createDirectories()
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, Path.of(target))
        }.onFailure {
            Log.debug("symlink $link -> $target not created: ${it.message}")
        }
    }

    private fun markExecutable(path: Path) {
        runCatching {
            val view = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
                ?: return
            val perms = view.readAttributes().permissions()
            perms += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            perms += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
            perms += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
            view.setPermissions(perms)
        }
    }

    fun findLocal(majorVersion: Int): Path? {
        val candidates = buildList {
            System.getenv("JAVA_HOME")?.let { add(Path.of(it)) }
            listOf(
                System.getenv("ProgramFiles") to listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto"),
                System.getenv("ProgramFiles(x86)") to listOf("Java"),
                System.getenv("LOCALAPPDATA") to listOf("Programs/Eclipse Adoptium"),
            ).forEach { (base, vendors) ->
                if (base.isNullOrBlank()) return@forEach
                vendors.forEach { vendor ->
                    val dir = Path.of(base).resolve(vendor)
                    if (dir.isDirectory()) {
                        runCatching { addAll(dir.listDirectoryEntries().filter { it.isDirectory() }) }
                    }
                }
            }
        }

        return candidates.firstNotNullOfOrNull { home ->
            val exe = executableIn(home)
            if (!exe.isRegularFile()) return@firstNotNullOfOrNull null
            if (majorOf(home) != majorVersion) return@firstNotNullOfOrNull null
            Log.info("using local JDK for Java $majorVersion: $home")
            exe
        }
    }

    fun customExecutable(raw: String, requiredMajor: Int? = null): Path {
        val path = try { Path.of(raw.trim().removeSurrounding("\"")).toAbsolutePath().normalize() }
        catch (e: Exception) { throw IOException("Некорректный путь к Java", e) }
        val executable = if (path.isDirectory()) executableIn(path) else path
        if (!executable.isRegularFile() || executable.fileName.toString().lowercase() !in setOf("java", "java.exe", "javaw.exe"))
            throw IOException("Выбери java.exe, javaw.exe или папку установленной Java")
        val major = executable.parent?.parent?.let(::majorOf)
            ?: throw IOException("Не удалось определить версию Java: рядом с папкой bin должен быть файл release")
        if (requiredMajor != null && major < requiredMajor) throw IOException("Для этой сборки нужна Java $requiredMajor или новее, выбрана Java $major")
        return executable
    }

    private fun majorOf(home: Path): Int? = runCatching {
        val release = home.resolve("release")
        if (!release.isRegularFile()) return@runCatching null
        val line = release.readText().lineSequence().firstOrNull { it.startsWith("JAVA_VERSION=") }
            ?: return@runCatching null
        val raw = line.substringAfter('=').trim().trim('"')
        if (raw.startsWith("1.")) raw.split('.').getOrNull(1)?.toIntOrNull()
        else raw.takeWhile { it.isDigit() }.toIntOrNull()
    }.getOrNull()
}
