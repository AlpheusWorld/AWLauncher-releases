package ru.aw.launcher.mods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.*
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
internal data class CompanionArtifact(val gameVersion: String, val version: String, val url: String, val sha1: String, val sha256: String, val size: Long)
@Serializable
private data class CompanionRecord(val sha256: String)

internal object CompanionMod {
    private const val MOD_ID = "awassistant"
    private const val FILE = "awassistant.jar"
    private const val RECORD = "awassistant-managed.json"
    private val artifacts by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/companion/catalog.json")).bufferedReader().use { it.readText() }
        Json.decodeFromString<List<CompanionArtifact>>(text)
    }

    suspend fun ensure(gameVersion: String, loader: LoaderKind, gameDir: Path, enabled: Boolean,
                       onStage: (String) -> Unit = {}, onProgress: (DownloadProgress) -> Unit = {}, onNotice: (String) -> Unit = {}, fabricVersion: String? = null) {
        if (!enabled || loader != LoaderKind.FABRIC) return
        val artifact = artifacts.firstOrNull { it.gameVersion == gameVersion } ?: return
        if (!supportsLoader(fabricVersion)) { onNotice("Для AWAssistant нужен Fabric Loader 0.19.3 или новее"); return }
        ensureArtifact(artifact, gameDir, onStage, onProgress, onNotice)
    }

    internal fun supportsLoader(version: String?): Boolean = version != null &&
        version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && !ru.aw.launcher.update.Updater.isNewer("0.19.3", version)

    internal suspend fun ensureArtifact(artifact: CompanionArtifact, gameDir: Path, onStage: (String) -> Unit = {},
                                       onProgress: (DownloadProgress) -> Unit = {}, onNotice: (String) -> Unit = {},
                                       download: suspend (DownloadTask) -> Unit = { Downloader().run(listOf(it), onProgress) },
                                       cacheDir: Path = Paths.cache.resolve("companion")) = withContext(Dispatchers.IO) {
        require(artifact.url.startsWith("https://github.com/AlpheusWorld/AWLauncher-releases/releases/download/"))
        require(artifact.sha256.matches(Regex("[a-f0-9]{64}")) && artifact.sha1.matches(Regex("[a-f0-9]{40}")) && artifact.size in 1..16_777_216)
        val mods = gameDir.resolve("mods")
        val target = mods.resolve(FILE)
        val recordFile = gameDir.resolve("config").resolve(RECORD)
        fun installed() = if (Files.isDirectory(mods)) Files.list(mods).use { stream ->
            stream.filter { Files.isRegularFile(it) && (it.fileName.toString().endsWith(".jar") || it.fileName.toString().endsWith(".jar.disabled")) }
                .filter { ModCompat.read(it)?.id == MOD_ID }.toList()
        } else emptyList()
        if (Files.exists(mods.resolve("$FILE.disabled")) || installed().any { it != target }) return@withContext
        val owned = runCatching { Json.decodeFromString<CompanionRecord>(Files.readString(recordFile)).sha256 }.getOrNull()
        if (Files.exists(target)) {
            val current = hash(target)
            if (current == artifact.sha256) return@withContext
            if (current != owned) { onNotice("AWAssistant установлен вручную — автозагрузка его не заменит"); return@withContext }
        }
        onStage("Скачиваю клиентский мод AWAssistant")
        val cached = cacheDir.resolve("${artifact.sha256}.jar")
        if (!Files.exists(cached) || hash(cached) != artifact.sha256 || Files.size(cached) != artifact.size) {
            download(DownloadTask(artifact.url, cached, artifact.sha1, artifact.size, label = "AWAssistant ${artifact.version}"))
        }
        require(Files.size(cached) == artifact.size && hash(cached) == artifact.sha256) { "Не совпадает контрольная сумма AWAssistant" }
        require(ModCompat.read(cached)?.id == MOD_ID) { "Файл AWAssistant не содержит клиентский мод" }
        // Re-check after the network operation: a user may disable or replace the mod while it downloads.
        if (Files.exists(mods.resolve("$FILE.disabled")) || installed().any { it != target }) return@withContext
        if (Files.exists(target) && hash(target) != owned) return@withContext
        target.writeAtomically(Files.readAllBytes(cached))
        recordFile.writeAtomically(Json.encodeToString(CompanionRecord(artifact.sha256)))
    }

    private fun hash(path: Path): String = Files.newInputStream(path).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().toHex()
    }
}
