package ru.aw.launcher.mods

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.aw.launcher.core.Json
import ru.aw.launcher.net.Http

object Modrinth {

    private const val API = "https://api.modrinth.com/v2"

    @Serializable
    data class Version(
        val id: String,
        @SerialName("project_id") val projectId: String,
        val name: String = "",
        @SerialName("version_number") val versionNumber: String = "",
        @SerialName("version_type") val versionType: String = "release",
        @SerialName("date_published") val datePublished: String = "",
        @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
        val loaders: List<String> = emptyList(),
        val files: List<VersionFile> = emptyList(),
        val dependencies: List<Dependency> = emptyList(),
        val changelog: String = "",
        val downloads: Long = 0,
    ) {
        val primaryFile: VersionFile? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
    }

    @Serializable
    data class VersionFile(
        val url: String,
        val filename: String,
        val primary: Boolean = false,
        val size: Long = 0,
        val hashes: Map<String, String> = emptyMap(),
    ) {
        val sha1: String? get() = hashes["sha1"]
    }

    @Serializable
    data class Dependency(
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("version_id") val versionId: String? = null,
        @SerialName("dependency_type") val type: String = "required",
    )

    @Serializable
    data class Project(
        val id: String,
        val title: String = "",
        val description: String = "",
        @SerialName("icon_url") val iconUrl: String? = null,
        val slug: String = "",
        val body: String = "",
        @SerialName("project_type") val projectType: String = "mod",
        val downloads: Long = 0,
        val followers: Long = 0,
        @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
        val loaders: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val gallery: List<GalleryImage> = emptyList(),
        val license: License = License(),
        @SerialName("source_url") val sourceUrl: String? = null,
        @SerialName("issues_url") val issuesUrl: String? = null,
        @SerialName("wiki_url") val wikiUrl: String? = null,
        @SerialName("discord_url") val discordUrl: String? = null,
    )

    @Serializable
    data class GalleryImage(
        val url: String,
        val featured: Boolean = false,
        val title: String? = null,
        val description: String? = null,
        val created: String = "",
        val ordering: Int = 0,
    )

    @Serializable
    data class License(val id: String = "", val name: String = "", val url: String? = null)

    fun project(id: String): Project = Json.decodeFromString<Project>(Http.getString("$API/project/$id"))

    @Serializable
    data class SearchHit(
        @SerialName("project_id") val projectId: String,
        val slug: String = "",
        val title: String = "",
        val description: String = "",
        val author: String = "",
        val downloads: Long = 0,
        @SerialName("icon_url") val iconUrl: String? = null,
        val categories: List<String> = emptyList(),
        val versions: List<String> = emptyList(),
        @SerialName("project_type") val projectType: String = "modpack",
    )

    @Serializable
    data class SearchPage(
        val hits: List<SearchHit> = emptyList(),
        val offset: Int = 0,
        @SerialName("total_hits") val totalHits: Int = 0,
    )

    fun versions(project: String, loaders: List<String> = emptyList(), gameVersion: String? = null): List<Version> {
        val url = "$API/project/$project/version".toHttpUrl().newBuilder()
            .apply { if (loaders.isNotEmpty()) addQueryParameter("loaders", loaders.joinToString(",", "[", "]") { "\"$it\"" }) }
            .apply { if (gameVersion != null) addQueryParameter("game_versions", "[\"$gameVersion\"]") }
            .build()
            .toString()
        return Json.decodeFromString<List<Version>>(Http.getString(url))
            .sortedByDescending { it.datePublished }
    }

    fun version(id: String): Version = Json.decodeFromString<Version>(Http.getString("$API/version/$id"))

    fun search(
        projectType: String,
        query: String,
        categories: List<String>,
        gameVersion: String?,
        offset: Int,
        limit: Int = PAGE,
    ): SearchPage {
        val facets = buildJsonArray {
            add(buildJsonArray { add("project_type:$projectType") })
            if (categories.isNotEmpty()) add(buildJsonArray { categories.forEach { add("categories:$it") } })
            if (gameVersion != null) add(buildJsonArray { add("versions:$gameVersion") })
            if (projectType == "mod") add(buildJsonArray { add("client_side:required"); add("client_side:optional") })
        }
        val url = "$API/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.trim())
            .addQueryParameter("facets", facets.toString())
            .addQueryParameter("index", if (query.isBlank()) "downloads" else "relevance")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
            .toString()
        return Json.decodeFromString<SearchPage>(Http.getString(url))
    }

    const val PAGE = 20

    fun pick(versions: List<Version>): Version? =
        versions.firstOrNull { it.versionType == "release" }
            ?: versions.firstOrNull { it.versionType == "beta" }

    fun versionsByHash(sha1s: Collection<String>): Map<String, Version> {
        if (sha1s.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("hashes", JsonArray(sha1s.map { JsonPrimitive(it) }))
            put("algorithm", "sha1")
        }
        return Json.decodeFromString<Map<String, Version>>(Http.postJson("$API/version_files", body.toString()))
    }

    fun latestVersions(sha1s: Collection<String>, loaders: List<String>, gameVersion: String): Map<String, Version> {
        if (sha1s.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("hashes", JsonArray(sha1s.map { JsonPrimitive(it) }))
            put("algorithm", "sha1")
            put("loaders", JsonArray(loaders.map { JsonPrimitive(it) }))
            put("game_versions", buildJsonArray { add(gameVersion) })
        }
        return Json.decodeFromString<Map<String, Version>>(Http.postJson("$API/version_files/update", body.toString()))
    }

    fun projects(ids: Collection<String>): List<Project> {
        if (ids.isEmpty()) return emptyList()
        val url = "$API/projects".toHttpUrl().newBuilder()
            .addQueryParameter("ids", ids.joinToString(",", "[", "]") { "\"$it\"" })
            .build()
            .toString()
        return Json.decodeFromString<List<Project>>(Http.getString(url))
    }

    fun titles(ids: Collection<String>): Map<String, String> = projects(ids).associate { it.id to it.title }
}
