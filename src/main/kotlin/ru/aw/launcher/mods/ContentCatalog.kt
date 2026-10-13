package ru.aw.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class CatalogSource(val label: String) {
    MODRINTH("Modrinth"), CURSEFORGE("CurseForge")
}

/** Provider routing keeps the existing catalog and installation models compatible. */
object ContentCatalog {
    data class UpdateLookup(val versions: Map<String, Modrinth.Version>, val failures: Map<String, String>)

    internal suspend fun updates(
        current: Map<String, Modrinth.Version>, loaders: List<String>, gameVersion: String,
        modrinth: (Collection<String>, List<String>, String) -> Map<String, Modrinth.Version> = Modrinth::latestVersions,
        curseforge: (String, List<String>, String) -> Modrinth.Version? = { id, platforms, game ->
            Modrinth.pick(CurseForge.versions(id, platforms, game).filter { it.primaryFile != null })
        },
    ): UpdateLookup = withContext(Dispatchers.IO) {
        coroutineScope {
            val limit = Semaphore(6)
            val requests = current.filterValues { !CurseForge.owns(it.projectId) }.keys.chunked(500).map { hashes ->
                async {
                    limit.withPermit {
                        try { UpdateLookup(modrinth(hashes, loaders, gameVersion), emptyMap()) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { UpdateLookup(emptyMap(), hashes.associateWith { "Modrinth: ${e.message.orEmpty()}" }) }
                    }
                }
            } + current.filterValues { CurseForge.owns(it.projectId) }.entries.groupBy { it.value.projectId }.map { (id, entries) ->
                async {
                    limit.withPermit {
                        try {
                            val version = curseforge(id, loaders, gameVersion)
                            UpdateLookup(if (version == null) emptyMap() else entries.associate { it.key to version }, emptyMap())
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { UpdateLookup(emptyMap(), entries.associate { it.key to "CurseForge: ${e.message.orEmpty()}" }) }
                    }
                }
            }
            val results = requests.map { it.await() }
            UpdateLookup(results.flatMap { it.versions.entries }.associate { it.toPair() },
                results.flatMap { it.failures.entries }.associate { it.toPair() })
        }
    }
    fun source(id: String) = if (CurseForge.owns(id)) CatalogSource.CURSEFORGE else CatalogSource.MODRINTH

    fun search(source: CatalogSource, type: String, query: String, loaders: List<String>, gameVersion: String?, offset: Int): Modrinth.SearchPage =
        when (source) {
            CatalogSource.MODRINTH -> Modrinth.search(type, query, loaders, gameVersion, offset)
            CatalogSource.CURSEFORGE -> CurseForge.search(type, query, loaders, gameVersion, offset)
        }

    fun project(id: String): Modrinth.Project = if (CurseForge.owns(id)) CurseForge.project(id) else Modrinth.project(id)
    fun versions(id: String, loaders: List<String> = emptyList(), gameVersion: String? = null): List<Modrinth.Version> =
        if (CurseForge.owns(id)) CurseForge.versions(id, loaders, gameVersion) else Modrinth.versions(id, loaders, gameVersion)
    fun version(id: String): Modrinth.Version = if (CurseForge.owns(id)) CurseForge.version(id) else Modrinth.version(id)
    fun changelog(version: Modrinth.Version): String = if (CurseForge.owns(version.id)) CurseForge.changelog(version.id) else version.changelog

    fun titles(ids: Collection<String>): Map<String, String> {
        val (curse, modrinth) = ids.partition(CurseForge::owns)
        return Modrinth.titles(modrinth) + curse.associateWith { CurseForge.projectInfo(it).name }
    }

    fun page(id: String, type: String, slug: String): String =
        if (CurseForge.owns(id)) "https://www.curseforge.com/projects/${CurseForge.projectNumber(id)}"
        else "https://modrinth.com/$type/${slug.ifBlank { id }}"

    fun isPackFile(projectId: String, filename: String): Boolean =
        filename.endsWith(if (CurseForge.owns(projectId)) ".zip" else ".mrpack", ignoreCase = true)
}
