package ru.aw.launcher.mods

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Paths
import ru.aw.launcher.net.Http
import ru.aw.launcher.packs.PackFile
import ru.aw.launcher.packs.PackIndex
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readText

object CurseForge {
    private const val API = "https://api.curseforge.com/v1"
    private const val GAME = 432
    private const val MAX_RESULTS = 10_000
    private val LOADERS = mapOf("forge" to 1, "fabric" to 4, "quilt" to 5, "neoforge" to 6)
    internal val DOWNLOAD_HOSTS = setOf("edge.forgecdn.net", "mediafilez.forgecdn.net", "media.forgecdn.net")

    val apiKey: String by lazy {
        sequenceOf(
            System.getenv("AW_CURSEFORGE_API_KEY"),
            runCatching { Paths.root.resolve("curseforge_api_key.txt").takeIf { it.exists() }?.readText() }.getOrNull(),
            CurseForge::class.java.getResourceAsStream("/curseforge-api-key.txt")?.bufferedReader()?.use { it.readText() },
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    fun owns(id: String): Boolean = id.startsWith("cf:")
    fun projectNumber(id: String): Int = id.removePrefix("cf:").substringBefore(':').toIntOrNull()?.takeIf { it > 0 }
        ?: throw IOException("Некорректный идентификатор CurseForge")
    private fun fileNumber(id: String): Int = id.split(':').takeIf { it.size == 3 }?.last()?.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IOException("Некорректный идентификатор файла CurseForge")

    @Serializable internal data class Response<T>(val data: T, val pagination: Pagination = Pagination())
    @Serializable internal data class Pagination(val index: Int = 0, val resultCount: Int = 0, val totalCount: Int = 0)
    @Serializable internal data class Category(val id: Int, val slug: String = "", val name: String = "")
    @Serializable internal data class Asset(val url: String = "", val title: String = "", val description: String = "")
    @Serializable internal data class Author(val name: String = "")
    @Serializable internal data class Links(val websiteUrl: String = "", val sourceUrl: String? = null, val issuesUrl: String? = null, val wikiUrl: String? = null)
    @Serializable internal data class Mod(
        val id: Int, val gameId: Int, val name: String = "", val slug: String = "", val summary: String = "",
        val classId: Int? = null, val categories: List<Category> = emptyList(), val authors: List<Author> = emptyList(),
        val logo: Asset? = null, val screenshots: List<Asset> = emptyList(), val links: Links = Links(),
        val downloadCount: Double = 0.0, val latestFiles: List<File> = emptyList(), val allowModDistribution: Boolean? = null,
    )
    @Serializable internal data class Hash(val value: String, val algo: Int)
    @Serializable internal data class Dependency(val modId: Int, val relationType: Int)
    @Serializable internal data class File(
        val id: Int, val modId: Int, val gameId: Int, val displayName: String = "", val fileName: String,
        val releaseType: Int = 1, val fileDate: String = "", val fileLength: Long = 0, val downloadCount: Long = 0,
        val downloadUrl: String? = null, val gameVersions: List<String> = emptyList(), val hashes: List<Hash> = emptyList(),
        val dependencies: List<Dependency> = emptyList(), val isAvailable: Boolean = true, val isServerPack: Boolean = false,
    )
    @Serializable internal data class Manifest(
        val manifestType: String = "", val manifestVersion: Int = 0, val name: String = "", val version: String = "",
        val minecraft: Minecraft, val files: List<ManifestFile> = emptyList(), val overrides: String = "overrides",
    )
    @Serializable internal data class Minecraft(val version: String, val modLoaders: List<ManifestLoader> = emptyList())
    @Serializable internal data class ManifestLoader(val id: String, val primary: Boolean = false)
    @Serializable internal data class ManifestFile(val projectID: Int, val fileID: Int, val required: Boolean = true)

    @Volatile private var classes: List<Category>? = null
    private fun classes(): List<Category> = classes ?: Json.decodeFromString<Response<List<Category>>>(
        get("categories", mapOf("gameId" to "$GAME", "classesOnly" to "true"))
    ).data.also { classes = it }

    private fun classId(type: String): Int {
        val slugs = when (type) {
            "mod" -> setOf("mc-mods", "mods")
            "modpack" -> setOf("modpacks")
            "resourcepack" -> setOf("texture-packs", "resource-packs")
            "shader" -> setOf("shaders")
            else -> throw IOException("Неизвестный тип контента: $type")
        }
        return classes().firstOrNull { it.slug in slugs }?.id ?: throw IOException("В CurseForge нет раздела $type")
    }

    private fun type(mod: Mod): String = when (classes().firstOrNull { it.id == mod.classId }?.slug) {
        "mc-mods", "mods" -> "mod"
        "modpacks" -> "modpack"
        "texture-packs", "resource-packs" -> "resourcepack"
        "shaders" -> "shader"
        else -> throw IOException("Этот проект CurseForge не поддерживается")
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): String {
        if (apiKey.isBlank()) throw IOException("Для CurseForge нужен API-ключ: укажи AW_CURSEFORGE_API_KEY или файл .aw/curseforge_api_key.txt и перезапусти лаунчер")
        val url = "$API/$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return try { Http.getString(url.toString(), mapOf("x-api-key" to apiKey)) }
        catch (e: IOException) { throw IOException("Не удалось загрузить данные CurseForge: ${e.message}", e) }
    }

    internal fun projectInfo(id: String): Mod = Json.decodeFromString<Response<Mod>>(get("mods/${projectNumber(id)}")).data.also {
        require(it.gameId == GAME && it.id == projectNumber(id)) { "Проект CurseForge относится к другой игре" }
    }

    fun search(type: String, query: String, loaders: List<String>, gameVersion: String?, offset: Int): Modrinth.SearchPage {
        if (offset >= MAX_RESULTS) return Modrinth.SearchPage(offset = offset, totalHits = MAX_RESULTS)
        val params = linkedMapOf("gameId" to "$GAME", "classId" to classId(type).toString(), "searchFilter" to query.trim(),
            "sortField" to if (query.isBlank()) "6" else "1", "sortOrder" to "desc", "index" to "$offset", "pageSize" to "${minOf(Modrinth.PAGE, MAX_RESULTS - offset)}")
        if (gameVersion != null) params["gameVersion"] = gameVersion
        val ids = loaders.mapNotNull(LOADERS::get)
        if (ids.isNotEmpty() && gameVersion != null) params["modLoaderTypes"] = ids.joinToString(",", "[", "]")
        val page = Json.decodeFromString<Response<List<Mod>>>(get("mods/search", params))
        return Modrinth.SearchPage(page.data.filter { it.gameId == GAME }.map { mod ->
            Modrinth.SearchHit("cf:${mod.id}", mod.slug, mod.name, mod.summary, mod.authors.joinToString { it.name },
                mod.downloadCount.toLong(), mod.logo?.url, mod.categories.map { it.slug },
                mod.latestFiles.flatMap { it.gameVersions }.filter(::isGameVersion).distinct(), type)
        }, offset, minOf(page.pagination.totalCount, MAX_RESULTS))
    }

    fun project(id: String): Modrinth.Project {
        val mod = projectInfo(id)
        val kind = type(mod)
        return Modrinth.Project(id = id, title = mod.name, description = mod.summary, iconUrl = mod.logo?.url, slug = mod.slug,
            body = Json.decodeFromString<Response<String>>(get("mods/${mod.id}/description")).data,
            projectType = kind, downloads = mod.downloadCount.toLong(), categories = mod.categories.map { it.name },
            gameVersions = mod.latestFiles.flatMap { it.gameVersions }.filter(::isGameVersion).distinct(),
            loaders = mod.latestFiles.flatMap { mapFile(it, kind, mod.allowModDistribution != false).loaders }.distinct(),
            gallery = mod.screenshots.mapIndexed { i, it -> Modrinth.GalleryImage(it.url, title = it.title, description = it.description, ordering = i) },
            sourceUrl = mod.links.sourceUrl, issuesUrl = mod.links.issuesUrl, wikiUrl = mod.links.wikiUrl)
    }

    fun versions(id: String, loaders: List<String> = emptyList(), gameVersion: String? = null): List<Modrinth.Version> {
        val mod = projectInfo(id)
        val kind = type(mod)
        val result = mutableListOf<Modrinth.Version>()
        var offset = 0
        do {
            val params = linkedMapOf("index" to "$offset", "pageSize" to "50")
            if (gameVersion != null) params["gameVersion"] = gameVersion
            if (loaders.size == 1) LOADERS[loaders.single()]?.let { params["modLoaderType"] = "$it" }
            val page = Json.decodeFromString<Response<List<File>>>(get("mods/${mod.id}/files", params))
            result += page.data.filter { it.modId == mod.id && it.gameId == GAME && !it.isServerPack }.map { mapFile(it, kind, mod.allowModDistribution != false) }
            offset += page.data.size
        } while (page.data.isNotEmpty() && offset < minOf(page.pagination.totalCount, MAX_RESULTS))
        return result.filter { (gameVersion == null || gameVersion in it.gameVersions) && (loaders.isEmpty() || it.loaders.any { loader -> loader in loaders }) }
            .sortedByDescending { it.datePublished }
    }

    fun version(id: String): Modrinth.Version {
        val mod = projectInfo(id)
        val file = file(mod.id, fileNumber(id))
        return mapFile(file, type(mod), mod.allowModDistribution != false)
    }

    fun changelog(id: String): String = Json.decodeFromString<Response<String>>(
        get("mods/${projectNumber(id)}/files/${fileNumber(id)}/changelog")
    ).data

    private fun file(project: Int, file: Int): File = Json.decodeFromString<Response<File>>(get("mods/$project/files/$file")).data.also {
        require(it.gameId == GAME && it.modId == project && it.id == file && !it.isServerPack) { "Файл CurseForge относится к другому проекту или серверной сборке" }
    }

    internal fun allowedDownload(url: String): Boolean = runCatching { url.toHttpUrl().let { it.isHttps && it.host in DOWNLOAD_HOSTS } }.getOrDefault(false)
    private fun isGameVersion(value: String) = value.firstOrNull()?.isDigit() == true || value.matches(Regex("\\d{2}w\\d{2}[a-z]"))
    internal fun mapFile(file: File, type: String, distributed: Boolean = true): Modrinth.Version {
        val loaders = when (type) {
            "resourcepack" -> listOf("minecraft")
            "shader" -> listOf("iris", "optifine")
            else -> file.gameVersions.map { it.lowercase() }.filter { it in LOADERS }
        }
        val download = file.downloadUrl?.takeIf { file.isAvailable && distributed && allowedDownload(it) }
        return Modrinth.Version(id = "cf:${file.modId}:${file.id}", projectId = "cf:${file.modId}", name = file.displayName,
            versionNumber = file.displayName.ifBlank { file.fileName }, versionType = when (file.releaseType) { 1 -> "release"; 2 -> "beta"; else -> "alpha" },
            datePublished = file.fileDate, gameVersions = file.gameVersions.filter(::isGameVersion), loaders = loaders,
            files = download?.let { listOf(Modrinth.VersionFile(it, file.fileName, true, file.fileLength, file.hashes.filter { h -> h.algo == 1 }.associate { h -> "sha1" to h.value })) }.orEmpty(),
            dependencies = file.dependencies.filter { it.relationType == 3 }.map { Modrinth.Dependency(projectId = "cf:${it.modId}") }, downloads = file.downloadCount)
    }

    internal fun packIndex(zip: ZipFile): Pair<PackIndex, String> {
        val entry = zip.getEntry("manifest.json") ?: throw IOException("Это не сборка CurseForge: нет manifest.json")
        val manifest = zip.getInputStream(entry).use { Json.decodeFromString<Manifest>(it.readBytes().decodeToString()) }
        require(manifest.manifestType == "minecraftModpack" && manifest.manifestVersion == 1) { "Неподдерживаемый формат сборки CurseForge" }
        val dependencies = linkedMapOf("minecraft" to manifest.minecraft.version)
        val primary = manifest.minecraft.modLoaders.filter { it.primary }.let { if (it.isEmpty()) manifest.minecraft.modLoaders else it }
        require(primary.size <= 1) { "В сборке CurseForge указано несколько загрузчиков" }
        primary.singleOrNull()?.let { loader ->
            val name = loader.id.substringBefore('-')
            val version = loader.id.substringAfter('-', "")
            val key = when (name) { "forge" -> "forge"; "neoforge" -> "neoforge"; "fabric" -> "fabric-loader"; "quilt" -> "quilt-loader"; else -> throw IOException("Загрузчик $name пока не поддерживается") }
            require(version.isNotBlank()) { "Не указана версия загрузчика CurseForge" }
            dependencies[key] = version
        }
        val files = manifest.files.filter { it.required }.map { ref ->
            val mod = projectInfo("cf:${ref.projectID}")
            val kind = type(mod)
            val content = ContentKind.entries.firstOrNull { it.projectType == kind } ?: throw IOException("В сборке указан неподдерживаемый проект ${mod.name}")
            val version = mapFile(file(ref.projectID, ref.fileID), kind, mod.allowModDistribution != false)
            val file = version.primaryFile ?: throw IOException("Автор ${mod.name} запретил загрузку через сторонние лаунчеры или файл недоступен — открой проект на CurseForge")
            val name = content.safeName(file.filename) ?: throw IOException("Недопустимое имя файла сборки: ${file.filename}")
            val sha1 = file.sha1 ?: throw IOException("У файла ${file.filename} нет контрольной суммы SHA-1")
            PackFile("${content.folder}/$name", mapOf("sha1" to sha1), downloads = listOf(file.url), fileSize = file.size,
                catalogVersion = version, catalogTitle = mod.name)
        }
        require(files.map { it.path }.distinct().size == files.size) { "В сборке CurseForge повторяются имена файлов" }
        return PackIndex(1, "minecraft", manifest.version, manifest.name, files, dependencies) to manifest.overrides
    }
}
