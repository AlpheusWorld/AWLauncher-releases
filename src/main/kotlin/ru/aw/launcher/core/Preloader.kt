package ru.aw.launcher.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.auth.AccountManager
import kotlinx.serialization.encodeToString
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.meta.LoaderRepository
import ru.aw.launcher.meta.LoaderSupport
import ru.aw.launcher.meta.VersionManifest
import ru.aw.launcher.meta.VersionManifestRepository
import ru.aw.launcher.packs.Modpack
import ru.aw.launcher.packs.Modpacks
import ru.aw.launcher.instance.LocalBuild
import ru.aw.launcher.instance.LocalBuilds
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

data class PreloadResult(
    val manifest: VersionManifest,
    val installed: Map<String, Long>,
    val profiles: Set<String>,
    val loaderSupport: LoaderSupport = LoaderSupport(),
    val packs: List<Modpack> = emptyList(),
    val builds: List<LocalBuild> = emptyList(),
    val manifestStale: Boolean = false,
    val loaderSupportStale: Boolean = false,
)

object Preloader {

    suspend fun run(onStep: (Float, String) -> Unit): PreloadResult {
        val startedAt = System.nanoTime()
        val timings = linkedMapOf<String, Long>()
        suspend fun <T> localData(name: String, load: () -> T): T = withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                load()
            } finally {
                timings[name] = (System.nanoTime() - start) / 1_000_000
            }
        }

        val opening = "Запускаюсь"
        onStep(0.05f, opening)
        localData("directories") { runCatching { Paths.ensureBaseDirs() } }
        onStep(0.15f, opening)
        localData("settings") { runCatching { Settings.load() } }
        onStep(0.25f, opening)
        localData("accounts") { runCatching { AccountManager.load() } }
        localData("notices") { runCatching { Notices.load() } }
        onStep(0.40f, opening)
        localData("verification cache") { runCatching { VerifyCache.load() } }

        onStep(0.50f, "Загружаю сохранённые версии")
        val (cachedManifest, manifestStale) = localData("version cache") {
            val cached = VersionManifestRepository.loadCached()
            cached to (cached == null || !VersionManifestRepository.isFresh())
        }
        // First launch and offline launch must not wait for a metadata request.
        // LauncherState already refreshes missing or stale versions in the background.
        val manifest = cachedManifest ?: VersionManifest()

        val loaders = "Подтягиваю загрузчики модов"
        onStep(0.70f, loaders)
        val cachedSupport = localData("loader cache") { readSupportCache() }
        // The existing background refresh fills this in without holding the splash open.
        val loaderSupport = cachedSupport?.support ?: LoaderSupport()

        onStep(0.90f, loaders)
        val (installed, profiles) = localData("installed versions") { scanInstalled() }
        val packs = localData("modpacks") { Modpacks.list() }
        val builds = localData("builds") { LocalBuilds.list() }

        onStep(1f, loaders)
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        Log.info("preload stages: " + timings.entries.joinToString { (name, ms) -> "$name ${ms} ms" })
        Log.info(
            "preload: ${elapsed} ms; versions from " +
                (if (cachedManifest != null) "cache" + (if (manifestStale) " (stale)" else "") else "background refresh") +
                ", loaders from " +
                (if (cachedSupport != null) "cache" + (if (!cachedSupport.fresh) " (stale)" else "") else "background refresh")
        )

        return PreloadResult(
            manifest = manifest,
            installed = installed,
            profiles = profiles,
            loaderSupport = loaderSupport,
            packs = packs,
            builds = builds,
            manifestStale = manifestStale,
            loaderSupportStale = cachedSupport?.fresh != true,
        )
    }

    suspend fun refreshManifest(): VersionManifest? =
        withContext(Dispatchers.IO) { VersionManifestRepository.refresh() }

    suspend fun refreshLoaderSupport(): LoaderSupport = fetchLoaderSupport()

    private const val SUPPORT_FRESH_MILLIS = 6 * 60 * 60 * 1000L

    private const val PARTIAL_FRESH_MILLIS = 15 * 60 * 1000L

    private val supportCache: Path get() = Paths.cache.resolve("loader-support-v2.json")

    private suspend fun fetchLoaderSupport(): LoaderSupport {
        val fetched = withContext(Dispatchers.IO) {
            coroutineScope {
                val fabric = async { LoaderRepository.supportedGameVersions(LoaderKind.FABRIC) }
                val quilt = async { LoaderRepository.supportedGameVersions(LoaderKind.QUILT) }
                val forge = async { LoaderRepository.supportedGameVersions(LoaderKind.FORGE) }
                val neoforge = async { LoaderRepository.supportedGameVersions(LoaderKind.NEOFORGE) }
                LoaderSupport(fabric.await(), quilt.await(), forge.await(), neoforge.await())
            }
        }

        val previous = withContext(Dispatchers.IO) { readSupportCache() }?.support ?: LoaderSupport()
        val merged = LoaderSupport(
            fabric = fetched.fabric.ifEmpty { previous.fabric },
            quilt = fetched.quilt.ifEmpty { previous.quilt },
            forge = fetched.forge.ifEmpty { previous.forge },
            neoforge = fetched.neoforge.ifEmpty { previous.neoforge },
        )

        if (!merged.isEmpty) {
            withContext(Dispatchers.IO) {
                runCatching { supportCache.writeAtomically(Json.encodeToString(merged)) }
                    .onFailure { Log.warn("could not cache loader support", it) }
            }
        }
        return merged
    }

    private class CachedSupport(val support: LoaderSupport, val fresh: Boolean)

    private fun readSupportCache(): CachedSupport? = runCatching {
        if (!supportCache.exists()) return null
        val cached = Json.decodeFromString<LoaderSupport>(supportCache.readText()).takeIf { !it.isEmpty }
            ?: return null

        val complete = cached.fabric.isNotEmpty() && cached.quilt.isNotEmpty() &&
            cached.forge.isNotEmpty() && cached.neoforge.isNotEmpty()
        val window = if (complete) SUPPORT_FRESH_MILLIS else PARTIAL_FRESH_MILLIS
        val age = System.currentTimeMillis() - supportCache.getLastModifiedTime().toMillis()
        CachedSupport(cached, fresh = age in 0..window)
    }.getOrNull()

    fun scanInstalled(): Pair<Map<String, Long>, Set<String>> = runCatching {
        val dirs = Paths.versions.listDirectoryEntries().filter { it.isDirectory() }
        val jars = dirs.mapNotNull { dir ->
            val id = dir.fileName.toString()
            val jar = dir.resolve("$id.jar")
            if (jar.exists()) id to runCatching { jar.fileSize() }.getOrDefault(0L) else null
        }.toMap()
        val profiles = dirs.mapNotNull { dir ->
            val id = dir.fileName.toString()
            id.takeIf { dir.resolve("$id.json").exists() }
        }.toSet()
        jars to profiles
    }.getOrDefault(emptyMap<String, Long>() to emptySet())
}
