package ru.aw.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.instance.ManagedMod
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

data class InstalledItem(
    val file: Path,
    val kind: ContentKind,
    val enabled: Boolean,
    val sha1: String,
    val projectId: String?,
    val title: String,
    val versionNumber: String,
    val iconUrl: String?,
    val update: Modrinth.Version?,
    val versionId: String? = null,
    val rowKey: String = file.toString(),
) {
    val fileName: String get() = file.name
}

object ModManager {

    const val IRIS = "YL57xq9U"
    private const val DISABLED = ".disabled"
    private const val DEPENDENCY_DEPTH = 4
    private const val FIX_ROUNDS = 4
    private const val FIX_CANDIDATES = 6
    private const val BLOCKED_LIMIT = 50

    private class Hashed(val size: Long, val modified: Long, val sha1: String)

    private val hashes = ConcurrentHashMap<Path, Hashed>()

    private fun hashOf(file: Path): String {
        val size = file.fileSize()
        val modified = file.getLastModifiedTime().toMillis()
        hashes[file]?.takeIf { it.size == size && it.modified == modified }?.let { return it.sha1 }
        return sha1Of(file).also { hashes[file] = Hashed(size, modified, it) }
    }

    fun loadersFor(kind: LoaderKind): List<String> = when (kind) {
        LoaderKind.FABRIC -> listOf("fabric")
        LoaderKind.QUILT -> listOf("quilt", "fabric")
        LoaderKind.FORGE -> listOf("forge")
        LoaderKind.NEOFORGE -> listOf("neoforge")
        LoaderKind.VANILLA -> emptyList()
    }

    fun catalogLoaders(content: ContentKind, kind: LoaderKind): List<String> = when (content) {
        ContentKind.MOD -> loadersFor(kind)
        ContentKind.SHADER -> listOf("iris", "optifine")
        ContentKind.RESOURCE_PACK -> listOf("minecraft")
    }

    fun shaderLoader(kind: LoaderKind): LoaderKind? = when (kind) {
        LoaderKind.FABRIC, LoaderKind.QUILT, LoaderKind.NEOFORGE -> kind
        LoaderKind.VANILLA -> null
        LoaderKind.FORGE -> null
    }

    fun modsDir(gameDir: Path): Path = ContentKind.MOD.dir(gameDir)

    suspend fun scan(
        gameDir: Path,
        kind: LoaderKind,
        gameVersion: String,
        content: ContentKind = ContentKind.MOD,
        withUpdates: Boolean = false,
        identifyRemote: Boolean = true,
    ): List<InstalledItem> =
        withContext(Dispatchers.IO) {
            val dir = content.dir(gameDir)
            if (!dir.isDirectory()) return@withContext emptyList()
            val files = dir.listDirectoryEntries().filter { isContent(it, content) }
            if (files.isEmpty()) return@withContext emptyList()

            val sums = files.associateWith { file -> if (file.isDirectory()) "" else runCatching { hashOf(file) }.getOrDefault("") }
            val known = sums.values.filter { it.isNotEmpty() }
            val options = InstanceStore.get(gameDir)
            val records = options.catalogMods.associateBy { it.fileName }
            val tracked = files.mapNotNull { file ->
                records["${content.folder}/${file.name.removeSuffix(DISABLED)}"]?.takeIf { it.sha1 == sums[file] }
            }.associateBy { it.sha1 }
            val versions = (if (identifyRemote) remote("identify mods") { Modrinth.versionsByHash(known.filter { it !in tracked }) }.orEmpty() else emptyMap()) +
                tracked.mapValues { (_, record) -> Modrinth.Version(id = record.versionId, projectId = record.projectId,
                    name = record.title, versionNumber = record.versionNumber, datePublished = record.datePublished) }
            val projects = (if (identifyRemote) remote("mod titles") { Modrinth.projects(versions.values.map { it.projectId }.filterNot(CurseForge::owns).distinct()) }
                .orEmpty() else emptyList()).associateBy { it.id }
            val loaders = loadersFor(kind)
            val updates = if (withUpdates && content == ContentKind.MOD && versions.isNotEmpty()) {
                val latest = remote("mod updates") { Modrinth.latestVersions(versions.filterValues { !CurseForge.owns(it.projectId) }.keys, loaders, gameVersion) }.orEmpty() +
                    tracked.mapNotNull { (sha1, record) -> remote("CurseForge updates") {
                        newestRelease(ContentCatalog.versions(record.projectId, loaders, gameVersion).filter { it.primaryFile != null })
                    }?.let { sha1 to it } }.toMap()
                stableUpdates(versions, latest, loaders, gameVersion)
            } else {
                emptyMap()
            }
            val blocked = options.blockedUpdates.toSet()
            val shader = if (content == ContentKind.SHADER) GameOptions.activeShader(gameDir) else null
            val resourcePacks = if (content == ContentKind.RESOURCE_PACK) GameOptions.resourcePacks(gameDir).toSet() else emptySet()

            files.map { file ->
                val sha1 = sums.getValue(file)
                val version = versions[sha1]
                val project = version?.let { projects[it.projectId] }
                val newer = updates[sha1]?.takeIf { it.id !in blocked }
                val enabled = when (content) {
                    ContentKind.MOD -> file.name.endsWith(".jar")
                    ContentKind.SHADER -> file.name == shader
                    ContentKind.RESOURCE_PACK -> "file/${file.name}" in resourcePacks
                }
                InstalledItem(
                    file = file,
                    kind = content,
                    enabled = enabled,
                    sha1 = sha1,
                    projectId = version?.projectId,
                    title = project?.title?.takeIf { it.isNotBlank() } ?: tracked[sha1]?.title?.takeIf { it.isNotBlank() } ?: file.name.removeSuffix(DISABLED).removeSuffix(content.extension),
                    versionNumber = version?.versionNumber.orEmpty(),
                    iconUrl = project?.iconUrl ?: tracked[sha1]?.iconUrl,
                    update = newer,
                    versionId = version?.id,
                )
            }.sortedBy { it.title.lowercase() }
        }

    private fun isContent(file: Path, content: ContentKind): Boolean = when (content) {
        ContentKind.MOD -> file.name.endsWith(".jar") || file.name.endsWith(".jar$DISABLED")
        else -> file.name.endsWith(content.extension) || file.isDirectory()
    }

    suspend fun install(
        gameDir: Path,
        kind: LoaderKind,
        gameVersion: String,
        projectId: String,
        title: String,
        present: Set<String>,
        selectedVersion: Modrinth.Version? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        val loaders = loadersFor(kind)
        val find = { id: String -> Modrinth.pick(ContentCatalog.versions(id, loaders, gameVersion).filter { it.primaryFile != null }) }
        val root = selectedVersion?.also { requireCompatibleVersion(it, projectId, gameVersion, loaders) } ?: find(projectId)
            ?: throw IOException("$title пока нет для ${kind.label} $gameVersion")

        val chosen = LinkedHashMap<String, Modrinth.Version>()
        chosen[projectId] = root
        val skipped = ArrayList<String>()
        var frontier = listOf(root)
        for (depth in 1..if (CurseForge.owns(projectId)) 64 else DEPENDENCY_DEPTH) {
            val needed = frontier.asSequence()
                .flatMap { it.dependencies }
                .filter { it.type == "required" }
                .mapNotNull { it.projectId }
                .filter { it !in chosen && it !in present }
                .distinct()
                .toList()
            if (needed.isEmpty()) break
            val names = remote("dependency titles") { ContentCatalog.titles(needed) }.orEmpty()
            frontier = needed.mapNotNull { id ->
                val version = find(id)
                if (version == null) skipped += names[id] ?: id
                version?.also { chosen[id] = it }
            }
        }
        if (skipped.isNotEmpty()) {
            throw IOException("$title требует ${skipped.joinToString()}, а для ${kind.label} $gameVersion их нет")
        }
        if (CurseForge.owns(projectId) && chosen.values.flatMap { it.dependencies }.any {
                it.type == "required" && it.projectId != null && it.projectId !in chosen && it.projectId !in present
            }) throw IOException("Не удалось разрешить все зависимости $title")

        val dir = modsDir(gameDir).also { it.createDirectories() }
        val before = dir.listDirectoryEntries().toSet()
        val tasks = chosen.values.map { version ->
            val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
            val name = ContentKind.MOD.safeName(file.filename)
                ?: throw IOException("Недопустимое имя файла: ${file.filename}")
            DownloadTask(file.url, dir.resolve(name), file.sha1, file.size, label = name)
        }
        Downloader().run(tasks, onProgress)
        val added = tasks.map { it.dest }.filter { it !in before }.toSet()
        val existing = ModCompat.enabledIn(dir, except = added)
        val conflicts = added.mapNotNull(ModCompat::read).flatMap { ModCompat.conflicts(it, existing) }
        if (conflicts.isNotEmpty()) {
            added.forEach { runCatching { it.deleteIfExists() } }
            throw IncompatibleModException(conflicts, "не стал ставить, чтобы игра не упала")
        }
        clearBlocked(gameDir)
        val titles = remote("installed titles") { ContentCatalog.titles(chosen.keys) }.orEmpty()
        chosen.values.forEach { version -> rememberCatalog(gameDir, ContentKind.MOD, version, titles[version.projectId] ?: title) }
        Log.info("mods installed into $dir: ${tasks.joinToString { it.dest.name }}")
        chosen.keys.map { titles[it] ?: it }
    }

    suspend fun installShader(
        gameDir: Path,
        kind: LoaderKind,
        gameVersion: String,
        projectId: String,
        title: String,
        present: Set<String>,
        selectedVersion: Modrinth.Version? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<String> {
        val runLoader = shaderLoader(kind)
            ?: throw IOException("Шейдеры работают через Iris, а для ${kind.label} его нет. Выбери эту версию с Fabric или NeoForge")
        val iris = if (IRIS in present) emptyList() else install(gameDir, runLoader, gameVersion, IRIS, "Iris", present, onProgress = onProgress)
        val name = downloadPack(gameDir, ContentKind.SHADER, gameVersion, projectId, title, onProgress, selectedVersion)
        GameOptions.setShader(gameDir, name)
        return iris + title
    }

    suspend fun installResourcePack(
        gameDir: Path,
        gameVersion: String,
        projectId: String,
        title: String,
        selectedVersion: Modrinth.Version? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ) {
        val name = downloadPack(gameDir, ContentKind.RESOURCE_PACK, gameVersion, projectId, title, onProgress, selectedVersion)
        GameOptions.setResourcePack(gameDir, name, enabled = true)
    }

    private suspend fun downloadPack(
        gameDir: Path,
        content: ContentKind,
        gameVersion: String,
        projectId: String,
        title: String,
        onProgress: (DownloadProgress) -> Unit,
        selectedVersion: Modrinth.Version? = null,
    ): String = withContext(Dispatchers.IO) {
        val loaders = catalogLoaders(content, LoaderKind.VANILLA)
        val version = selectedVersion?.also { requireCompatibleVersion(it, projectId, gameVersion, loaders) }
            ?: Modrinth.pick(ContentCatalog.versions(projectId, loaders, gameVersion))
            ?: throw IOException("$title пока нет для $gameVersion")
        val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
        val name = content.safeName(file.filename) ?: throw IOException("Недопустимое имя файла: ${file.filename}")
        Downloader().run(listOf(DownloadTask(file.url, content.dir(gameDir).resolve(name), file.sha1, file.size, label = name)), onProgress)
        rememberCatalog(gameDir, content, version, title)
        Log.info("${content.projectType} installed into $gameDir: $name")
        name
    }

    internal fun requireCompatibleVersion(version: Modrinth.Version, projectId: String, gameVersion: String, loaders: List<String>) {
        require(version.projectId == projectId) { "Версия относится к другому проекту" }
        require(gameVersion in version.gameVersions && (loaders.isEmpty() || version.loaders.any { it in loaders })) {
            "Эта версия несовместима с выбранной сборкой"
        }
    }

    suspend fun update(mod: InstalledItem, onProgress: (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val version = mod.update ?: return@withContext
        val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
        val name = ContentKind.MOD.safeName(file.filename)
            ?: throw IOException("Недопустимое имя файла: ${file.filename}")
        val target = mod.file.resolveSibling(if (mod.enabled) name else name + DISABLED)
        val download = mod.file.resolveSibling("$name.download")
        Downloader().run(listOf(DownloadTask(file.url, download, file.sha1, file.size, label = name)), onProgress)
        if (mod.enabled) {
            val conflicts = ModCompat.read(download)
                ?.let { ModCompat.conflicts(it, ModCompat.enabledIn(mod.file.parent, except = setOf(mod.file))) }
                .orEmpty()
            if (conflicts.isNotEmpty()) {
                download.deleteIfExists()
                gameDirOf(mod)?.let { dir ->
                    InstanceStore.update(dir) { it.copy(blockedUpdates = (it.blockedUpdates + version.id).distinct().takeLast(BLOCKED_LIMIT)) }
                }
                throw IncompatibleModException(conflicts, "оставил прежнюю версию")
            }
        }
        locked(mod) {
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
            if (target != mod.file) mod.file.deleteIfExists()
        }
        gameDirOf(mod)?.let(::clearBlocked)
        gameDirOf(mod)?.let { dir -> rememberCatalog(dir, ContentKind.MOD, version, mod.title, mod.iconUrl) }
        Log.info("mod updated: ${mod.fileName} -> ${target.name}")
    }

    suspend fun fixConflicts(
        gameDir: Path,
        kind: LoaderKind,
        gameVersion: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = modsDir(gameDir)
        val changes = ArrayList<String>()
        val rejected = ArrayList<String>()
        try {
            repeat(FIX_ROUNDS) {
                val jars = dir.listDirectoryEntries("*.jar").mapNotNull { jar -> ModCompat.read(jar)?.let { jar to it } }
                val conflict = ModCompat.conflictsAmong(jars.map { it.second }).firstOrNull() ?: return@withContext changes
                changes += replaceOneSide(conflict, jars, kind, gameVersion, rejected, onProgress)
                    ?: throw IOException("${conflict.text}. Подходящей версии на Modrinth нет — выключи один из них в «Моды»")
            }
            if (ModCompat.conflictsIn(dir).isNotEmpty()) throw IOException("Не получилось развести все моды — выключи лишние в «Моды»")
            changes
        } finally {
            if (rejected.isNotEmpty()) {
                InstanceStore.update(gameDir) { options ->
                    options.copy(blockedUpdates = (options.blockedUpdates + rejected).distinct().takeLast(BLOCKED_LIMIT))
                }
            }
        }
    }

    private suspend fun replaceOneSide(
        conflict: Conflict,
        jars: List<Pair<Path, ModMeta>>,
        kind: LoaderKind,
        gameVersion: String,
        rejected: MutableList<String>,
        onProgress: (DownloadProgress) -> Unit,
    ): String? {
        val sides = listOf(conflict.mod, conflict.other).mapNotNull { meta -> jars.firstOrNull { it.second == meta } }
        val known = Modrinth.versionsByHash(sides.map { hashOf(it.first) })
        val ordered = sides.mapNotNull { side -> known[hashOf(side.first)]?.let { side to it } }
            .sortedBy { (_, version) -> if (version.versionType == "release") 1 else 0 }
        val loaders = loadersFor(kind)
        for ((side, current) in ordered) {
            val (jar, meta) = side
            val others = jars.filter { it.first != jar }.map { it.second }
            val candidates = ContentCatalog.versions(current.projectId, loaders, gameVersion)
                .filter { it.versionType == "release" && it.id != current.id }
                .take(FIX_CANDIDATES)
            for (candidate in candidates) {
                val file = candidate.primaryFile ?: continue
                val name = ContentKind.MOD.safeName(file.filename) ?: continue
                val download = jar.resolveSibling("$name.download")
                Downloader().run(listOf(DownloadTask(file.url, download, file.sha1, file.size, label = name)), onProgress)
                val fresh = ModCompat.read(download)
                if (fresh == null || ModCompat.conflicts(fresh, others).isNotEmpty()) {
                    download.deleteIfExists()
                    rejected += candidate.id
                    continue
                }
                val target = jar.resolveSibling(name)
                Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
                if (target != jar) jar.deleteIfExists()
                rejected += current.id
                Log.info("mod conflict fixed: ${jar.name} -> ${target.name}")
                return "${meta.name} ${meta.shortVersion} → ${fresh.shortVersion}"
            }
        }
        return null
    }

    private suspend fun stableUpdates(
        installed: Map<String, Modrinth.Version>,
        latest: Map<String, Modrinth.Version>,
        loaders: List<String>,
        gameVersion: String,
    ): Map<String, Modrinth.Version> = coroutineScope {
        latest.mapNotNull { (sha1, candidate) ->
            val current = installed[sha1] ?: return@mapNotNull null
            if (candidate.id == current.id) return@mapNotNull null
            sha1 to async {
                val release = if (candidate.versionType == "release") {
                    candidate
                } else {
                    remote("releases of ${current.projectId}") {
                        ContentCatalog.versions(current.projectId, loaders, gameVersion)
                    }?.let(::newestRelease)
                }
                release?.takeIf { isUpgrade(it, current) }
            }
        }.mapNotNull { (sha1, lookup) -> lookup.await()?.let { sha1 to it } }.toMap()
    }

    internal fun newestRelease(versions: List<Modrinth.Version>): Modrinth.Version? =
        versions.filter { it.versionType == "release" }.maxByOrNull(::publishedAt)

    internal fun isUpgrade(candidate: Modrinth.Version, current: Modrinth.Version): Boolean =
        candidate.versionType == "release" && candidate.id != current.id && publishedAt(candidate) > publishedAt(current)

    private fun publishedAt(version: Modrinth.Version): Instant =
        runCatching { Instant.parse(version.datePublished) }.getOrDefault(Instant.EPOCH)

    fun setEnabled(item: InstalledItem, enabled: Boolean): InstalledItem {
        if (item.enabled == enabled) return item
        val gameDir = gameDirOf(item) ?: return item
        var file = item.file
        when (item.kind) {
            ContentKind.MOD -> {
                val base = item.fileName.removeSuffix(DISABLED)
                val target = item.file.resolveSibling(if (enabled) base else base + DISABLED)
                locked(item) { Files.move(item.file, target, StandardCopyOption.REPLACE_EXISTING) }
                file = target
                clearBlocked(gameDir)
            }
            ContentKind.SHADER -> GameOptions.setShader(gameDir, item.fileName.takeIf { enabled })
            ContentKind.RESOURCE_PACK -> GameOptions.setResourcePack(gameDir, item.fileName, enabled)
        }
        return item.copy(file = file, enabled = enabled)
    }

    fun remove(item: InstalledItem) {
        locked(item) { if (item.file.isDirectory()) Storage.discard(item.file) else item.file.deleteIfExists() }
        val gameDir = gameDirOf(item) ?: return
        when (item.kind) {
            ContentKind.MOD -> clearBlocked(gameDir)
            ContentKind.SHADER -> if (item.enabled) GameOptions.setShader(gameDir, null)
            ContentKind.RESOURCE_PACK -> GameOptions.setResourcePack(gameDir, item.fileName, enabled = false)
        }
    }

    private fun gameDirOf(item: InstalledItem): Path? = item.file.parent?.parent

    internal fun rememberCatalog(gameDir: Path, kind: ContentKind, version: Modrinth.Version, title: String, iconUrl: String? = null) {
        if (!CurseForge.owns(version.projectId)) return
        val file = version.primaryFile ?: return
        val name = kind.safeName(file.filename) ?: return
        val path = kind.dir(gameDir).resolve(name).let { if (it.exists()) it else it.resolveSibling(name + DISABLED) }
        val icon = iconUrl ?: remote("CurseForge icon") { CurseForge.projectInfo(version.projectId).logo?.url }
        val record = ManagedMod(version.projectId, version.id, "${kind.folder}/$name", hashOf(path), title,
            version.versionNumber, version.datePublished, icon)
        InstanceStore.update(gameDir) { options -> options.copy(catalogMods = options.catalogMods.filterNot {
            it.fileName == record.fileName || (it.projectId == record.projectId && it.fileName.startsWith("${kind.folder}/"))
        } + record) }
    }

    private fun clearBlocked(gameDir: Path) {
        if (InstanceStore.get(gameDir).blockedUpdates.isEmpty()) return
        InstanceStore.update(gameDir) { it.copy(blockedUpdates = emptyList()) }
    }

    fun count(gameDir: Path): Int = runCatching {
        val dir = modsDir(gameDir)
        if (!dir.exists()) 0 else dir.listDirectoryEntries("*.jar").size
    }.getOrDefault(0)

    private inline fun locked(mod: InstalledItem, action: () -> Unit) {
        try {
            action()
        } catch (e: FileSystemException) {
            throw IOException("${mod.title}: файл занят. Закрой игру и попробуй снова", e)
        }
    }

    private inline fun <T> remote(what: String, call: () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.warn("modrinth $what failed: ${e.message}")
        null
    }
}
