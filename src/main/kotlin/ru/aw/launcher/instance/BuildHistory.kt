package ru.aw.launcher.instance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.ContentKind
import ru.aw.launcher.mods.ModManager
import ru.aw.launcher.mods.ModCompat
import ru.aw.launcher.mods.InstalledItem
import ru.aw.launcher.packs.Modpacks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class BuildChange(val title: String, val before: String?, val after: String?)

@Serializable
data class BuildHistoryEntry(
    val id: String,
    val createdAt: Long,
    val title: String,
    val gameVersion: String,
    val loader: LoaderKind,
    val loaderVersion: String? = null,
    val includesWorlds: Boolean = false,
    val roots: List<String>,
    val files: Map<String, String>,
    val directories: List<String>,
    val items: List<HistoryItem> = emptyList(),
    val changes: List<BuildChange> = emptyList(),
    val completed: Boolean = false,
    val targetGameVersion: String = gameVersion,
    val sizeBytes: Long = 0,
    val interrupted: Boolean = false,
)

@Serializable
data class HistoryItem(val key: String, val path: String, val title: String, val version: String)

/** Each entry contains the state BEFORE the operation. World data is opt-in. */
object BuildHistory {
    const val FOLDER = "aw-build-history"
    private const val MANIFEST = "snapshot.json"
    private val fixedRoots = listOf("mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks",
        "options.txt", "optionsof.txt", "optionsshaders.txt", "iris.properties", InstanceStore.FILE_NAME, Modpacks.MANIFEST)
    private val excludedRoots = setOf(FOLDER, "aw-migration-backups", "logs", "crash-reports", "screenshots",
        "assets", "libraries", "versions", "natives", ".cache", "cache", ".fabric", ".quilt", "latest-game.log",
        ".git", "accounts.json", "launcher_accounts.json", "launcher_msa_credentials.bin", "launcher_profiles.json")
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    suspend fun list(root: Path): List<BuildHistoryEntry> = withContext(Dispatchers.IO) {
        val history = root.resolve(FOLDER)
        if (!history.exists(NOFOLLOW_LINKS)) return@withContext emptyList()
        directory(history)
        Files.list(history).use { paths -> paths.filter { idPattern.matches(it.fileName.toString()) }.map { path ->
            try { read(root, path.fileName.toString()) }
            catch (e: Exception) { Log.warn("Unreadable build snapshot: $path", e); null }
        }.toList().filterNotNull().sortedByDescending { it.createdAt } }
    }

    suspend fun create(root: Path, gameVersion: String, loader: LoaderKind, title: String,
                       loaderVersion: String? = null, includeWorlds: Boolean? = null, completed: Boolean = false,
                       knownItems: List<InstalledItem> = emptyList()): BuildHistoryEntry = withContext(Dispatchers.IO) {
        if (!root.exists(NOFOLLOW_LINKS)) Files.createDirectories(root)
        directory(root)
        val worlds = includeWorlds ?: InstanceStore.get(root).historyIncludeWorlds
        val history = root.resolve(FOLDER)
        if (!history.exists(NOFOLLOW_LINKS)) Files.createDirectory(history)
        directory(history)
        val id = UUID.randomUUID().toString()
        val pending = history.resolve(".$id")
        Files.createDirectory(pending)
        try {
            val roots = roots(root, worlds)
            val before = inventory(root, roots)
            val items = items(root, gameVersion, loader, knownItems)
            val data = Files.createDirectory(pending.resolve("data"))
            copy(root, data, before)
            if (inventory(root, roots) != before || inventory(data, roots) != before)
                throw IOException("Сборка изменилась во время сохранения. Повтори действие")
            val entry = BuildHistoryEntry(id, System.currentTimeMillis(), title, gameVersion, loader,
                loaderVersion, worlds, roots, before.first, before.second, items, completed = completed,
                sizeBytes = before.first.keys.sumOf { Files.size(data.resolve(it)) })
            pending.resolve(MANIFEST).writeAtomically(Json.encodeToString(entry))
            Files.move(pending, history.resolve(id))
            entry
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { deleteTree(pending) }.onFailure { failure.addSuppressed(it) } }
            throw failure
        }
    }

    /** Also runs after failed or cancelled batches, preserving recovery for partially applied changes. */
    suspend fun finish(root: Path, entry: BuildHistoryEntry, targetGameVersion: String = entry.gameVersion,
                       interrupted: Boolean = false) = withContext(NonCancellable + Dispatchers.IO) {
        val current = inventory(root, (entry.roots + roots(root, entry.includesWorlds)).distinct())
        val actualVersion = Modpacks.read(root)?.gameVersion ?: targetGameVersion
        if (current.first == entry.files && current.second == entry.directories && actualVersion == entry.gameVersion) {
            deleteTree(location(root, entry.id))
        } else {
            val fresh = items(root, entry.gameVersion, entry.loader)
            val oldByKey = entry.items.associateBy { it.key }
            val newByKey = fresh.associateBy { it.key }
            val changes = (oldByKey.keys + newByKey.keys).mapNotNull { key ->
                val old = oldByKey[key]; val new = newByKey[key]
                if (old == new && old?.let { entry.files[it.path] == current.first[it.path] } == true) null
                else BuildChange(new?.title ?: old!!.title, old?.version, new?.version)
            }
            val allChanges = changes + if (actualVersion != entry.gameVersion)
                listOf(BuildChange("Minecraft", entry.gameVersion, actualVersion)) else emptyList()
            location(root, entry.id).resolve(MANIFEST).writeAtomically(Json.encodeToString(entry.copy(
                title = if (entry.title == "Обновление проектов") "Обновлено проектов: ${changes.count { it.before != null && it.after != null }}" else entry.title,
                changes = allChanges, completed = true, targetGameVersion = actualVersion, interrupted = interrupted)))
        }
    }

    suspend fun <T> record(root: Path, gameVersion: String, loader: LoaderKind, title: String,
                           loaderVersion: String? = null, targetGameVersion: String = gameVersion,
                           knownItems: List<InstalledItem> = emptyList(), block: suspend () -> T): T {
        val entry = create(root, gameVersion, loader, title, loaderVersion, knownItems = knownItems)
        var failure: Throwable? = null
        try { return block() }
        catch (e: Throwable) { failure = e; throw e }
        finally {
            try { finish(root, entry, if (failure == null) targetGameVersion else gameVersion, interrupted = failure != null) }
            catch (e: Throwable) { if (failure != null) failure.addSuppressed(e) else throw e }
        }
    }

    suspend fun delete(root: Path, id: String) = withContext(Dispatchers.IO) { deleteTree(location(root, id)) }

    /** Verify and stage all files first; moves are reversible and cannot be cancelled halfway through. */
    suspend fun restore(root: Path, id: String, gameVersion: String, loader: LoaderKind, loaderVersion: String?,
                        restoreWorlds: Boolean = false, allowLoaderChange: Boolean = false,
                        commitVersion: (BuildHistoryEntry) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        directory(root)
        val entry = read(root, id)
        require(entry.loader == loader || allowLoaderChange) { "Снимок относится к другому загрузчику" }
        require(!restoreWorlds || entry.includesWorlds) { "В этом снимке нет миров" }
        val data = location(root, id).resolve("data")
        directory(data)
        if (inventory(data, entry.roots) != (entry.files to entry.directories)) throw IOException("Файлы снимка повреждены или изменены")
        val selected = entry.roots.filter { restoreWorlds || it != "saves" }
        val packRoots = if (entry.includesWorlds && !restoreWorlds) roots(data, false).filter { it.startsWith("saves/") } else emptyList()
        val targetRoots = (selected + packRoots + roots(root, restoreWorlds)).distinct()
        targetRoots.forEach { checkedPath(root, it) }
        val before = create(root, gameVersion, loader, "Перед откатом", loaderVersion, restoreWorlds)
        val transaction = root.resolve(FOLDER).resolve(".restore-" + UUID.randomUUID())
        Files.createDirectory(transaction)
        val staged = Files.createDirectory(transaction.resolve("prepared"))
        val previous = Files.createDirectory(transaction.resolve("previous"))
        val moved = ArrayList<String>()
        val placed = ArrayList<String>()
        var preserve = false
        var committed = false
        try {
            val expected = inventory(data, targetRoots)
            copy(data, staged, expected)
            if (inventory(staged, targetRoots) != expected) throw IOException("Не удалось проверить подготовленный откат")
            if (inventory(root, before.roots) != (before.files to before.directories))
                throw IOException("Сборка изменилась во время подготовки отката. Повтори действие")
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                try {
                    for (name in targetRoots) {
                        val target = checkedPath(root, name)
                        val old = previous.resolve(name)
                        if (target.exists(NOFOLLOW_LINKS)) {
                            Files.createDirectories(old.parent)
                            Files.move(target, old)
                            moved += name
                        }
                        val source = staged.resolve(name)
                        if (source.exists(NOFOLLOW_LINKS)) {
                            Files.createDirectories(target.parent)
                            Files.move(source, target)
                            placed += name
                        }
                    }
                    InstanceStore.forget(root)
                    commitVersion(entry)
                    committed = true
                } catch (failure: Throwable) {
                    try {
                        placed.asReversed().forEach { deleteTree(root.resolve(it)) }
                        moved.asReversed().forEach { name ->
                            Files.createDirectories(root.resolve(name).parent)
                            Files.move(previous.resolve(name), root.resolve(name))
                        }
                        InstanceStore.forget(root)
                    } catch (rollback: Throwable) {
                        preserve = true
                        failure.addSuppressed(rollback)
                        throw IOException("Не удалось завершить откат. Файлы сохранены в $transaction", failure)
                    }
                    throw failure
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (!preserve) runCatching { deleteTree(transaction) }.onFailure { Log.warn("Restore staging cleanup failed", it) }
                finish(root, before, if (committed) entry.gameVersion else gameVersion)
            }
        }
    }

    private fun read(root: Path, id: String): BuildHistoryEntry {
        val path = location(root, id)
        directory(path)
        val file = path.resolve(MANIFEST)
        regular(file)
        val entry = Json.decodeFromString<BuildHistoryEntry>(file.readText())
        require(entry.id == id && entry.gameVersion.isNotBlank()) { "Некорректный снимок сборки" }
        require(entry.roots.distinctBy { it.lowercase() }.size == entry.roots.size && entry.roots.all(::allowedRoot)) { "Некорректные папки снимка" }
        require(entry.includesWorlds == ("saves" in entry.roots)) { "Некорректный состав снимка миров" }
        require(entry.roots.none { a -> entry.roots.any { b -> a != b && a.startsWith("$b/") } }) { "Пересекающиеся папки снимка" }
        for (name in entry.files.keys + entry.directories) {
            safeRelative(name)
            require(entry.roots.any { name == it || name.startsWith("$it/") }) { "Файл вне снимка" }
        }
        return entry
    }

    private fun location(root: Path, id: String): Path {
        require(idPattern.matches(id)) { "Некорректный ID снимка" }
        directory(root)
        directory(root.resolve(FOLDER))
        return root.resolve(FOLDER).resolve(id)
    }

    private fun allowedRoot(name: String): Boolean = (!name.equals("saves", ignoreCase = true) && '/' !in name && name.lowercase() !in excludedRoots &&
        runCatching { safeRelative(name) }.isSuccess) || name == "saves" ||
        (name.split('/').let { it.size == 3 && it[0] == "saves" && it[1].isNotBlank() && it[2] in listOf("datapacks", "aw-disabled-datapacks") }
            && runCatching { safeRelative(name) }.isSuccess)

    private fun roots(root: Path, worlds: Boolean): List<String> {
        val extra = Files.list(root).use { paths -> paths.map { it.fileName.toString() }
            .filter { !it.equals("saves", ignoreCase = true) && it.lowercase() !in excludedRoots }.toList() }
        return (fixedRoots + extra).distinctBy { it.lowercase() } + if (worlds) listOf("saves") else {
            val saves = root.resolve("saves")
            if (!saves.exists(NOFOLLOW_LINKS)) emptyList() else {
                directory(saves)
                Files.list(saves).use { paths -> paths.toList().filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.flatMap { world ->
                    directory(world)
                    listOf("saves/${world.fileName}/datapacks", "saves/${world.fileName}/aw-disabled-datapacks")
                } }
            }
        }
    }

    private fun safeRelative(name: String) {
        require(name.isNotBlank() && '\\' !in name && ':' !in name && !name.startsWith('/') &&
            name.split('/').none { it.isBlank() || it.endsWith('.') || it.endsWith(' ') }) { "Некорректный путь снимка" }
    }

    private fun checkedPath(root: Path, name: String): Path {
        safeRelative(name)
        var path = root
        for (part in name.split('/')) {
            path = path.resolve(part)
            if (path.exists(NOFOLLOW_LINKS)) attributes(path)
        }
        return path
    }

    private fun attributes(path: Path): BasicFileAttributes {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attrs.isSymbolicLink || attrs.isOther || (!attrs.isRegularFile && !attrs.isDirectory))
            throw IOException("В сборке есть ссылка или неподдерживаемый файл: $path")
        return attrs
    }
    private fun directory(path: Path) { if (!attributes(path).isDirectory) throw IOException("Не найдена папка: $path") }
    private fun regular(path: Path) { if (!attributes(path).isRegularFile) throw IOException("Не найден файл: $path") }

    private suspend fun inventory(root: Path, roots: List<String>): Pair<Map<String, String>, List<String>> {
        val files = sortedMapOf<String, String>()
        val dirs = sortedSetOf<String>()
        for (name in roots) {
            val source = checkedPath(root, name)
            if (!source.exists(NOFOLLOW_LINKS)) continue
            Files.walk(source).use { paths ->
                for (path in paths.toList()) {
                    currentCoroutineContext().ensureActive()
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    if (attributes(path).isDirectory) dirs += relative else files[relative] = sha1Of(path)
                }
            }
        }
        return files to dirs.toList()
    }

    private suspend fun copy(source: Path, target: Path, inventory: Pair<Map<String, String>, List<String>>) {
        for (name in inventory.second) { currentCoroutineContext().ensureActive(); Files.createDirectories(target.resolve(name)) }
        for (name in inventory.first.keys) {
            currentCoroutineContext().ensureActive()
            val file = checkedPath(source, name)
            regular(file)
            val dest = target.resolve(name)
            Files.createDirectories(dest.parent)
            Files.copy(file, dest)
        }
    }

    private suspend fun items(root: Path, version: String, loader: LoaderKind, known: List<InstalledItem> = emptyList()): List<HistoryItem> =
        ContentKind.entries.flatMap { kind -> ModManager.scan(root, loader, version, kind, identifyRemote = false).map { scanned ->
            val item = known.firstOrNull { it.file == scanned.file && it.sha1.isNotEmpty() && it.sha1 == scanned.sha1 }
                ?.copy(enabled = scanned.enabled) ?: scanned
            val meta = if (kind == ContentKind.MOD) ModCompat.read(item.file) else null
            val relative = root.relativize(item.file).toString().replace('\\', '/')
            val context = if (kind == ContentKind.DATA_PACK) item.worldName.orEmpty() else ""
            HistoryItem("${kind.name}:$context:${meta?.id ?: item.projectId ?: item.fileName.removeSuffix(".disabled")}", relative,
                meta?.name ?: item.title, item.versionNumber.ifBlank { meta?.version ?: item.fileName.removeSuffix(".disabled") } + if (item.enabled) "" else " (выключен)")
        } }

    private fun deleteTree(path: Path) {
        if (!path.exists(NOFOLLOW_LINKS)) return
        val root = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (root.isSymbolicLink || root.isOther) { Files.delete(path); return }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink || attrs.isOther) { Files.delete(dir); return FileVisitResult.SKIP_SUBTREE }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
