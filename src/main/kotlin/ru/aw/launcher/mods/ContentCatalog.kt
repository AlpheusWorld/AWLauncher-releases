package ru.aw.launcher.mods

enum class CatalogSource(val label: String) {
    MODRINTH("Modrinth"), CURSEFORGE("CurseForge")
}

/** Provider routing keeps the existing catalog and installation models compatible. */
object ContentCatalog {
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
