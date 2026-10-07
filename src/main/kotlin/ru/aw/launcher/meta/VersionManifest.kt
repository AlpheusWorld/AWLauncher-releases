package ru.aw.launcher.meta

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.net.Http
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

@Serializable
data class VersionManifest(
    val latest: Latest = Latest(),
    val versions: List<ManifestVersion> = emptyList(),
) {
    @Serializable
    data class Latest(
        val release: String = "",
        val snapshot: String = "",
    )
}

@Serializable
data class ManifestVersion(
    val id: String,
    val type: String = "release",
    val url: String,
    val time: String = "",
    val releaseTime: String = "",
    val sha1: String? = null,
) {
    val kind: VersionKind get() = VersionKind.of(type)
}

enum class VersionKind(val label: String) {
    RELEASE("Релизы"),
    SNAPSHOT("Снапшоты"),
    OLD_BETA("Beta"),
    OLD_ALPHA("Alpha"),
    OTHER("Прочее");

    companion object {
        fun of(type: String): VersionKind = when (type.lowercase()) {
            "release" -> RELEASE
            "snapshot" -> SNAPSHOT
            "old_beta" -> OLD_BETA
            "old_alpha" -> OLD_ALPHA
            else -> OTHER
        }
    }
}

object VersionManifestRepository {

    private const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private val cacheFile = Paths.cache.resolve("version_manifest_v2.json")

    @Volatile private var cached: VersionManifest? = null

    private const val CACHE_FRESH_MILLIS = 10 * 60 * 1000L

    fun load(): VersionManifest {
        cached?.let { return it }
        if (isFresh()) loadCached()?.let { return it }
        return refresh() ?: loadCached() ?: VersionManifest()
    }

    fun loadCached(): VersionManifest? = runCatching {
        if (!cacheFile.exists()) return null
        Json.decodeFromString<VersionManifest>(cacheFile.readText()).takeIf { it.versions.isNotEmpty() }
    }.getOrNull()?.also { cached = it }

    fun isFresh(): Boolean = runCatching {
        val age = System.currentTimeMillis() - cacheFile.getLastModifiedTime().toMillis()
        age in 0..CACHE_FRESH_MILLIS
    }.getOrDefault(false)

    fun refresh(): VersionManifest? = runCatching {
        val text = Http.getString(URL)
        Json.decodeFromString<VersionManifest>(text).takeIf { it.versions.isNotEmpty() }?.also {
            runCatching { cacheFile.writeAtomically(text) }
            cached = it
        }
    }.onFailure { Log.warn("version manifest fetch failed: ${it.message}") }.getOrNull()
}
