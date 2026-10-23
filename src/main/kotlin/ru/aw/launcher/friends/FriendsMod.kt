package ru.aw.launcher.friends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.core.toHex
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.mods.ModCompat
import ru.aw.launcher.mods.ModManager
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.net.DownloadProgress
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
private data class FriendsManagedMod(val fileName: String, val sha1: String, val gameVersion: String, val loader: LoaderKind)

/** Uses the established mod resolver so required libraries and hashes are checked as usual. */
object FriendsMod {
    const val PROJECT = "qANg5Jrr"
    private val ids = setOf("e4mc","e4mc_minecraft")

    suspend fun prepare(gameDir: Path, loader: LoaderKind, version: String,
                        onProgress: (DownloadProgress) -> Unit, onNotice: (String) -> Unit) = withContext(Dispatchers.IO) {
        val dir = ModManager.modsDir(gameDir)
        val marker = gameDir.resolve("config/aw-friends-managed.json")
        val record = runCatching {
            if (Files.isRegularFile(marker,NOFOLLOW_LINKS) && Files.size(marker) <= 4096)
                Json.decodeFromString<FriendsManagedMod>(Files.readString(marker)) else null
        }.getOrNull()
        val current = if (record != null) InstanceStore.get(gameDir).catalogMods.firstOrNull { it.projectId == PROJECT } else null
        val updated = current?.takeIf { it.fileName.startsWith("mods/") && !it.fileName.removePrefix("mods/").contains('/') && !it.fileName.contains('\\') }
            ?.let { FriendsManagedMod(it.fileName.removePrefix("mods/"),it.sha1,version,loader) } ?: record
        val managed = updated?.takeIf { runCatching { it.fileName == Path.of(it.fileName).fileName.toString() && it.fileName != "." && it.fileName != ".." && it.sha1.matches(Regex("[a-f0-9]{40}")) }.getOrDefault(false) }
        if (!Settings.current.friendsEnabled) {
            managed?.let { owned ->
                val file = dir.resolve(owned.fileName)
                if (Files.isRegularFile(file,NOFOLLOW_LINKS) && sha1Of(file) == owned.sha1) {
                    val disabled = file.resolveSibling(file.fileName.toString() + ".disabled")
                    if (Files.exists(disabled,NOFOLLOW_LINKS)) throw IOException("Не удалось отключить мод друзей: рядом уже есть выключенная копия")
                    Files.move(file,disabled)
                }
            }
            return@withContext
        }
        if (!loader.isModded) return@withContext
        if (isPresent(gameDir)) return@withContext
        try {
            val available = Modrinth.versions(PROJECT,ModManager.loadersFor(loader),version)
            val selected = Modrinth.pick(available.filter { it.primaryFile != null })
            if (selected == null) {
                onNotice("Для ${loader.label} $version пока нет мода друзей. Подключение к чужому миру остаётся доступным")
                return@withContext
            }
            ModManager.install(gameDir,loader,version,PROJECT,"e4mc — игра с друзьями",emptySet(),selectedVersion = selected,onProgress = onProgress)
            val file = selected.primaryFile ?: throw IOException("В выпуске мода друзей нет файла")
            val hash = file.sha1 ?: throw IOException("Нет контрольной суммы мода друзей")
            Files.createDirectories(marker.parent)
            marker.writeAtomically(Json.encodeToString(FriendsManagedMod(file.filename,hash,version,loader)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            throw IOException("Не удалось подготовить мод друзей: ${e.message}. Можно повторить запуск или выключить игру с друзьями в настройках",e)
        }
    }

    fun isPresent(gameDir: Path): Boolean {
        val dir = ModManager.modsDir(gameDir)
        return if (Files.isDirectory(dir)) Files.list(dir).use { files ->
            files.filter { Files.isRegularFile(it,NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".jar") }
                .anyMatch { runCatching { ModCompat.read(it)?.id in ids }.getOrDefault(false) }
        } else false
    }

    fun fingerprint(gameDir: Path): String {
        val dir = ModManager.modsDir(gameDir)
        val sums = if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { files ->
            files.filter { Files.isRegularFile(it,NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".jar") }
                .map { sha1Of(it) }.toList().sorted()
        }
        return MessageDigest.getInstance("SHA-256").digest(sums.joinToString("\n").toByteArray()).toHex()
    }
}
