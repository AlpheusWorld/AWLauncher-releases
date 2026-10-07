package ru.aw.launcher.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import ru.aw.launcher.core.Shell
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.*
import ru.aw.launcher.net.DownloadProgress
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.IOException
import kotlin.io.path.createDirectories

internal class ContentModel(
    private val dir: Path,
    private val loader: LoaderKind,
    private val gameVersion: String,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    source: CatalogSource,
    private val downloads: DownloadQueue? = null,
    private val entryKey: String = dir.toString(),
    private val entryTitle: String = gameVersion,
) {
    private var provider = source
    private val searchCache = HashMap<CatalogSource, Map<ContentKind, CatalogSearch>>()
    val searches: Map<ContentKind, CatalogSearch> get() = searchCache.getOrPut(provider) {
        val selected = provider
        ContentKind.entries.associateWith { kind -> CatalogSearch(scope) { query, offset ->
            ContentCatalog.search(selected, kind.projectType, query, ModManager.catalogLoaders(kind, loader), gameVersion, offset)
        } }
    }
    fun useSource(source: CatalogSource) { provider = source }
    var installed by mutableStateOf<Map<ContentKind, List<InstalledItem>>>(emptyMap())
    var scanned by mutableStateOf(false)
    var working by mutableStateOf<Set<String>>(emptySet())
    private var currentProgress by mutableStateOf<DownloadProgress?>(null)
    private var activeReport: DownloadQueue.Reporter? = null
    var progress: DownloadProgress?
        get() = currentProgress
        set(value) { currentProgress = value; activeReport?.progress(value) }
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    private var scanJob: Job? = null
    private var scanGeneration = 0L
    private var scannedAt = 0L
    var refreshing by mutableStateOf(false)
        private set

    val installedProjects: Set<String> get() = installed.values.flatten().mapNotNull { it.projectId }.toSet()
    val updates: List<InstalledItem> get() = installed[ContentKind.MOD].orEmpty().filter { it.update != null }
    val migrating: Boolean get() = downloads?.contains("migration:$entryKey") == true
    fun waiting(key: String): Boolean = downloads?.items?.any {
        it.key == "content:$entryKey:$key" && it.status == DownloadStatus.WAITING
    } == true

    fun ensureScanned() {
        if (!scanned || System.currentTimeMillis() - scannedAt > 600_000) rescan()
    }

    fun rescan() = startScan(clearError = true)

    private fun startScan(clearError: Boolean) {
        if (refreshing || working.isNotEmpty()) return
        refreshing = true
        if (clearError) error = null
        val generation = ++scanGeneration
        scanJob = scope.launch {
            try {
                if (!scanned) {
                    val local = scanAll(remote = false)
                    if (generation != scanGeneration) return@launch
                    installed = retainRows(local)
                    scanned = true
                }
                val remote = scanAll()
                if (generation != scanGeneration) return@launch
                installed = retainRows(remote)
                scannedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == scanGeneration) error = e.message ?: "Не удалось прочитать содержимое сборки"
            } finally {
                if (generation == scanGeneration) refreshing = false
            }
        }
    }

    private fun stopScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        refreshing = false
    }

    private fun retainRows(fresh: Map<ContentKind, List<InstalledItem>>): Map<ContentKind, List<InstalledItem>> = fresh.mapValues { (kind, rows) ->
        val previous = installed[kind].orEmpty().associateBy { it.file }
        rows.map { row ->
            val old = previous[row.file] ?: return@map row
            if (row.sha1 == old.sha1 && row.projectId == null && old.projectId != null) {
                row.copy(rowKey = old.rowKey, projectId = old.projectId, title = old.title, versionNumber = old.versionNumber,
                    iconUrl = old.iconUrl, versionId = old.versionId, update = old.update)
            } else row.copy(rowKey = old.rowKey)
        }
    }

    private suspend fun scanAll(remote: Boolean = true): Map<ContentKind, List<InstalledItem>> = coroutineScope {
        ContentKind.entries.associateWith { kind -> async {
            ModManager.scan(dir, loader, gameVersion, kind, withUpdates = remote && kind == ContentKind.MOD && loader.isModded, identifyRemote = remote)
        } }.mapValues { it.value.await() }
    }

    fun addFiles(files: List<Path>, kind: ContentKind) = work(ALL) {
        withContext(Dispatchers.IO) {
            val destination = kind.dir(dir).createDirectories()
            val incoming = files.map { file ->
                ensureActive()
                if (!Files.isRegularFile(file) || !file.fileName.toString().endsWith(kind.extension, ignoreCase = true))
                    throw IOException("Выбери файлы ${kind.extension}")
                val filename = file.fileName.toString().dropLast(kind.extension.length) + kind.extension
                val target = destination.resolve(filename)
                if (Files.exists(target)) throw IOException("Файл ${file.fileName} уже есть в сборке")
                file to target
            }
            if (incoming.map { it.second.fileName.toString().lowercase() }.distinct().size != incoming.size)
                throw IOException("В выбранных файлах есть одинаковые имена")
            incoming.forEach { (source, target) ->
                ensureActive()
                Files.copy(source, target)
            }
        }
        message = "Добавлено файлов: ${files.size}"
    }

    fun install(kind: ContentKind, hit: Modrinth.SearchHit, version: Modrinth.Version? = null) = work(hit.projectId, "${hit.title} · $entryTitle") {
        version?.let { ModManager.requireCompatibleVersion(it, hit.projectId, gameVersion, ModManager.catalogLoaders(kind, loader)) }
        message = when (kind) {
            ContentKind.SHADER -> {
                val titles = ModManager.installShader(dir, loader, gameVersion, hit.projectId, hit.title, installedProjects, selectedVersion = version) { progress = it }
                listOfNotNull(
                    "${hit.title} установлен и включён",
                    "вместе с ${titles.dropLast(1).joinToString()}".takeIf { titles.size > 1 },
                ).joinToString(", ")
            }
            ContentKind.RESOURCE_PACK -> {
                ModManager.installResourcePack(dir, gameVersion, hit.projectId, hit.title, selectedVersion = version) { progress = it }
                "${hit.title} установлен и включён"
            }
            ContentKind.MOD -> {
                val current = installed[ContentKind.MOD].orEmpty().firstOrNull { it.projectId == hit.projectId }
                val titles = if (current != null && version != null) {
                    updateWithDependencies(current.copy(update = version))
                    listOf(hit.title)
                } else ModManager.install(dir, loader, gameVersion, hit.projectId, hit.title, installedProjects, selectedVersion = version) { progress = it }
                if (titles.size <= 1) "${hit.title} установлен" else "Установлено: ${titles.joinToString()}"
            }
        }
    }

    fun update(mod: InstalledItem) = work(mod.rowKey, "${mod.title} · $entryTitle") {
        updateWithDependencies(mod)
        message = "${mod.title} обновлён до ${mod.update?.versionNumber.orEmpty()}".trimEnd()
    }

    fun updateAll() = work(ALL, "Обновление модов · $entryTitle") {
        var pending = updates
        var done = 0
        var skipped = emptyList<String>()
        do {
            val before = done
            val failed = ArrayList<InstalledItem>()
            val reasons = ArrayList<String>()
            for (mod in pending) {
                try {
                    updateWithDependencies(mod)
                    done++
                } catch (e: IncompatibleModException) {
                    failed += mod
                    reasons += e.message.orEmpty()
                }
            }
            pending = failed
            skipped = reasons
        } while (pending.isNotEmpty() && done > before)
        if (skipped.isEmpty()) {
            message = "Обновлено модов: $done"
        } else {
            error = (listOf("Обновлено модов: $done") + skipped).joinToString(". ")
        }
    }

    fun toggle(item: InstalledItem, enabled: Boolean) = setEnabled(listOf(item), enabled)

    fun toggleMany(items: List<InstalledItem>, enabled: Boolean) = setEnabled(items, enabled)

    private fun replaceEnabled(rows: Map<ContentKind, List<InstalledItem>>, changed: InstalledItem): Map<ContentKind, List<InstalledItem>> =
        rows + (changed.kind to rows[changed.kind].orEmpty().mapNotNull { row ->
            when {
                row.rowKey == changed.rowKey -> changed
                row.file == changed.file -> null // A pre-existing target was replaced by the file operation.
                changed.kind == ContentKind.SHADER && changed.enabled -> row.copy(enabled = false)
                else -> row
            }
        })

    private fun setEnabled(items: List<InstalledItem>, enabled: Boolean) {
        if (migrating) return
        if (working.isNotEmpty()) return
        val editable = items.distinctBy { it.rowKey }.map { item ->
            installed[item.kind].orEmpty().firstOrNull { it.rowKey == item.rowKey } ?: item
        }.filter { it.enabled != enabled }
        if (editable.isEmpty()) return
        stopScan()
        working = setOf(if (editable.size == 1) editable.first().rowKey else ALL)
        error = null; message = null
        var confirmed = installed
        // Keep titles, ordering, row identities and selection while I/O is pending.
        installed = editable.fold(installed) { rows, item -> replaceEnabled(rows, item.copy(enabled = enabled)) }
        scope.launch {
            try {
                for (item in editable) {
                    val changed = withContext(Dispatchers.IO) { ModManager.setEnabled(item, enabled) }
                    confirmed = replaceEnabled(confirmed, changed)
                }
                installed = if (scanned) confirmed else retainRows(scanAll(remote = false))
                scanned = true; scannedAt = System.currentTimeMillis()
                if (items.size > 1) message = "Изменено проектов: ${editable.size}"
                onChanged()
            } catch (e: CancellationException) {
                installed = confirmed
                throw e
            } catch (e: Exception) {
                installed = confirmed
                error = e.message ?: "Не удалось изменить состояние проекта"
            } finally {
                working = emptySet()
                progress = null
            }
        }
    }

    fun removeMany(items: List<InstalledItem>) = work(ALL) {
        val editable = items.distinctBy { it.file }
        withContext(Dispatchers.IO) { editable.forEach { ensureActive(); ModManager.remove(it) } }
        message = "Удалено проектов: ${editable.size}"
    }

    fun updateMany(items: List<InstalledItem>) = work(ALL, "Обновление проектов · $entryTitle") {
        var done = 0
        val failures = ArrayList<String>()
        for (item in items.filter { it.update != null }.distinctBy { it.file }) {
            try { updateWithDependencies(item); done++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += "${item.title}: ${e.message}" }
        }
        message = "Обновлено проектов: $done"
        if (failures.isNotEmpty()) error = failures.joinToString("\n")
    }

    private suspend fun updateWithDependencies(mod: InstalledItem) {
        val version = mod.update ?: return
        if (CurseForge.owns(version.projectId)) {
            val present = installedProjects + version.projectId
            for (dependency in version.dependencies.filter { it.type == "required" }) {
                val id = dependency.projectId ?: continue
                if (id !in present) ModManager.install(dir, loader, gameVersion, id, id, present) { progress = it }
            }
        }
        ModManager.update(mod) { progress = it }
    }

    fun remove(item: InstalledItem) = work(item.rowKey) {
        withContext(Dispatchers.IO) { ModManager.remove(item) }
        message = "${item.title} удалён"
    }

    fun folder(kind: ContentKind?): Path = kind?.dir(dir) ?: dir

    private fun work(key: String, title: String? = null, block: suspend () -> Unit) {
        if (migrating) { error = "Дождись завершения изменения версии сборки"; return }
        if (title != null && downloads != null) {
            if (ALL in working || (key == ALL && working.isNotEmpty())) return
            downloads.enqueue("content:$entryKey:$key", title, entryKey,
                onQueued = { working = working + key },
                onFinished = { working = working - key; if (working.isEmpty()) startScan(clearError = false) },
            ) { reporter ->
                stopScan()
                activeReport = reporter
                error = null; message = null
                reporter.stage("Загружаю")
                try {
                    block()
                    installed = retainRows(scanAll(remote = false))
                    scanned = true; scannedAt = 0
                    onChanged()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Не получилось"; throw e }
                finally { progress = null; activeReport = null }
            }
            return
        }
        if (working.isNotEmpty()) return
        working = working + key
        error = null
        message = null
        stopScan()
        scope.launch {
            try {
                block()
                installed = retainRows(scanAll(remote = false))
                scanned = true
                scannedAt = 0
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не получилось"
            } finally {
                progress = null
                working = working - key
            }
            startScan(clearError = false)
        }
    }

    companion object {
        const val ALL = "*"
    }
}
