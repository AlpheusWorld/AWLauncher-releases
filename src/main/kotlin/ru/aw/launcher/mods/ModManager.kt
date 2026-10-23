package ru.aw.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.VerifyCache
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.instance.ManagedMod
import ru.aw.launcher.instance.BuildHistory
import ru.aw.launcher.instance.BuildMigration
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    val worldName: String? get() = if (kind == ContentKind.DATA_PACK) file.parent?.parent?.fileName?.toString() else null
}

object ModManager {

    const val IRIS = "YL57xq9U"
    private const val DISABLED = ".disabled"
    private const val FIX_ROUNDS = 4
    private const val FIX_CANDIDATES = 6
    private const val BLOCKED_LIMIT = 50

    private class Hashed(val size: Long, val modified: Long, val sha1: String)

    private val hashes = ConcurrentHashMap<Path, Hashed>()

    private fun hashOf(file: Path): String {
        val size = file.fileSize()
        val modified = file.getLastModifiedTime().toMillis()
        hashes[file]?.takeIf { it.size == size && it.modified == modified }?.let { return it.sha1 }
        VerifyCache.cachedSha1(file)?.let { cached ->
            hashes[file] = Hashed(size, modified, cached)
            return cached
        }
        return sha1Of(file).also {
            if (file.fileSize() == size && file.getLastModifiedTime().toMillis() == modified) {
                hashes[file] = Hashed(size, modified, it)
                VerifyCache.record(file, it)
            }
        }
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
        ContentKind.DATA_PACK -> listOf("datapack")
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
        previewOnly: Boolean = false,
    ): List<InstalledItem> =
        withContext(Dispatchers.IO) {
            val directories = if (content == ContentKind.DATA_PACK) dataPackWorlds(gameDir).flatMap {
                listOf(content.dir(it), it.resolve("aw-disabled-datapacks"))
            }
                else listOf(content.dir(gameDir))
            val files = directories.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.flatMap { dir ->
                dir.listDirectoryEntries().filter { !Files.isSymbolicLink(it) && isContent(it, content) }
            }
            if (files.isEmpty()) return@withContext emptyList()

            val sums = files.associateWith { file ->
                if (file.isDirectory()) "" else runCatching {
                    if (previewOnly) VerifyCache.cachedSha1(file).orEmpty() else hashOf(file)
                }.getOrDefault("")
            }
            val known = sums.values.filter { it.isNotEmpty() }
            val options = InstanceStore.get(gameDir)
            val records = options.catalogMods.associateBy { it.fileName }
            val tracked = files.mapNotNull { file ->
                records[gameDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/').replace("/aw-disabled-datapacks/", "/datapacks/").removeSuffix(DISABLED)]?.takeIf { it.sha1 == sums[file] }
            }.associateBy { it.sha1 }
            val versions = (if (identifyRemote && !previewOnly) remote("identify mods") { Modrinth.versionsByHash(known.filter { it !in tracked }) }.orEmpty() else emptyMap()) +
                tracked.mapValues { (_, record) -> Modrinth.Version(id = record.versionId, projectId = record.projectId,
                    name = record.title, versionNumber = record.versionNumber, datePublished = record.datePublished) }
            val projects = (if (identifyRemote && !previewOnly) remote("mod titles") { Modrinth.projects(versions.filterKeys { it !in tracked }.values.map { it.projectId }.filterNot(CurseForge::owns).distinct()) }
                .orEmpty() else emptyList()).associateBy { it.id }
            val loaders = catalogLoaders(content, kind)
            val updates = if (withUpdates && !previewOnly && versions.isNotEmpty()) {
                val latest = ContentCatalog.updates(versions, loaders, gameVersion).versions
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
                    ContentKind.DATA_PACK -> file.parent.fileName.toString() == "datapacks" && !file.name.endsWith(DISABLED)
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
        ContentKind.DATA_PACK -> file.name.endsWith(".zip") || file.name.endsWith(".zip$DISABLED") ||
            (file.isDirectory() && Files.isRegularFile(file.resolve("pack.mcmeta"), NOFOLLOW_LINKS))
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
        onInstalled: (Set<String>) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        val loaders = loadersFor(kind)
        val find = { id: String -> Modrinth.pick(ContentCatalog.versions(id, loaders, gameVersion).filter { it.primaryFile != null }) }
        val root = selectedVersion?.also { requireCompatibleVersion(it, projectId, gameVersion, loaders) } ?: find(projectId)
            ?: throw IOException("$title пока нет для ${kind.label} $gameVersion")
        requireCompatibleVersion(root, projectId, gameVersion, loaders)

        val result = installResolved(gameDir, kind, gameVersion, listOf(root), rootTitle = title, onProgress = onProgress)
        onInstalled(result.second)
        result.first
    }

    suspend fun updateMod(gameDir: Path, loader: LoaderKind, gameVersion: String, item: InstalledItem,
                          onProgress: (DownloadProgress) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        val target = item.update ?: return@withContext
        try {
            installResolved(gameDir, loader, gameVersion, listOf(target),
                disabledRoots = if (item.enabled) emptySet() else setOf(target.projectId),
                replacements = setOf(item.file), rootTitle = item.title, onProgress = onProgress)
        } catch (e: IncompatibleModException) {
            InstanceStore.update(gameDir) { it.copy(blockedUpdates = (it.blockedUpdates + target.id).distinct().takeLast(BLOCKED_LIMIT)) }
            throw e
        }
    }

    internal suspend fun enableMod(gameDir: Path, loader: LoaderKind, gameVersion: String, item: InstalledItem,
                                   onProgress: (DownloadProgress) -> Unit = {}): Pair<InstalledItem, List<InstalledItem>?> = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(item.file, NOFOLLOW_LINKS)) throw IOException("Файл мода ${item.title} не найден")
        val sum = sha1Of(item.file)
        val record = InstanceStore.get(gameDir).catalogMods.firstOrNull {
            it.fileName == "mods/${item.fileName.removeSuffix(DISABLED)}" && it.sha1 == sum
        }
        val metadata = runCatching { ModCompat.readAll(item.file) }.getOrDefault(emptyList())
        if (record == null && metadata.none { mod -> mod.depends.any { it !in setOf("minecraft", "java", "fabricloader", "quilt_loader") } })
            return@withContext setEnabled(item, true) to null
        val release = record?.let {
            try { ContentCatalog.version(it.versionId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw IOException("Не удалось проверить зависимости ${item.title} в каталоге", e) }
        }
        installResolved(gameDir, loader, gameVersion, listOfNotNull(release),
            localFiles = if (release == null) listOf(item.file) else emptyList(), replacements = setOf(item.file),
            rootTitle = item.title, onProgress = onProgress)
        item.copy(file = item.file.resolveSibling(item.fileName.removeSuffix(DISABLED)), enabled = true) to
            scan(gameDir, loader, gameVersion, identifyRemote = false)
    }

    suspend fun importMods(gameDir: Path, loader: LoaderKind, gameVersion: String, files: List<Path>,
                           onProgress: (DownloadProgress) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        val names = files.map { it.fileName.toString().dropLast(4) + ".jar" }
        if (names.map { it.lowercase() }.distinct().size != names.size) throw IOException("В выбранных файлах есть одинаковые имена")
        for (file in files) {
            val name = file.name.dropLast(4) + ".jar"
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !file.name.endsWith(".jar", true) || ContentKind.MOD.safeName(name) == null)
                throw IOException("Выбери файлы модов .jar")
            if (modsDir(gameDir).resolve(name).exists(NOFOLLOW_LINKS)) throw IOException("Файл ${file.name} уже есть в сборке")
            // Reject broken archives before making a network request or changing the profile.
            try { ZipFile(file.toFile()).use { } }
            catch (e: IOException) { throw IOException("Не удалось прочитать файл мода ${file.name}", e) }
        }
        val sums = files.associateWith(::sha1Of)
        val identified = remote("identify imported mods") { Modrinth.versionsByHash(sums.values) }.orEmpty()
        val versions = files.mapNotNull { file -> identified[sums[file]]?.let { value ->
            val matched = value.files.firstOrNull { it.sha1.equals(sums[file], true) }
                ?: throw IOException("Каталог вернул другой файл для ${file.name}")
            value.copy(files = listOf(matched.copy(filename = file.name.dropLast(4) + ".jar")))
        } }
        for (file in files.filter { sums[it] !in identified }) {
            if (ModCompat.readAll(file).isEmpty()) throw IOException("Не удалось проверить зависимости ${file.name}. Установи этот мод через каталог")
        }
        installResolved(gameDir, loader, gameVersion, versions, localFiles = files, onProgress = onProgress).first
    }

    /** Stage the complete result so dependency errors and download cancellation leave the profile intact. */
    internal suspend fun installResolved(
        gameDir: Path, loader: LoaderKind, gameVersion: String, roots: List<Modrinth.Version>,
        localFiles: List<Path> = emptyList(), disabledRoots: Set<String> = emptySet(), replacements: Set<Path> = emptySet(),
        rootTitle: String? = null, onProgress: (DownloadProgress) -> Unit = {},
        versions: (String) -> List<Modrinth.Version> = { ContentCatalog.versions(it, loadersFor(loader), gameVersion) },
        version: (String) -> Modrinth.Version = ContentCatalog::version,
        project: (String) -> Modrinth.Project = ContentCatalog::project,
        identify: (Collection<String>) -> Map<String, Modrinth.Version> = Modrinth::versionsByHash,
    ): Pair<List<String>, Set<String>> = withContext(Dispatchers.IO) {
        require(loader.isModded) { "Для установки модов выбери сборку с загрузчиком" }
        gameDir.createDirectories()
        val before = BuildMigration.fingerprint(gameDir)
        val options = InstanceStore.get(gameDir)
        val local = scan(gameDir, loader, gameVersion, identifyRemote = false)
        val unknown = local.filter { it.projectId == null }.map { it.sha1 }.filter { it.isNotBlank() }.distinct()
        val recognized = if (roots.isNotEmpty() && unknown.isNotEmpty()) identify(unknown) else emptyMap()
        val installed = local.map { item -> recognized[item.sha1]?.let {
            item.copy(projectId = it.projectId, versionId = it.id, versionNumber = it.versionNumber)
        } ?: item }
        val resolver = ModDependencies(loadersFor(loader), gameVersion, versions, version, project)
        val history = gameDir.resolve(BuildHistory.FOLDER)
        if (!history.exists(NOFOLLOW_LINKS)) Files.createDirectory(history)
        if (!Files.isDirectory(history, NOFOLLOW_LINKS)) throw IOException("Некорректная папка истории сборки")
        val staging = Files.createTempDirectory(history, ".mods-")
        val stagedMods = staging.resolve("mods").createDirectories()
        val oldMods = modsDir(gameDir)
        val existed = oldMods.exists(NOFOLLOW_LINKS)
        val metadata = gameDir.resolve(InstanceStore.FILE_NAME)
        val metadataExisted = metadata.exists(NOFOLLOW_LINKS)
        val originalMetadata = staging.resolve("previous-instance.json")
        var swapped = false
        var keepRecovery = false
        try {
            val incomingFiles = localFiles.mapIndexed { index, source ->
                val name = source.name.removeSuffix(DISABLED).dropLast(4) + ".jar"
                if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || ContentKind.MOD.safeName(name) == null)
                    throw IOException("Выбери обычные файлы модов .jar")
                val sum = sha1Of(source)
                val target = staging.resolve("incoming/$index").createDirectories().resolve(name)
                Files.copy(source, target)
                if (sha1Of(target) != sum || sha1Of(source) != sum) throw IOException("Добавляемый файл изменился во время копирования")
                target
            }
            if (existed) BuildMigration.copyTree(oldMods, stagedMods)
            if (metadataExisted) Files.copy(metadata, originalMetadata)
            InstanceStore.update(staging) { options.copy(blockedUpdates = emptyList()) }
            val existingMetadata = installed.associate { it.file to runCatching { ModCompat.readAll(it.file) }.getOrDefault(emptyList()) }
            val baseline = installed.filter { it.enabled }.flatMap { existingMetadata.getValue(it.file) }
            val baselineMissing = ModCompat.missingDependencies(baseline).map { it.mod.id to it.id }.toSet()
            val rootIds = roots.map { it.projectId }.toSet()
            val prepared = HashMap<String, Pair<Modrinth.Version, Path>>()
            val preparedMetadata = HashMap<String, List<ModMeta>>()
            val preferred = HashMap<String, Modrinth.Version>()
            val extraEnabled = linkedSetOf<Path>()
            val extraRoots = linkedMapOf<String, Modrinth.Version>()
            val imports = incomingFiles.associateBy(::sha1Of)
            val knownHashes = roots.flatMap { it.files }.mapNotNull { it.sha1?.lowercase() }.toSet()
            val untrackedIncoming = incomingFiles.filter { sha1Of(it) !in knownHashes }
            val importedMetas = untrackedIncoming.flatMap(ModCompat::readAll)
            suspend fun prepare(value: Modrinth.Version): Pair<Modrinth.Version, Path> = prepared.getOrPut(value.id) {
                val file = value.primaryFile ?: throw IOException("У обязательного мода ${value.name} нет файла")
                val matched = installed.firstOrNull { item -> item.projectId == value.projectId && item.versionId == value.id ||
                    value.files.any { it.sha1 != null && it.sha1.equals(item.sha1, true) } }
                val incoming = value.files.firstNotNullOfOrNull { it.sha1?.lowercase()?.let(imports::get) }
                val chosenFile = matched?.let { item -> value.files.firstOrNull { it.sha1.equals(item.sha1, true) } } ?: file
                val name = if (incoming != null) incoming.name.dropLast(4) + ".jar" else if (matched != null) matched.fileName.removeSuffix(DISABLED) else chosenFile.filename
                if (ContentKind.MOD.safeName(name) == null) throw IOException("Недопустимое имя файла мода")
                val destination = staging.resolve("downloads/${prepared.size}").createDirectories().resolve(name)
                val source = incoming ?: matched?.file
                if (source != null) {
                    Files.copy(source, destination)
                    val expected = if (incoming != null) sha1Of(incoming) else matched!!.sha1
                    if (sha1Of(destination) != expected) throw IOException("Файл мода изменился во время подготовки")
                } else try {
                    Downloader().run(listOf(DownloadTask(chosenFile.url, destination, chosenFile.sha1, chosenFile.size, label = name)), onProgress)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw IOException("Не удалось скачать мод ${value.name.ifBlank { value.projectId }}", e) }
                preparedMetadata[value.id] = ModCompat.readAll(destination)
                value.copy(files = listOf(chosenFile.copy(filename = name))) to destination
            }
            var resolved = emptyList<Modrinth.Version>()
            var active = emptySet<String>()
            var completed = false
            for (round in 0 until 32) {
                currentCoroutineContext().ensureActive()
                val allRoots = roots + extraRoots.values
                resolved = resolver.resolve(allRoots, installed, preferred)
                val values = resolved.associateBy { it.projectId }
                val enabled = allRoots.filterNot { it.projectId in disabledRoots }.map { it.projectId }.toMutableSet()
                enabled += resolved.filter { candidate -> installed.any { it.enabled && it.projectId == candidate.projectId } }.map { it.projectId }
                var expanded: Boolean
                do {
                    expanded = false
                    for (id in enabled.toList()) for (dep in values[id]?.dependencies.orEmpty().filter { it.type == "required" }) {
                        val child = dep.projectId ?: dep.versionId?.let { resolver.detail(it).projectId }
                        if (child in values && enabled.add(child!!)) expanded = true
                    }
                } while (expanded)
                active = enabled
                val files = resolved.associate { it.projectId to prepare(it) }
                val newMeta = resolved.associate { it.projectId to preparedMetadata.getValue(it.id) }
                if (Regex("^\\d+(?:\\.\\d+)+(?:-.*)?$").matches(gameVersion)) {
                    val wrongGame = (newMeta.values.flatten() + importedMetas).firstOrNull { meta ->
                        meta.dependencyVersions["minecraft"]?.let { !FabricVersion.satisfies(gameVersion, it) } == true
                    }
                    if (wrongGame != null) throw IOException("${wrongGame.name} требует Minecraft ${wrongGame.dependencyVersions.getValue("minecraft").joinToString(" или ")}, а в сборке $gameVersion")
                }
                val changedIds = (newMeta.filterKeys { it in active }.values.flatten() + importedMetas).map { it.id }.toSet()
                val replaced = installed.filter { item -> item.file in replacements || item.projectId in values ||
                    ModCompat.read(item.file)?.id in changedIds }.map { it.file }.toSet()
                val remaining = installed.filter { it.enabled && it.file !in replaced }.flatMap { existingMetadata.getValue(it.file) }
                val candidates = remaining + newMeta.filterKeys { it in active }.values.flatten() + importedMetas +
                    extraEnabled.flatMap { existingMetadata.getValue(it) }
                val potential = remaining + newMeta.values.flatten() + importedMetas + extraEnabled.flatMap { existingMetadata.getValue(it) }
                val inactiveIds = newMeta.filterKeys { it !in active }.values.flatten().map { it.id }.toSet()
                val topLevel = installed.filter { it.enabled && it.file !in replaced }.mapNotNull { ModCompat.read(it.file) } +
                    files.filterKeys { it in active }.values.mapNotNull { ModCompat.read(it.second) } +
                    untrackedIncoming.mapNotNull(ModCompat::read) +
                    extraEnabled.mapNotNull(ModCompat::read)
                val duplicates = topLevel.groupBy { it.id }.filterValues { it.size > 1 }.keys
                if (duplicates.isNotEmpty()) throw IOException("Выбрано несколько файлов одного мода: ${duplicates.joinToString()}")
                val missing = ModCompat.missingDependencies(candidates).filter {
                    it.mod.id in changedIds || it.id in changedIds || (it.mod.id to it.id) !in baselineMissing
                } + ModCompat.missingDependencies(potential).filter { it.mod.id in inactiveIds }
                val disabled = missing.firstNotNullOfOrNull { issue -> installed.filter { !it.enabled && it.file !in replaced && it.file !in extraEnabled }
                    .firstOrNull { item -> existingMetadata.getValue(item.file).any { (it.id == issue.id || issue.id in it.provides) && FabricVersion.satisfies(it.version, issue.ranges) } } }
                if (disabled != null) {
                    if (disabled.projectId != null && disabled.versionId != null) extraRoots[disabled.projectId] = resolver.detail(disabled.versionId)
                    else extraEnabled.add(disabled.file)
                    continue
                }
                val problem = missing.firstOrNull()
                if (problem != null) {
                    val dependency = newMeta.entries.firstOrNull { (_, metas) -> metas.any { it.id == problem.id || problem.id in it.provides } }?.key
                    val pinned = resolved.flatMap { it.dependencies }.mapNotNull { it.versionId }.toSet()
                    if (dependency != null && allRoots.none { it.projectId == dependency } && values[dependency]?.id !in pinned) {
                        val requirements = potential.flatMap { it.dependencyVersions[problem.id]?.let { ranges -> listOf(ranges) }.orEmpty() }
                        val alternative = resolver.candidates(dependency).filter { it.id != values[dependency]?.id }.take(16).firstOrNull { candidate ->
                            prepare(candidate)
                            val metas = preparedMetadata.getValue(candidate.id)
                            requirements.all { ranges -> metas.any { (it.id == problem.id || problem.id in it.provides) && FabricVersion.satisfies(it.version, ranges) } }
                        }
                        if (alternative != null) { preferred[dependency] = alternative; continue }
                    }
                    throw IOException(problem.text + ". Подходящую обязательную зависимость установить не удалось")
                }
                val conflicts = ModCompat.conflictsAmong(candidates).filter { it.mod.id in changedIds || it.other.id in changedIds }
                if (conflicts.isNotEmpty()) throw IncompatibleModException(conflicts, "оставил прежнее состояние сборки")
                for (candidate in resolved.filter { it.projectId in active }) {
                    for (excluded in candidate.dependencies.filter { it.type == "incompatible" }) {
                        val id = excluded.projectId ?: excluded.versionId?.let { resolver.detail(it).projectId } ?: continue
                        val present = resolved.any { it.projectId == id && id in active && (excluded.versionId == null || excluded.versionId == it.id) } ||
                            installed.any { it.enabled && it.file !in replaced && it.projectId == id && (excluded.versionId == null || excluded.versionId == it.versionId) }
                        if (present) throw IOException("${candidate.name.ifBlank { candidate.projectId }} несовместим с модом ${resolver.title(id)}")
                    }
                }
                val targetNames = files.map { (id, pair) -> pair.first.primaryFile!!.filename + if (id in active) "" else DISABLED } +
                    untrackedIncoming.map { it.name }
                if (targetNames.map { it.lowercase() }.distinct().size != targetNames.size) throw IOException("У модов совпадают имена файлов")
                for (item in installed.filter { it.file in replaced }) Files.deleteIfExists(stagedMods.resolve(item.fileName))
                val removedRecords = installed.filter { it.file in replaced }.map { "mods/${it.fileName.removeSuffix(DISABLED)}" }.toSet()
                InstanceStore.update(staging) { it.copy(catalogMods = it.catalogMods.filterNot { record -> record.fileName in removedRecords }) }
                for ((id, pair) in files) {
                    val name = pair.first.primaryFile!!.filename + if (id in active) "" else DISABLED
                    val target = stagedMods.resolve(name)
                    if (target.exists(NOFOLLOW_LINKS)) throw IOException("В сборке уже есть другой файл $name")
                    Files.copy(pair.second, target)
                    val title = if (id in rootIds && rootTitle != null) rootTitle else resolver.title(id).takeIf { it != id }
                        ?: pair.first.name.ifBlank { id }
                    rememberCatalog(staging, ContentKind.MOD, pair.first, title,
                        iconUrl = installed.firstOrNull { it.projectId == id }?.iconUrl, lookupIcon = false)
                }
                for (path in untrackedIncoming)
                    Files.copy(path, stagedMods.resolve(path.name))
                for (path in extraEnabled) {
                    val disabledPath = stagedMods.resolve(path.fileName.toString())
                    Files.move(disabledPath, disabledPath.resolveSibling(path.fileName.toString().removeSuffix(DISABLED)))
                }
                completed = true
                break
            }
            if (!completed) throw IOException("Не удалось согласовать зависимости модов")
            if (BuildMigration.fingerprint(gameDir) != before) throw IOException("Сборка изменилась во время загрузки. Повтори установку")
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                try {
                    if (existed) Files.move(oldMods, staging.resolve("previous-mods"))
                    swapped = true
                    Files.move(stagedMods, oldMods)
                    Files.copy(staging.resolve(InstanceStore.FILE_NAME), metadata, StandardCopyOption.REPLACE_EXISTING)
                    InstanceStore.forget(gameDir)
                } catch (failure: Throwable) {
                    if (swapped) try {
                        Storage.deleteTree(oldMods)
                        if (existed) Files.move(staging.resolve("previous-mods"), oldMods)
                        if (metadataExisted) Files.copy(originalMetadata, metadata, StandardCopyOption.REPLACE_EXISTING)
                        else Files.deleteIfExists(metadata)
                        InstanceStore.forget(gameDir)
                    } catch (rollback: Throwable) {
                        keepRecovery = true
                        failure.addSuppressed(rollback)
                        throw IOException("Не удалось вернуть прежние файлы. Резервная копия: $staging", failure)
                    }
                    throw failure
                }
            }
            val titles = resolved.map { candidate -> if (candidate.projectId in rootIds && rootTitle != null) rootTitle
                else resolver.title(candidate.projectId).takeIf { it != candidate.projectId } ?: candidate.name.ifBlank { candidate.projectId } }
            titles to resolved.filter { it.projectId in active }.map { it.projectId }.toSet()
        } catch (e: FileSystemException) {
            throw IOException("Не удалось изменить файлы сборки. Закрой Minecraft и программы, которые используют её папку", e)
        } finally {
            withContext(NonCancellable) {
                InstanceStore.forget(staging)
                if (!keepRecovery) Storage.deleteTree(staging)
            }
        }
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
        val irisEnabled = withContext(Dispatchers.IO) {
            scan(gameDir, runLoader, gameVersion, identifyRemote = false).any { it.enabled && it.projectId == IRIS } ||
                ModCompat.enabledIn(modsDir(gameDir)).any { it.id == "iris" }
        }
        val iris = if (irisEnabled) emptyList() else install(gameDir, runLoader, gameVersion, IRIS, "Iris", present, onProgress = onProgress)
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

    fun dataPackWorlds(gameDir: Path): List<Path> {
        val saves = gameDir.toAbsolutePath().normalize().resolve("saves")
        if (!Files.isDirectory(saves, NOFOLLOW_LINKS)) return emptyList()
        return Files.list(saves).use { paths -> paths.filter {
            Files.isDirectory(it, NOFOLLOW_LINKS) && Files.isRegularFile(it.resolve("level.dat"), NOFOLLOW_LINKS)
        }.sorted().toList() }
    }

    suspend fun installDataPack(
        gameDir: Path, world: Path, gameVersion: String, projectId: String, title: String,
        selectedVersion: Modrinth.Version? = null, onProgress: (DownloadProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val targetWorld = world.toAbsolutePath().normalize()
        if (targetWorld !in dataPackWorlds(gameDir)) throw IOException("Выбери существующий мир этой сборки")
        val version = selectedVersion?.also { requireCompatibleVersion(it, projectId, gameVersion, listOf("datapack")) }
            ?: Modrinth.pick(ContentCatalog.versions(projectId, listOf("datapack"), gameVersion).filter { it.primaryFile != null })
            ?: throw IOException("$title пока нет для $gameVersion")
        val file = version.primaryFile ?: throw IOException("У $title нет файла для скачивания")
        val name = ContentKind.DATA_PACK.safeName(file.filename) ?: throw IOException("Выбери ZIP-версию датапака")
        val dir = targetWorld.resolve("datapacks")
        if (Files.exists(dir, NOFOLLOW_LINKS) && !Files.isDirectory(dir, NOFOLLOW_LINKS))
            throw IOException("Папка датапаков содержит ссылку или неподдерживаемый файл")
        dir.createDirectories()
        val target = dir.resolve(name)
        val current = InstanceStore.get(gameDir).catalogMods.filter { it.projectId == projectId &&
            it.fileName.startsWith("saves/${targetWorld.fileName}/datapacks/") }
        if (Files.exists(target, NOFOLLOW_LINKS) && current.none { it.fileName.endsWith("/$name") })
            throw IOException("В этом мире уже есть файл $name")
        val download = dir.resolve("$name.download")
        try {
            Downloader().run(listOf(DownloadTask(file.url, download, file.sha1, file.size, label = name)), onProgress)
            normalizeDataPack(download)
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING)
            current.filter { !it.fileName.endsWith("/$name") }.forEach { record ->
                val oldName = ContentKind.DATA_PACK.safeName(record.fileName.substringAfterLast('/')) ?: return@forEach
                dir.resolve(oldName).deleteIfExists(); dir.resolve(oldName + DISABLED).deleteIfExists()
            }
            rememberCatalog(gameDir, ContentKind.DATA_PACK, version, title, directory = dir)
        } finally { download.deleteIfExists() }
    }

    internal fun normalizeDataPack(path: Path) {
        val normalized = path.resolveSibling(path.name + ".normalized")
        try {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > 10_000 || entries.any { it.size > 64L * 1024 * 1024 } ||
                    entries.sumOf { maxOf(0, it.size) } > 256L * 1024 * 1024) throw IOException("Архив датапака слишком большой")
                val metadata = zip.getEntry("pack.mcmeta") ?: entries.singleOrNull {
                    it.name.endsWith("/pack.mcmeta") && it.name.count { c -> c == '/' } == 1
                } ?: throw IOException("В архиве датапака нет pack.mcmeta")
                val text = zip.getInputStream(metadata).use { it.readNBytes(1024 * 1024 + 1) }
                if (text.size > 1024 * 1024 || runCatching {
                    (ru.aw.launcher.core.Json.parseToJsonElement(text.decodeToString()) as? JsonObject)?.get("pack") is JsonObject
                }.getOrDefault(false).not()) throw IOException("Некорректная структура архива датапака")
                val prefix = metadata.name.removeSuffix("pack.mcmeta")
                if (entries.any { entry -> entry.name.startsWith('/') || '\\' in entry.name || ':' in entry.name ||
                    entry.name.trimEnd('/').split('/').any { it == ".." || it == "." || it.isBlank() } ||
                    (prefix.isNotEmpty() && !entry.name.startsWith(prefix)) }) throw IOException("Некорректная структура архива датапака")
                if (prefix.isEmpty()) return
                ZipOutputStream(Files.newOutputStream(normalized)).use { output ->
                    var total = 0L
                    val buffer = ByteArray(8192)
                    for (entry in entries.filterNot { it.isDirectory }) {
                        output.putNextEntry(ZipEntry(entry.name.removePrefix(prefix)))
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                val read = input.read(buffer); if (read < 0) break
                                total += read
                                if (total > 256L * 1024 * 1024) throw IOException("Архив датапака слишком большой")
                                output.write(buffer, 0, read)
                            }
                        }
                        output.closeEntry()
                    }
                }
            }
            Files.move(normalized, path, StandardCopyOption.REPLACE_EXISTING)
        } finally { normalized.deleteIfExists() }
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
    internal fun dependencyApplies(platforms: List<String>, selected: List<String>): Boolean =
        platforms.isEmpty() || platforms.any { it in selected }

    suspend fun update(mod: InstalledItem, onProgress: (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val version = mod.update ?: return@withContext
        val file = version.primaryFile ?: throw IOException("У ${version.name} нет файла для скачивания")
        val name = mod.kind.safeName(file.filename)
            ?: throw IOException("Недопустимое имя файла: ${file.filename}")
        val target = mod.file.resolveSibling(if (mod.enabled) name else name + DISABLED)
        val download = mod.file.resolveSibling("$name.download")
        Downloader().run(listOf(DownloadTask(file.url, download, file.sha1, file.size, label = name)), onProgress)
        if (mod.kind == ContentKind.DATA_PACK) try { normalizeDataPack(download) }
            catch (e: Exception) { download.deleteIfExists(); throw e }
        if (mod.enabled && mod.kind == ContentKind.MOD) {
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
        if (mod.enabled) gameDirOf(mod)?.let { dir ->
            when (mod.kind) {
                ContentKind.RESOURCE_PACK -> {
                    if (target != mod.file) GameOptions.setResourcePack(dir, mod.fileName, false)
                    GameOptions.setResourcePack(dir, target.name, true)
                }
                ContentKind.SHADER -> GameOptions.setShader(dir, target.name)
                else -> Unit
            }
        }
        gameDirOf(mod)?.let { dir -> rememberCatalog(dir, mod.kind, version, mod.title, mod.iconUrl, mod.file.parent) }
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
                    ?: throw IOException("${conflict.text}. Подходящей версии в каталогах нет — выключи один из них в «Моды»")
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
        val records = sides.firstOrNull()?.first?.parent?.parent?.let { InstanceStore.get(it).catalogMods }.orEmpty()
        val tracked = sides.mapNotNull { (file, _) -> records.firstOrNull {
            it.fileName == "mods/${file.name}" && it.sha1 == hashOf(file)
        } }.associate { it.sha1 to Modrinth.Version(it.versionId, it.projectId, datePublished = it.datePublished) }
        val known = Modrinth.versionsByHash(sides.map { hashOf(it.first) }.filter { it !in tracked }) + tracked
        val ordered = sides.mapNotNull { side -> known[hashOf(side.first)]?.let { side to it } }
            .sortedBy { (_, version) -> if (version.versionType == "release") 1 else 0 }
        val loaders = loadersFor(kind)
        for ((side, current) in ordered) {
            val (jar, meta) = side
            val candidates = ContentCatalog.versions(current.projectId, loaders, gameVersion)
                .filter { it.versionType == "release" && it.id != current.id }
                .take(FIX_CANDIDATES)
            for (candidate in candidates) {
                val file = candidate.primaryFile ?: continue
                val name = ContentKind.MOD.safeName(file.filename) ?: continue
                try {
                    installResolved(jar.parent.parent, kind, gameVersion, listOf(candidate),
                        replacements = setOf(jar), rootTitle = meta.name, onProgress = onProgress)
                } catch (e: IncompatibleModException) {
                    rejected += candidate.id
                    continue
                }
                rejected += current.id
                Log.info("mod conflict fixed: ${jar.name} -> $name")
                return "${meta.name} ${meta.shortVersion} → ${candidate.versionNumber}"
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
            ContentKind.MOD, ContentKind.DATA_PACK -> {
                val base = item.fileName.removeSuffix(DISABLED)
                val target = if (item.kind == ContentKind.DATA_PACK && item.file.isDirectory()) {
                    val parent = item.file.parent.parent.resolve(if (enabled) "datapacks" else "aw-disabled-datapacks")
                    if (Files.exists(parent, NOFOLLOW_LINKS) && !Files.isDirectory(parent, NOFOLLOW_LINKS))
                        throw IOException("Некорректная папка датапаков")
                    parent.createDirectories().resolve(base)
                } else item.file.resolveSibling(if (enabled) base else base + DISABLED)
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
            ContentKind.MOD, ContentKind.DATA_PACK -> clearBlocked(gameDir)
            ContentKind.SHADER -> if (item.enabled) GameOptions.setShader(gameDir, null)
            ContentKind.RESOURCE_PACK -> GameOptions.setResourcePack(gameDir, item.fileName, enabled = false)
        }
    }

    private fun gameDirOf(item: InstalledItem): Path? = if (item.kind == ContentKind.DATA_PACK)
        item.file.parent?.parent?.parent?.parent else item.file.parent?.parent

    internal fun rememberCatalog(gameDir: Path, kind: ContentKind, version: Modrinth.Version, title: String, iconUrl: String? = null,
                                 directory: Path = kind.dir(gameDir), lookupIcon: Boolean = true) {
        val file = version.primaryFile ?: return
        val name = kind.safeName(file.filename) ?: return
        val path = directory.resolve(name).let { if (it.exists()) it else it.resolveSibling(name + DISABLED) }
        val icon = iconUrl ?: if (lookupIcon && CurseForge.owns(version.projectId)) remote("CurseForge icon") { CurseForge.projectInfo(version.projectId).logo?.url } else null
        val relative = gameDir.toAbsolutePath().normalize().relativize(directory.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val record = ManagedMod(version.projectId, version.id, "$relative/$name", hashOf(path), title,
            version.versionNumber, version.datePublished, icon)
        InstanceStore.update(gameDir) { options -> options.copy(catalogMods = options.catalogMods.filterNot {
            it.fileName == record.fileName || (it.projectId == record.projectId && it.fileName.startsWith("$relative/"))
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
