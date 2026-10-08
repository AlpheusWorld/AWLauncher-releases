package ru.aw.launcher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Serializable
internal data class SavedSkin(val id: String, val name: String, val model: SkinModel, val capeId: String? = null)

/** Local PNG collection; saving a preset never changes the public Minecraft profile. */
internal class SkinLibrary(private val directory: Path = Paths.root.resolve("skins")) {
    private val mutex = Mutex()
    private val index = directory.resolve("library.json")

    suspend fun list(): List<SavedSkin> = withContext(Dispatchers.IO) { mutex.withLock { readIndex() } }
    suspend fun image(skin: SavedSkin): SkinImage = withContext(Dispatchers.IO) { MinecraftSkins.read(imagePath(skin.id)) }

    suspend fun save(image: SkinImage, name: String, model: SkinModel, capeId: String?, existingId: String? = null): SavedSkin =
        withContext(Dispatchers.IO) { mutex.withLock {
            val entries = readIndex()
            val id = existingId ?: UUID.randomUUID().toString()
            if (existingId != null) require(entries.any { it.id == id }) { "Скин уже удалён из библиотеки" }
            val saved = SavedSkin(id, name.trim().take(80).ifBlank { "Мой скин" }, model, capeId)
            // Validate even images received from a caller instead of the file chooser.
            val path = imagePath(id)
            val previous = if (existingId != null) MinecraftSkins.read(path).png else null
            path.writeAtomically(MinecraftSkins.decode(image.png).png)
            val updated = if (existingId == null) entries + saved else entries.map { if (it.id == id) saved else it }
            try { index.writeAtomically(Json.encodeToString(updated)) }
            catch (failure: Exception) {
                runCatching { if (previous != null) path.writeAtomically(previous) else Files.deleteIfExists(path) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            saved
        } }

    suspend fun remove(id: String): List<SavedSkin> = withContext(Dispatchers.IO) { mutex.withLock {
        val path = imagePath(id)
        val remaining = readIndex().filterNot { it.id == id }
        index.writeAtomically(Json.encodeToString(remaining))
        Files.deleteIfExists(path)
        remaining
    } }

    suspend fun move(id: String, destination: Int): List<SavedSkin> = withContext(Dispatchers.IO) { mutex.withLock {
        val entries = readIndex().toMutableList()
        val position = entries.indexOfFirst { it.id == id }
        if (position >= 0) {
            val entry = entries.removeAt(position)
            entries.add(destination.coerceIn(0, entries.size), entry)
            index.writeAtomically(Json.encodeToString(entries))
        }
        entries
    } }

    private fun readIndex(): List<SavedSkin> {
        if (!Files.exists(index)) return emptyList()
        val bytes = Files.newInputStream(index).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024) { "Библиотека скинов слишком большая" }
        val entries = Json.decodeFromString<List<SavedSkin>>(bytes.toString(Charsets.UTF_8))
        entries.forEach { imagePath(it.id) }
        require(entries.map { it.id }.distinct().size == entries.size) { "Некорректная библиотека скинов" }
        return entries
    }

    private fun imagePath(id: String): Path {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Некорректный ID скина" }
        return directory.resolve("$id.png")
    }
}
