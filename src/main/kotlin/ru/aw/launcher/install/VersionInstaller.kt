package ru.aw.launcher.install

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.AssetEndpoints
import ru.aw.launcher.meta.AssetIndex
import ru.aw.launcher.meta.RuleEnvironment
import ru.aw.launcher.meta.VersionJson
import ru.aw.launcher.meta.VersionManifestRepository
import ru.aw.launcher.meta.mavenPath
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import ru.aw.launcher.net.Http
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText

data class InstalledVersion(
    val json: VersionJson,
    val clientJar: Path,
    val classpath: List<Path>,
    val nativesDir: Path,
    val assetsDir: Path,
    val assetIndexId: String,
    val logConfig: Path?,
    val logConfigArgument: String?,
)

class VersionInstaller(
    private val downloader: Downloader = Downloader(),
    private val env: RuleEnvironment = RuleEnvironment.current,
) {

    private companion object {
        const val MOJANG_LIBRARIES = "https://libraries.minecraft.net/"
    }

    fun resolve(id: String): VersionJson {
        val chain = ArrayList<VersionJson>(2)
        val seen = LinkedHashSet<String>()
        var currentId = id

        while (true) {
            if (!seen.add(currentId)) throw IOException("circular inheritsFrom chain at $currentId")
            val version = readOrFetch(currentId)
            chain += version
            currentId = version.inheritsFrom ?: break
        }

        var merged = chain.last()
        for (i in chain.size - 2 downTo 0) merged = chain[i].mergedOnto(merged)
        return merged
    }

    private fun readOrFetch(id: String): VersionJson {
        val file = Paths.versionJson(id)
        if (file.exists()) {
            runCatching { return Json.decodeFromString<VersionJson>(file.readText()) }
                .onFailure { Log.warn("version json for $id is corrupt, refetching", it) }
        }
        val entry = VersionManifestRepository.load().versions.firstOrNull { it.id == id }
            ?: throw IOException("Версия '$id' не найдена в манифесте Mojang")
        val text = Http.getString(entry.url)
        file.writeAtomically(text)
        return Json.decodeFromString(text)
    }

    private class Plan(
        val tasks: List<DownloadTask>,
        val classpath: List<Path>,
        val nativeJars: List<NativeJar>,
        val clientJar: Path,
        val assetIndex: AssetIndex?,
        val logConfig: Path?,
    )

    suspend fun prefetch(version: VersionJson, onProgress: (DownloadProgress) -> Unit = {}) {
        downloader.run(plan(version, onProgress).tasks, onProgress)
    }

    suspend fun install(
        version: VersionJson,
        gameDir: Path,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstalledVersion {
        onStage("Чтение метаданных")
        val plan = plan(version, onProgress)

        onStage("Загрузка файлов")
        downloader.run(plan.tasks, onProgress)

        onStage("Распаковка библиотек")
        val nativesDir = NativesExtractor.extract(version.id, plan.nativeJars)

        onStage("Подготовка ресурсов")
        val assetsDir = prepareAssets(version, plan.assetIndex, gameDir)

        return InstalledVersion(
            json = version,
            clientJar = plan.clientJar,
            classpath = plan.classpath.plusElement(plan.clientJar),
            nativesDir = nativesDir,
            assetsDir = assetsDir,
            assetIndexId = version.assetsId,
            logConfig = plan.logConfig,
            logConfigArgument = version.logging?.client?.argument,
        )
    }

    private suspend fun plan(version: VersionJson, onProgress: (DownloadProgress) -> Unit): Plan {
        Paths.ensureBaseDirs()
        val assetIndexFile = ensureAssetIndex(version, onProgress)
        val assetIndex = assetIndexFile?.let {
            runCatching { Json.decodeFromString<AssetIndex>(it.readText()) }
                .onFailure { e -> Log.warn("asset index unreadable: ${e.message}") }
                .getOrNull()
        }

        val tasks = ArrayList<DownloadTask>(4096)
        val classpath = ArrayList<Path>(64)
        val nativeJars = ArrayList<NativeJar>(8)

        val clientJar = Paths.versionJar(version.id)
        version.downloads?.client?.let { client ->
            if (client.url.isNotBlank()) {
                tasks += DownloadTask(client.url, clientJar, client.sha1, client.size, label = "${version.id}.jar")
            }
        }

        collectLibraries(version, tasks, classpath, nativeJars)

        assetIndex?.objects?.values?.distinctBy { it.hash }?.forEach { obj ->
            tasks += DownloadTask(
                url = AssetEndpoints.objectUrl(obj.hash),
                dest = Paths.assetObject(obj.hash),
                sha1 = obj.hash,
                size = obj.size,
                label = obj.hash.take(8),
            )
        }

        var logConfig: Path? = null
        version.logging?.client?.file?.let { file ->
            val name = file.id ?: "client-${version.id}.xml"
            val dest = Paths.assets.resolve("log_configs").resolve(name)
            if (file.url.isNotBlank()) {
                tasks += DownloadTask(file.url, dest, file.sha1, file.size, label = name)
            }
            logConfig = dest
        }
        return Plan(tasks, classpath, nativeJars, clientJar, assetIndex, logConfig)
    }

    internal fun collectLibraries(
        version: VersionJson,
        tasks: MutableList<DownloadTask>,
        classpath: MutableList<Path>,
        nativeJars: MutableList<NativeJar>,
    ) {
        val seen = HashSet<String>()
        val seenNatives = HashSet<Path>()

        for (library in version.libraries) {
            if (!library.appliesTo(env)) continue
            if (library.clientreq == false) continue

            if (seen.add(dedupeKey(library.name))) {
                val artifact = library.downloads?.artifact
                if (artifact != null) {
                    val path = Paths.libraryPath(artifact.path ?: mavenPath(library.name))
                    if (artifact.url.isNotBlank()) {
                        tasks += DownloadTask(artifact.url, path, artifact.sha1, artifact.size, label = path.fileName.toString())
                    }
                    classpath.add(path)
                    if (hasNativeClassifier(library.name)) nativeJars += NativeJar(path, library.extract)
                } else if (library.downloads == null && library.natives.isEmpty()) {
                    val relative = mavenPath(library.name)
                    val path = Paths.libraryPath(relative)
                    val root = library.url?.takeIf { it.isNotBlank() } ?: MOJANG_LIBRARIES
                    tasks += DownloadTask(
                        url = root.trimEnd('/') + "/" + relative,
                        dest = path,
                        label = path.fileName.toString(),
                    )
                    classpath.add(path)
                }
            }

            val classifier = library.nativeClassifier(env)
            if (classifier != null) {
                val nativeArtifact = library.downloads?.classifiers?.get(classifier)
                if (nativeArtifact != null) {
                    val path = Paths.libraryPath(nativeArtifact.path ?: mavenPath("${library.name}:$classifier"))
                    if (seenNatives.add(path)) {
                        if (nativeArtifact.url.isNotBlank()) {
                            tasks += DownloadTask(
                                nativeArtifact.url, path, nativeArtifact.sha1, nativeArtifact.size,
                                label = path.fileName.toString(),
                            )
                        }
                        nativeJars += NativeJar(path, library.extract)
                    }
                }
            }
        }
    }

    private fun dedupeKey(name: String): String {
        val parts = name.substringBefore('@').split(':')
        val group = parts.getOrNull(0).orEmpty()
        val artifact = parts.getOrNull(1).orEmpty()
        val classifier = parts.getOrNull(3).orEmpty()
        return "$group:$artifact:$classifier"
    }

    private fun hasNativeClassifier(name: String): Boolean =
        name.substringBefore('@').split(':').getOrNull(3)?.startsWith("natives-") == true

    internal suspend fun ensureAssetIndex(version: VersionJson, onProgress: (DownloadProgress) -> Unit = {}): Path? {
        val ref = version.assetIndex ?: return null
        val file = Paths.assetIndexes.resolve("${ref.id}.json")
        if (ref.url.isBlank()) return file.takeIf { it.exists() }
        try {
            downloader.run(listOf(DownloadTask(ref.url, file, ref.sha1, ref.size, label = file.fileName.toString())), onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "Не удалось скачать список ресурсов игры — звуков и переводов. Проверьте интернет и попробуйте ещё раз.",
                e,
            )
        }
        return file
    }

    private fun prepareAssets(version: VersionJson, index: AssetIndex?, gameDir: Path): Path {
        if (index == null) return Paths.assets

        return when {
            index.mapToResources -> {
                materialize(index, gameDir.resolve("resources"))
                Paths.assets
            }
            index.virtual || version.usesLegacyAssets -> {
                val virtual = Paths.assets.resolve("virtual").resolve(version.assetsId)
                materialize(index, virtual)
                virtual
            }
            else -> Paths.assets
        }
    }

    private fun materialize(index: AssetIndex, target: Path) {
        var linked = 0
        var copied = 0
        for ((name, obj) in index.objects) {
            val source = Paths.assetObject(obj.hash)
            if (!source.exists()) continue
            val dest = target.resolve(name)
            if (dest.exists() && runCatching { dest.fileSize() }.getOrNull() == obj.size) continue
            runCatching {
                dest.parent?.createDirectories()
                Files.deleteIfExists(dest)
                Files.createLink(dest, source)
                linked++
            }.onFailure {
                runCatching {
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }.onFailure { e -> Log.warn("could not materialise asset $name: ${e.message}") }
            }
        }
        if (linked + copied > 0) Log.info("legacy assets prepared: $linked linked, $copied copied")
    }
}
