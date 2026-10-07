package ru.aw.launcher.instance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.ContentKind
import ru.aw.launcher.mods.InstalledItem
import ru.aw.launcher.mods.ModManager
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import ru.aw.launcher.packs.Modpacks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.io.path.exists

data class MigrationMod(val item: InstalledItem, val replacement: Modrinth.Version?, val reason: String?)
data class MigrationPlan(val directory: Path, val sourceVersion: String, val targetVersion: String,
                         val loader: LoaderKind, val mods: List<MigrationMod>, val fingerprint: Map<String, String>)

/** Downloads into staging, then replaces this profile's mods without touching worlds or configs. */
object BuildMigration {
    private val metadata = listOf(InstanceStore.FILE_NAME, Modpacks.MANIFEST)

    suspend fun analyze(directory: Path, sourceVersion: String, targetVersion: String, loader: LoaderKind): MigrationPlan =
        withContext(Dispatchers.IO) {
            require(sourceVersion != targetVersion) { "Выбери другую версию Minecraft" }
            val root = directory.toAbsolutePath().normalize()
            val before = fingerprint(root)
            val installed = ModManager.scan(root, loader, sourceVersion).map { item ->
                item.copy(sha1 = before.getValue("mods/${item.file.fileName}"))
            }
            val candidates = if (loader.isModded) Modrinth.latestVersions(installed.map { it.sha1 }, ModManager.loadersFor(loader), targetVersion)
                else emptyMap()
            if (fingerprint(root) != before) throw IOException("Сборка изменилась во время проверки. Проверь моды ещё раз")
            plan(root, sourceVersion, targetVersion, loader, installed, candidates, before)
        }

    internal fun plan(directory: Path, sourceVersion: String, targetVersion: String, loader: LoaderKind,
                      installed: List<InstalledItem>, candidates: Map<String, Modrinth.Version>, fingerprint: Map<String, String>): MigrationPlan {
        val allowed = ModManager.loadersFor(loader)
        return MigrationPlan(directory, sourceVersion, targetVersion, loader, installed.map { item ->
            val version = candidates[item.sha1]?.takeIf {
                targetVersion in it.gameVersions && it.loaders.any { kind -> kind in allowed } &&
                    it.primaryFile?.let { file -> ContentKind.MOD.safeName(file.filename) != null } == true
            }
            MigrationMod(item, version, if (version != null) null else when {
                !loader.isModded -> "Vanilla не загружает моды"
                item.projectId == null -> "Мод не найден на Modrinth или не распознан"
                else -> "На Modrinth нет подходящей версии для $targetVersion и ${loader.label}"
            })
        }, fingerprint)
    }

    suspend fun apply(plan: MigrationPlan, keepEnabled: Set<String>,
                      onStage: (String) -> Unit, onProgress: (DownloadProgress) -> Unit,
                      prepareGame: suspend (Path) -> Unit, commitVersion: () -> Unit): Path = withContext(Dispatchers.IO) {
        val root = plan.directory.toAbsolutePath().normalize()
        if (fingerprint(root) != plan.fingerprint) throw IOException("Сборка изменилась после проверки. Проверь моды ещё раз")
        val backups = root.resolve("aw-migration-backups")
        checkedDirectory(backups, create = true)
        val backup = backups.resolve(UUID.randomUUID().toString())
        Files.createDirectory(backup)
        val stage = backup.resolve("prepared")
        Files.createDirectory(stage)
        val stagedMods = stage.resolve("mods")
        Files.createDirectory(stagedMods)
        val originalMods = root.resolve("mods")
        val originalExisted = originalMods.exists(NOFOLLOW_LINKS)
        var swapped = false
        try {
            onStage("Подготавливаю обновление сборки")
            if (originalExisted) copyTree(originalMods, stagedMods)
            val compatible = plan.mods.mapNotNull { it.replacement?.projectId }.toSet()
            InstanceStore.update(stage) { InstanceStore.get(root).copy(javaPath = null, blockedUpdates = emptyList(),
                catalogMods = InstanceStore.get(root).catalogMods.filterNot { it.fileName.startsWith("mods/") && it.projectId in compatible }) }
            prepareGame(stage)
            for (mod in plan.mods) {
                currentCoroutineContext().ensureActive()
                val copied = mod.item.copy(file = stagedMods.resolve(mod.item.file.fileName.toString()))
                if (mod.replacement != null) Files.delete(copied.file)
                else if (copied.enabled) ModManager.setEnabled(copied, false)
            }
            val present = plan.mods.filter { it.item.enabled && it.replacement != null }.map { it.replacement!!.projectId }.toMutableSet()
            present += plan.mods.filter { it.replacement == null && it.item.fileName in keepEnabled }
                .mapNotNull { it.item.projectId }
            for (mod in plan.mods.filter { it.item.enabled && it.replacement != null }) {
                val version = mod.replacement!!
                onStage("Обновляю ${mod.item.title}")
                ModManager.install(stage, plan.loader, plan.targetVersion, version.projectId, mod.item.title,
                    present, selectedVersion = version, onProgress = onProgress)
                present += ModManager.scan(stage, plan.loader, plan.targetVersion, identifyRemote = false)
                    .filter { it.enabled }.mapNotNull { it.projectId }
            }
            for (mod in plan.mods.filter { !it.item.enabled && it.replacement != null }) {
                val version = mod.replacement!!
                if (version.projectId in present) continue // An enabled mod now requires this dependency.
                val file = version.primaryFile!!
                val name = ContentKind.MOD.safeName(file.filename) ?: throw IOException("Недопустимое имя файла мода")
                Downloader().run(listOf(DownloadTask(file.url, stagedMods.resolve("$name.disabled"), file.sha1, file.size)), onProgress)
                ModManager.rememberCatalog(stage, ContentKind.MOD, version, mod.item.title, mod.item.iconUrl)
            }
            for (mod in plan.mods.filter { it.replacement == null && it.item.enabled && it.item.fileName in keepEnabled }) {
                ModManager.setEnabled(mod.item.copy(file = stagedMods.resolve("${mod.item.fileName}.disabled"), enabled = false), true)
            }
            currentCoroutineContext().ensureActive()
            if (fingerprint(root) != plan.fingerprint) throw IOException("Сборка изменилась во время загрузки. Проверь моды ещё раз")
            onStage("Применяю новую версию сборки")
            withContext(NonCancellable) {
                for (name in metadata) if (root.resolve(name).exists(NOFOLLOW_LINKS))
                    Files.copy(root.resolve(name), backup.resolve(name))
                if (originalExisted) Files.move(originalMods, backup.resolve("mods"))
                swapped = true
                Files.move(stagedMods, originalMods)
                Files.copy(stage.resolve(InstanceStore.FILE_NAME), root.resolve(InstanceStore.FILE_NAME), REPLACE_EXISTING)
                InstanceStore.forget(root)
                commitVersion()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (swapped) {
                    try {
                        Storage.deleteTree(originalMods)
                        if (originalExisted) Files.move(backup.resolve("mods"), originalMods)
                        for (name in metadata) {
                            val saved = backup.resolve(name)
                            if (saved.exists()) Files.copy(saved, root.resolve(name), REPLACE_EXISTING)
                            else Files.deleteIfExists(root.resolve(name))
                        }
                        InstanceStore.forget(root)
                    } catch (rollback: Exception) {
                        failure.addSuppressed(rollback)
                        throw IOException("Не удалось завершить откат. Резервные файлы сохранены в $backup", failure)
                    }
                }
            }
            throw failure
        } finally {
            withContext(NonCancellable) {
                InstanceStore.forget(stage)
                runCatching { Storage.deleteTree(stage) }.onFailure { Log.warn("migration staging cleanup failed", it) }
            }
        }
        backup
    }

    internal fun fingerprint(root: Path): Map<String, String> {
        checkedDirectory(root)
        val result = linkedMapOf<String, String>()
        val mods = root.resolve("mods")
        result["mods/"] = if (mods.exists(NOFOLLOW_LINKS)) "present" else "absent"
        if (mods.exists(NOFOLLOW_LINKS)) {
            checkedDirectory(mods)
            Files.walk(mods).use { paths -> paths.forEach { path ->
                if (Files.isSymbolicLink(path) || (!Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS)))
                    throw IOException("В папке модов есть ссылка или неподдерживаемый файл: ${path.fileName}")
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) result[root.relativize(path).toString().replace('\\', '/')] = sha1Of(path)
            } }
        }
        for (name in metadata) {
            val file = root.resolve(name)
            if (file.exists(NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("Некорректный файл настроек сборки")
            result[name] = if (file.exists(NOFOLLOW_LINKS)) sha1Of(file) else "absent"
        }
        return result
    }

    private fun checkedDirectory(path: Path, create: Boolean = false) {
        if (create && !path.exists(NOFOLLOW_LINKS)) Files.createDirectory(path)
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) throw IOException("Папка сборки содержит ссылку: $path")
    }

    private suspend fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            for (path in paths.toList()) {
                currentCoroutineContext().ensureActive()
                val dest = target.resolve(source.relativize(path))
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(dest)
                else Files.copy(path, dest)
            }
        }
    }
}
