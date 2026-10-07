package ru.aw.launcher.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.meta.LoaderRepository
import ru.aw.launcher.meta.VersionJson
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.DownloadTask
import ru.aw.launcher.net.Downloader
import ru.aw.launcher.net.Http
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

object LoaderInstaller {

    private const val INSTALLER_TIMEOUT_MINUTES = 15L

    suspend fun ensureProfile(
        kind: LoaderKind,
        gameVersion: String,
        javaExecutable: suspend () -> Path,
        pinned: String? = null,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (kind == LoaderKind.VANILLA) return@withContext gameVersion

        if (pinned == null) onStage("Ищу последнюю сборку ${kind.label}")
        val loaderVersion = pinned ?: LoaderRepository.latestBuild(kind, gameVersion)
        if (loaderVersion == null) {
            installedProfile(kind, gameVersion)?.let {
                Log.warn("${kind.label} builds for $gameVersion unavailable, launching installed $it")
                return@withContext it
            }
            throw IOException(
                "Для $gameVersion не нашлось ни одной сборки ${kind.label}. " +
                    "Возможно, она ещё не вышла или нет связи с их сервером."
            )
        }
        Log.info("${kind.label} build chosen for $gameVersion: $loaderVersion")

        when (kind) {
            LoaderKind.FABRIC, LoaderKind.QUILT ->
                installFabricLike(kind, gameVersion, loaderVersion, onStage)
            LoaderKind.FORGE, LoaderKind.NEOFORGE ->
                installViaInstaller(kind, gameVersion, loaderVersion, javaExecutable, onStage, onProgress)
            LoaderKind.VANILLA -> gameVersion
        }
    }

    private fun installFabricLike(
        kind: LoaderKind,
        gameVersion: String,
        loaderVersion: String,
        onStage: (String) -> Unit,
    ): String {
        predictedProfileId(kind, gameVersion, loaderVersion)
            .takeIf { Paths.versionJson(it).exists() }
            ?.let {
                Log.debug("${kind.label} profile $it already present")
                return it
            }

        onStage("Получаю профиль ${kind.label} $loaderVersion")
        val url = LoaderRepository.profileUrl(kind, gameVersion, loaderVersion)
        val text = Http.getString(url)

        val parsed = Json.decodeFromString<VersionJson>(text)
        val id = parsed.id.ifBlank {
            throw IOException("${kind.label} вернул профиль без id")
        }

        Paths.versionJson(id).writeAtomically(text)
        Log.info("${kind.label} profile installed: $id")
        return id
    }

    private suspend fun installViaInstaller(
        kind: LoaderKind,
        gameVersion: String,
        loaderVersion: String,
        javaExecutable: suspend () -> Path,
        onStage: (String) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): String {
        locateInstalledProfile(kind, gameVersion, loaderVersion)
            ?.takeIf(::isInstalled)
            ?.let {
                Log.debug("${kind.label} profile $it already present")
                return it
            }

        onStage("Скачиваю установщик ${kind.label} $loaderVersion")
        val url = LoaderRepository.installerUrl(kind, gameVersion, loaderVersion)
        val installerDir = Paths.cache.resolve("installers").also { it.createDirectories() }
        val installerJar = installerDir.resolve(url.substringAfterLast('/'))

        Downloader().run(
            listOf(DownloadTask(url = url, dest = installerJar, label = installerJar.fileName.toString())),
            onProgress,
        )
        if (!installerJar.exists()) throw IOException("Установщик ${kind.label} не скачался")

        val legacy = LegacyForge.read(installerJar)
        val id = if (legacy != null) {
            onStage("Устанавливаю ${kind.label}")
            LegacyForge.install(legacy, installerJar)
        } else {
            ensureLauncherProfilesStub()

            val java = javaExecutable()
            onStage("Устанавливаю ${kind.label} — это занимает пару минут")
            runInstaller(java, installerJar)

            locateInstalledProfile(kind, gameVersion, loaderVersion)
                ?: throw IOException(
                    "${kind.label} отработал, но профиль не найден в ${Paths.versions}. " +
                        "Подробности в логе лаунчера."
                )
        }
        Paths.versionDir(id).resolve(INSTALLED_MARKER).writeText("")
        Log.info("${kind.label} profile installed: $id")
        return id
    }

    private const val INSTALLED_MARKER = "aw-installed"

    private fun isInstalled(id: String): Boolean =
        Paths.versionJson(id).exists() && Paths.versionDir(id).resolve(INSTALLED_MARKER).exists()

    private suspend fun runInstaller(javaExecutable: Path, installerJar: Path) {
        val command = listOf(
            javaExecutable.toAbsolutePath().toString(),
            "-jar",
            installerJar.toAbsolutePath().toString(),
            "--installClient",
            Paths.root.toAbsolutePath().toString(),
        )
        Log.info("running loader installer: ${command.joinToString(" ")}")

        val output = installerJar.resolveSibling("installer-output.log")
        val process = ProcessBuilder(command)
            .directory(Paths.root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(INSTALLER_TIMEOUT_MINUTES)
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) {
                    throw IOException("Установщик не завершился за $INSTALLER_TIMEOUT_MINUTES минут")
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }

        val lines = runCatching { String(Files.readAllBytes(output), nativeCharset) }.getOrDefault("")
            .lineSequence().filter { it.isNotBlank() }.toList()
        lines.forEach { Log.debug("installer: $it") }

        val exit = process.exitValue()
        if (exit != 0) {
            throw IOException("Установщик завершился с кодом $exit\n" + lines.takeLast(6).joinToString("\n"))
        }
    }

    private val nativeCharset: Charset =
        runCatching { Charset.forName(System.getProperty("native.encoding")) }.getOrDefault(Charset.defaultCharset())

    private fun ensureLauncherProfilesStub() {
        val file = Paths.root.resolve("launcher_profiles.json")
        if (file.exists()) return
        runCatching {
            file.writeAtomically("""{"profiles":{},"selectedProfile":"","clientToken":"","version":3}""")
        }.onFailure { Log.warn("could not create launcher_profiles.json stub", it) }
    }

    private fun predictedProfileId(kind: LoaderKind, gameVersion: String, loaderVersion: String): String =
        when (kind) {
            LoaderKind.FABRIC -> "fabric-loader-$loaderVersion-$gameVersion"
            LoaderKind.QUILT -> "quilt-loader-$loaderVersion-$gameVersion"
            LoaderKind.FORGE -> "$gameVersion-forge-$loaderVersion"
            LoaderKind.NEOFORGE -> "neoforge-$loaderVersion"
            LoaderKind.VANILLA -> gameVersion
        }

    private fun installedProfile(kind: LoaderKind, gameVersion: String): String? = runCatching {
        Paths.versions.listDirectoryEntries()
            .filter { it.isDirectory() }
            .filter { dir ->
                val id = dir.fileName.toString()
                kind.ownsProfile(id, gameVersion) && dir.resolve("$id.json").exists() &&
                    (kind == LoaderKind.FABRIC || kind == LoaderKind.QUILT || isInstalled(id))
            }
            .maxByOrNull { it.getLastModifiedTime().toMillis() }
            ?.fileName?.toString()
    }.getOrNull()

    private fun locateInstalledProfile(
        kind: LoaderKind,
        gameVersion: String,
        loaderVersion: String,
    ): String? {
        predictedProfileId(kind, gameVersion, loaderVersion)
            .takeIf { Paths.versionJson(it).exists() }
            ?.let { return it }

        val marker = if (kind == LoaderKind.NEOFORGE) "neoforge" else "forge"
        return runCatching {
            Paths.versions.listDirectoryEntries()
                .filter { it.isDirectory() }
                .filter { dir ->
                    val name = dir.fileName.toString()
                    name.contains(marker, ignoreCase = true) &&
                        name.contains(loaderVersion) &&
                        dir.resolve("$name.json").exists()
                }
                .maxByOrNull { it.getLastModifiedTime().toMillis() }
                ?.fileName?.toString()
        }.getOrNull()
    }
}
