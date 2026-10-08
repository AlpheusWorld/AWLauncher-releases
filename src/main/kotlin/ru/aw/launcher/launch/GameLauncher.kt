package ru.aw.launcher.launch

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.aw.launcher.auth.Account
import ru.aw.launcher.auth.AccountManager
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.core.VerifyCache
import ru.aw.launcher.install.InstalledVersion
import ru.aw.launcher.install.LoaderInstaller
import ru.aw.launcher.install.VersionInstaller
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.GameOptions
import ru.aw.launcher.net.DownloadProgress
import ru.aw.launcher.net.Downloader
import ru.aw.launcher.net.ProgressMerger
import ru.aw.launcher.runtime.JavaComponents
import ru.aw.launcher.runtime.JavaManager
import ru.aw.launcher.servers.ServerList

data class LaunchResult(val process: Process, val gameDir: Path, val logFile: Path)

class PreparedLaunch(
    val installed: InstalledVersion,
    val gameDir: Path,
    val javaExecutable: Path,
    val memoryMb: Int,
    val settings: LauncherSettings = Settings.current.copy(memoryMb = memoryMb),
    val options: InstanceOptions = InstanceOptions(),
)

object GameLauncher {

    suspend fun launch(
        versionId: String,
        account: Account,
        loader: LoaderKind = LoaderKind.VANILLA,
        serverAddress: String? = null,
        gameDir: Path = Settings.gameDir(versionId, loader),
        loaderVersion: String? = null,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
        onNotice: (String) -> Unit = {},
    ): LaunchResult {
        val prepared = prepare(versionId, loader, gameDir, loaderVersion, onStage, onProgress, onNotice)
        withContext(Dispatchers.IO) {
            runCatching { ServerList.removeLegacyDefault(prepared.gameDir) }
                .onFailure { Log.warn("could not remove the old default server from ${prepared.gameDir}: ${it.message}") }
        }

        onStage("Проверка аккаунта")
        val readyAccount = AccountManager.prepareForLaunch(account, onStage)

        onStage("Запуск игры")
        val command = withContext(Dispatchers.IO) {
            prepared.options.fullscreen?.let { GameOptions.setFullscreen(prepared.gameDir, it) }
            ArgumentBuilder(
                installed = prepared.installed,
                account = readyAccount,
                settings = prepared.settings,
                javaExecutable = prepared.javaExecutable,
                gameDir = prepared.gameDir,
                serverAddress = serverAddress,
                options = prepared.options,
            ).build()
        }

        logCommand(command, readyAccount)

        val logFile = prepared.gameDir.resolve(GAME_LOG)
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .directory(prepared.gameDir.toFile())
                .redirectOutput(logFile.toFile())
                .redirectErrorStream(true)
                .start()
        }

        Log.info("game started, pid=${runCatching { process.pid() }.getOrDefault(-1)}")
        return LaunchResult(process, prepared.gameDir, logFile)
    }

    suspend fun prepare(
        versionId: String,
        loader: LoaderKind = LoaderKind.VANILLA,
        gameDir: Path = Settings.gameDir(versionId, loader),
        loaderVersion: String? = null,
        onStage: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
        onNotice: (String) -> Unit = {},
    ): PreparedLaunch {
        val options = withContext(Dispatchers.IO) {
            gameDir.createDirectories()
            if (!java.nio.file.Files.exists(gameDir.resolve(InstanceStore.FILE_NAME))) InstanceStore.update(gameDir) { it }
            InstanceStore.get(gameDir)
        }
        val settings = options.launchSettings(Settings.current)
        require(settings.memoryMb >= 512) { "Память сборки должна быть не меньше 512 МБ" }
        ArgumentBuilder.parseJvmArguments(settings.jvmArgs)
        if (settings.forceVerify) {
            Log.info("forced verification requested, dropping hash cache")
            VerifyCache.clear()
            Settings.update { it.copy(forceVerify = false) }
        }

        onStage("Чтение версии $versionId")
        val installer = VersionInstaller(
            downloader = Downloader(
                concurrency = settings.downloadConcurrency.takeIf { it > 0 } ?: Downloader.DEFAULT_CONCURRENCY
            )
        )

        val baseVersion = withContext(Dispatchers.IO) { installer.resolve(versionId) }
        val progress = ProgressMerger(onProgress)

        return coroutineScope {
            val java = async {
                options.javaPath?.takeIf { it.isNotBlank() }?.let { raw ->
                    withContext(Dispatchers.IO) { JavaManager.customExecutable(raw, baseVersion.javaVersion?.majorVersion ?: 8) }
                } ?: resolveJava(baseVersion.javaVersion?.component, baseVersion.javaVersion?.majorVersion, progress.sink("java"))
            }
            val awaitJava: suspend () -> Path = {
                if (!java.isCompleted) onStage("Докачиваю Java")
                java.await()
            }

            val baseFiles = if (loader.installsWithJava) async { installer.prefetch(baseVersion, progress.sink("game")) } else null

            val profileId = if (loader.isModded) {
                LoaderInstaller.ensureProfile(
                    kind = loader,
                    gameVersion = versionId,
                    javaExecutable = {
                        baseFiles?.await()
                        awaitJava()
                    },
                    pinned = loaderVersion,
                    onStage = onStage,
                    onProgress = progress.sink("loader"),
                )
            } else {
                versionId
            }
            baseFiles?.await()

            val version = if (profileId == versionId) baseVersion
            else withContext(Dispatchers.IO) { installer.resolve(profileId) }

            val installed = installer.install(version, gameDir, onStage, progress.sink("game"))
            PreparedLaunch(installed, gameDir, awaitJava(), settings.memoryMb, settings, options)
        }
    }

    private suspend fun resolveJava(
        component: String?,
        majorVersion: Int?,
        onProgress: (DownloadProgress) -> Unit,
    ): Path {
        val wanted = component ?: JavaComponents.forMajor(majorVersion ?: 8)
        val major = majorVersion ?: 8

        runCatching { return JavaManager.ensure(wanted, onProgress = onProgress) }
            .onFailure { Log.warn("could not install runtime $wanted: ${it.message}") }

        JavaManager.findLocal(major)?.let { return it }

        throw IOException(
            "Не удалось получить Java $major для этой версии. " +
                "Проверьте интернет или установите JDK $major вручную."
        )
    }

    private fun logCommand(command: List<String>, account: Account) {
        val token = account.accessToken
        val safe = command.joinToString(" ") { part ->
            if (token.length > 8 && part.contains(token)) part.replace(token, "<redacted>") else part
        }
        Log.info("command: $safe")
    }

    const val GAME_LOG = "latest-game.log"
}
