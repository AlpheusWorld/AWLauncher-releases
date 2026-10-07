package ru.aw.launcher.instance

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.PrettyJson
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.core.writeAtomically
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class ManagedMod(
    val projectId: String,
    val versionId: String,
    val fileName: String,
    val sha1: String,
    val title: String = "",
    val versionNumber: String = "",
    val datePublished: String = "",
    val iconUrl: String? = null,
)

@Serializable
data class LibraryGroup(val id: String, val name: String)

@Serializable
data class InstanceOptions(
    val blockedUpdates: List<String> = emptyList(),
    val catalogMods: List<ManagedMod> = emptyList(),
    val memoryMb: Int? = null,
    val jvmArgs: String? = null,
    val javaPath: String? = null,
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
    val fullscreen: Boolean? = null,
    val favorite: Boolean = false,
    val groupId: String? = null,
    val iconPreset: String? = null,
    val iconBackground: String? = null,
    val iconImage: String? = null,
    val iconUrl: String? = null,
) {
    fun launchSettings(defaults: LauncherSettings): LauncherSettings = defaults.copy(
        memoryMb = memoryMb ?: defaults.memoryMb,
        jvmArgs = jvmArgs ?: defaults.jvmArgs,
    )
}

object InstanceStore {

    const val FILE_NAME = "aw-instance.json"

    private val cache = ConcurrentHashMap<String, InstanceOptions>()

    private fun key(gameDir: Path): String = gameDir.toAbsolutePath().normalize().toString()

    fun get(gameDir: Path): InstanceOptions = cache.getOrPut(key(gameDir)) { read(gameDir) }

    fun cached(gameDir: Path): InstanceOptions? = cache[key(gameDir)]

    @Synchronized
    fun update(gameDir: Path, transform: (InstanceOptions) -> InstanceOptions): InstanceOptions {
        val updated = transform(get(gameDir))
        gameDir.resolve(FILE_NAME).writeAtomically(PrettyJson.encodeToString(updated))
        cache[key(gameDir)] = updated
        return updated
    }

    fun forget(gameDir: Path) {
        cache.remove(key(gameDir))
    }

    private fun read(gameDir: Path): InstanceOptions {
        val file = gameDir.resolve(FILE_NAME)
        if (!file.exists()) return InstanceOptions()
        return runCatching {
            val document = Json.parseToJsonElement(file.readText())
            val stored = Json.decodeFromJsonElement<InstanceOptions>(document)
            // Former automatic performance mods remain ordinary, manageable installed mods.
            val legacyMods = document.jsonObject["boostMods"]?.let { Json.decodeFromJsonElement<List<ManagedMod>>(it) }.orEmpty()
            stored.copy(catalogMods = (stored.catalogMods + legacyMods.map {
                it.copy(fileName = "mods/${it.fileName.removePrefix("mods/")}")
            }).distinctBy { it.fileName })
        }
            .onFailure { Log.warn("$file unreadable, using defaults", it) }
            .getOrDefault(InstanceOptions())
    }
}
