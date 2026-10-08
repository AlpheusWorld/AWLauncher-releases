package ru.aw.launcher.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import java.net.URI

@Serializable
internal data class SkinPreset(
    val name: String,
    val section: String,
    val model: SkinModel,
    val hash: String? = null,
    val archiveUrl: String? = null,
    val member: String? = null,
    val sha256: String? = null,
) {
    val id: String get() = "preset:$section:${hash ?: sha256}"
    val textureUrl: String? get() = hash?.let { "https://textures.minecraft.net/texture/$it" }

    fun validate() {
        require(name.isNotBlank() && section.isNotBlank())
        if (hash != null) {
            require(hash.matches(Regex("[a-f0-9]{32,64}")) && archiveUrl == null)
        } else {
            val url = URI(requireNotNull(archiveUrl))
            require(url.scheme == "https" && url.host in setOf("minecraft.net", "www.minecraft.net") && url.userInfo == null && url.port == -1)
            require(url.path.startsWith("/content/dam/minecraftnet/games/minecraft/software/") && url.path.endsWith(".zip") && url.query == null && url.fragment == null)
            require(!member.isNullOrBlank() && !member.startsWith('/') && '\\' !in member && member.split('/').none { it == ".." })
            require(sha256?.matches(Regex("[a-f0-9]{64}")) == true)
        }
    }
}

internal object SkinCatalog {
    val presets: List<SkinPreset> by lazy {
        val json = requireNotNull(javaClass.getResourceAsStream("/skins/catalog.json")) { "Missing skin catalog" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.decodeFromString<List<SkinPreset>>(json).also { entries ->
            entries.forEach(SkinPreset::validate)
            require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate skin preset IDs" }
        }
    }
    val sections: Map<String, List<SkinPreset>> by lazy { presets.groupBy { it.section } }
}
