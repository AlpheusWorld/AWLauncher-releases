package ru.aw.launcher.mods

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.toHex
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
private data class CompanionRecord(val sha256: String)

/** One-way retirement of the formerly bundled mod; never scans or downloads other mods. */
internal object CompanionMod {
    fun retire(gameDir: Path): Boolean {
        val target = gameDir.resolve("mods/awassistant.jar")
        val recordFile = gameDir.resolve("config/awassistant-managed.json")
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(recordFile, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.size(target) !in 1..16_777_216 || Files.size(recordFile) !in 1..4096) return false
        val owned = runCatching { Json.decodeFromString<CompanionRecord>(Files.readString(recordFile)).sha256 }.getOrNull() ?: return false
        if (!owned.matches(Regex("[a-f0-9]{64}"))) return false
        val hash = Files.newInputStream(target).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().toHex()
        }
        if (hash != owned || ModCompat.read(target)?.id != "awassistant") return false
        var disabled = target.resolveSibling("awassistant.jar.disabled")
        var suffix = 0
        while (Files.exists(disabled, LinkOption.NOFOLLOW_LINKS)) {
            suffix++
            disabled = target.resolveSibling("awassistant-retired-" + owned.take(12) + "-" + suffix + ".jar.disabled")
        }
        try { Files.move(target, disabled) }
        catch (error: IOException) { throw IOException("Не удалось отключить старый встроенный мод. Закрой Minecraft и повтори запуск сборки", error) }
        // The file is now an ordinary disabled mod. A subsequent manual re-enable is the user's choice.
        runCatching { Files.deleteIfExists(recordFile) }.onFailure { Log.warn("could not retire companion ownership marker", it) }
        Log.info("retired bundled assistant: " + disabled)
        return true
    }
}
