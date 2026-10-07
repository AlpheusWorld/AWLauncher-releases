package ru.aw.launcher.core

import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.meta.LoaderRepository
import java.awt.Desktop
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

object Storage {

    val trashAvailable: Boolean by lazy {
        runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) }
            .getOrDefault(false)
    }

    fun versionIdsOf(gameVersion: String, loader: LoaderKind, knownVersions: Collection<String>): List<String> {
        val known = knownVersions.toSet() + gameVersion
        return listDirs(Paths.versions).map { it.name }.filter { profileOwner(it, known) == (gameVersion to loader) }
    }

    fun deleteVersionFiles(versionIds: List<String>) {
        versionIds.forEach { id ->
            deleteTree(Paths.versionDir(id))
            deleteTree(Paths.nativesDir(id))
        }
    }

    fun deleteGameDir(gameDir: Path, moveToTrash: (Path) -> Boolean = ::systemTrash): Boolean {
        if (!gameDir.exists()) return false
        InstanceStore.forget(gameDir)
        if (!trashAvailable) {
            deleteTree(gameDir)
            return false
        }
        val trashed = runCatching { moveToTrash(gameDir) }
            .onFailure { Log.warn("moveToTrash failed for $gameDir: ${it.message}") }
            .getOrDefault(false)
        if (!trashed) {
            throw IOException(
                "Папку сборки не удалось перенести в корзину — наверное, какой-то файл в ней открыт " +
                    "в другой программе. Папка не тронута: закройте программу и попробуйте ещё раз."
            )
        }
        return true
    }

    fun discard(path: Path) {
        val trashed = trashAvailable && runCatching { systemTrash(path) }.getOrDefault(false)
        if (!trashed) deleteTree(path)
    }

    private fun systemTrash(path: Path): Boolean = Desktop.getDesktop().moveToTrash(path.toFile())

    fun hasWorlds(gameDir: Path): Boolean = runCatching {
        val saves = gameDir.resolve("saves")
        saves.isDirectory() && saves.listDirectoryEntries().any { it.isDirectory() }
    }.getOrDefault(false)

    fun sizeOf(path: Path): Long {
        if (!path.exists()) return 0
        var total = 0L
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return total
    }

    internal fun profileOwner(id: String, known: Set<String>): Pair<String, LoaderKind>? {
        fun gameVersionAtEnd(rest: String): String? =
            known.filter { rest.endsWith("-$it") }.maxByOrNull { it.length }
                ?: rest.substringAfter('-', "").takeIf { it.isNotEmpty() }

        return when {
            id.startsWith("fabric-loader-") ->
                gameVersionAtEnd(id.removePrefix("fabric-loader-"))?.let { it to LoaderKind.FABRIC }
            id.startsWith("quilt-loader-") ->
                gameVersionAtEnd(id.removePrefix("quilt-loader-"))?.let { it to LoaderKind.QUILT }
            id.startsWith("neoforge-") ->
                LoaderRepository.neoForgeGameVersion(id.removePrefix("neoforge-"))?.let { it to LoaderKind.NEOFORGE }
            id.contains("-forge", ignoreCase = true) ->
                id.substring(0, id.indexOf("-forge", ignoreCase = true)).takeIf { it.isNotEmpty() }
                    ?.let { it to LoaderKind.FORGE }
            else -> id to LoaderKind.VANILLA
        }
    }

    private fun listDirs(dir: Path): List<Path> =
        runCatching { dir.listDirectoryEntries().filter { it.isDirectory() } }.getOrDefault(emptyList())

    internal fun deleteTree(path: Path) {
        runCatching {
            val root = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (root.isLink) {
                Files.delete(path)
                return
            }
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isLink) {
                        Files.delete(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { if (it !is NoSuchFileException) Log.warn("could not delete $path: ${it.message}") }
    }

    private val BasicFileAttributes.isLink: Boolean get() = isSymbolicLink || isOther
}
