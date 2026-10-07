package ru.aw.launcher.instance

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.PrettyJson
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.net.DownloadProgress
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

@Serializable
data class LocalBuild(
    val id: String,
    val name: String,
    val versionId: String,
    val loader: LoaderKind,
    val loaderVersion: String? = null,
    val importedPlayTimeMillis: Long = 0,
    val importedAt: Long? = null,
    val lastPlayed: Long? = null,
    val iconFile: String? = null,
    val importedLaunchCount: Long = 0,
)

object LocalBuilds {

    private val idPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    internal var buildsFile: Path = Paths.root.resolve("local-builds.json")

    @Synchronized
    fun list(): List<LocalBuild> {
        val file = buildsFile
        if (!file.exists()) return emptyList()
        return runCatching {
            Json.decodeFromString<List<LocalBuild>>(file.readText())
                .filter { valid(it) }
                .distinctBy { it.id }
        }.onFailure { Log.warn("$file unreadable, ignoring local builds", it) }
            .getOrDefault(emptyList())
    }

    @Synchronized
    @Throws(IOException::class)
    fun create(name: String, versionId: String, loader: LoaderKind, loaderVersion: String? = null): LocalBuild {
        val cleanName = validateName(name)
        if (versionId.isBlank()) throw IOException("Выберите версию Minecraft")
        val existing = list()
        ensureUnique(cleanName, existing)
        val build = LocalBuild(UUID.randomUUID().toString(), cleanName, versionId, loader, loaderVersion)
        dirOf(build).createDirectories()
        save(existing + build)
        return build
    }

    @Throws(IOException::class)
    fun importDirectory(source: Path, name: String, versionId: String, loader: LoaderKind,
                        onProgress: ((DownloadProgress) -> Unit)? = null, onFile: (Path) -> Unit = {}): LocalBuild {
        val profile = ImportProfiles.detect(source)
        return importProfile(profile.copy(name = name, versionId = versionId, loader = loader), onProgress = onProgress, onFile = onFile)
    }

    fun importProfile(profile: ImportProfile, copyHistory: Boolean = true,
                      onProgress: ((DownloadProgress) -> Unit)? = null, onFile: (Path) -> Unit = {}): LocalBuild {
        val gameDir = profile.gameDir.toAbsolutePath().normalize()
        if (!Files.isDirectory(gameDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw IOException("Папка игры не найдена")
        val version = profile.versionId?.takeIf { it.isNotBlank() } ?: throw IOException("Выбери версию Minecraft для ${profile.name}")
        val build = create(uniqueImportName(profile.name), version, profile.loader, profile.loaderVersion).copy(
            importedPlayTimeMillis = profile.playTimeMillis.coerceAtLeast(0),
            importedAt = System.currentTimeMillis(),
            lastPlayed = profile.lastPlayed,
            iconFile = profile.icon?.let { "aw-build-icon.png" },
            importedLaunchCount = profile.launchCount,
        )
        try {
            val target = dirOf(build).toAbsolutePath().normalize()
            if (target.startsWith(gameDir)) throw IOException("Папка импорта должна находиться вне папки исходной сборки")
            copyDirectory(gameDir, target, onFile, copyHistory, profile.icon, onProgress)
            onFile(gameDir)
            synchronized(this) {
                save(list().map { if (it.id == build.id) build else it })
            }
        } catch (failure: Throwable) {
            runCatching { remove(build.id) }
            Storage.deleteTree(dirOf(build))
            throw failure
        }
        return build
    }

    @Synchronized
    @Throws(IOException::class)
    fun rename(id: String, name: String): LocalBuild {
        val cleanName = validateName(name)
        val existing = list()
        val old = existing.firstOrNull { it.id == id } ?: throw IOException("Сборка больше не найдена")
        ensureUnique(cleanName, existing.filterNot { it.id == id })
        val renamed = old.copy(name = cleanName)
        save(existing.map { if (it.id == id) renamed else it })
        return renamed
    }

    @Synchronized
    @Throws(IOException::class)
    fun remove(id: String) {
        val existing = list()
        if (existing.none { it.id == id }) return
        save(existing.filterNot { it.id == id })
    }

    @Synchronized
    fun changeGameVersion(id: String, expectedVersion: String, targetVersion: String): LocalBuild {
        val existing = list()
        val current = existing.firstOrNull { it.id == id } ?: throw IOException("Сборка больше не найдена")
        if (current.versionId != expectedVersion) throw IOException("Версия сборки уже изменилась")
        require(targetVersion.isNotBlank()) { "Выбери версию Minecraft" }
        val updated = current.copy(versionId = targetVersion, loaderVersion = null)
        save(existing.map { if (it.id == id) updated else it })
        return updated
    }

    fun dirOf(build: LocalBuild): Path {
        require(idPattern.matches(build.id)) { "Invalid local build ID" }
        return Settings.buildsDir().resolve("aw-build-${build.id}")
    }

    private fun valid(build: LocalBuild): Boolean = idPattern.matches(build.id) &&
        build.name.isNotBlank() && build.versionId.isNotBlank()

    private fun copyDirectory(source: Path, target: Path, onFile: (Path) -> Unit, copyHistory: Boolean,
                              icon: Path?, onProgress: ((DownloadProgress) -> Unit)?) {
        var totalBytes = 0L
        var totalFiles = 0
        fun skippedDirectory(dir: Path, attrs: BasicFileAttributes) = attrs.isSymbolicLink || excluded(dir.fileName.toString()) ||
            (!copyHistory && dir.parent == source && dir.fileName.toString() in setOf("logs", "crash-reports"))
        fun copiedFile(file: Path, attrs: BasicFileAttributes) = attrs.isRegularFile && !attrs.isSymbolicLink &&
            !excluded(file.fileName.toString()) && (copyHistory || file.parent != source || file.fileName.toString() != "latest-game.log")
        if (onProgress != null) Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                onFile(dir)
                return if (skippedDirectory(dir, attrs)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                onFile(file)
                if (copiedFile(file, attrs)) { totalBytes += attrs.size(); totalFiles++ }
                return FileVisitResult.CONTINUE
            }
        })
        val iconAttributes = icon?.let { Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        if (iconAttributes != null) {
            if (!iconAttributes.isRegularFile) throw IOException("Иконка сборки должна быть обычным файлом")
            totalBytes += iconAttributes.size(); totalFiles++
        }
        var completedBytes = 0L
        var completedFiles = 0
        val started = System.nanoTime()
        var reported = 0L
        fun report(file: String, force: Boolean = false) {
            if (onProgress == null) return
            val now = System.nanoTime()
            if (!force && now - reported < 80_000_000L) return
            reported = now
            val elapsed = (now - started).coerceAtLeast(1)
            onProgress(DownloadProgress(completedFiles, totalFiles, completedBytes, totalBytes,
                (completedBytes.toDouble() * 1_000_000_000 / elapsed).toLong(), file))
        }
        report("", force = true)
        val buffer = ByteArray(256 * 1024)
        fun copy(file: Path, destination: Path, attrs: BasicFileAttributes) {
            onFile(file)
            destination.parent?.createDirectories()
            if (onProgress == null) {
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                return
            }
            Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(destination).use { output ->
                    while (true) {
                        onFile(file)
                        val length = input.read(buffer)
                        if (length < 0) break
                        output.write(buffer, 0, length)
                        completedBytes += length
                        report(file.fileName.toString())
                    }
                }
            }
            Files.getFileAttributeView(destination, BasicFileAttributeView::class.java)
                .setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime())
            completedFiles++
            report(file.fileName.toString())
        }
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                onFile(dir)
                if (skippedDirectory(dir, attrs)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                onFile(file)
                if (copiedFile(file, attrs)) copy(file, target.resolve(source.relativize(file)), attrs)
                return FileVisitResult.CONTINUE
            }
        })
        if (icon != null && iconAttributes != null) copy(icon, target.resolve("aw-build-icon.png"), iconAttributes)
        // Source files may change while importing; finish with the actual copied totals.
        totalBytes = completedBytes; totalFiles = completedFiles
        report("", force = true)
    }

    private fun validateName(name: String): String {
        val clean = name.trim()
        if (clean.isBlank()) throw IOException("Укажи название сборки")
        if (clean.length > 48) throw IOException("Название сборки должно быть короче 49 символов")
        return clean
    }

    private fun ensureUnique(name: String, builds: List<LocalBuild>) {
        if (builds.any { it.name.equals(name, ignoreCase = true) }) {
            throw IOException("Сборка с таким названием уже есть")
        }
    }

    private fun excluded(name: String): Boolean = name.lowercase() in setOf(
        "accounts.json", "launcher_accounts.json", "launcher_msa_credentials.bin", "launcher_profiles.json",
        ".git", "aw-pack.json",
    ) || name.startsWith("_IAS_ACCOUNTS", ignoreCase = true)

    @Synchronized
    private fun uniqueImportName(name: String): String {
        val clean = validateName(name.take(48))
        val names = list().map { it.name.lowercase() }.toSet()
        if (clean.lowercase() !in names) return clean
        for (index in 2..10000) {
            val suffix = " ($index)"
            val candidate = clean.take(48 - suffix.length) + suffix
            if (candidate.lowercase() !in names) return candidate
        }
        throw IOException("Не удалось подобрать название сборки")
    }

    private fun save(builds: List<LocalBuild>) {
        runCatching { buildsFile.writeAtomically(PrettyJson.encodeToString(builds)) }
            .onFailure { Log.error("could not save local builds", it) }
            .getOrElse { throw IOException("Не удалось сохранить список сборок", it) }
    }
}
