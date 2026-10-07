package ru.aw.launcher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aw.launcher.core.ClassArchives
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.MemoryRelease
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.PlayArguments
import ru.aw.launcher.core.PreloadResult
import ru.aw.launcher.core.Preloader
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.SingleInstance
import ru.aw.launcher.core.VerifyCache
import ru.aw.launcher.discord.DiscordPresence
import ru.aw.launcher.discord.Presence
import ru.aw.launcher.net.Http
import ru.aw.launcher.ui.App
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.LauncherTitleBar
import ru.aw.launcher.ui.SplashContent
import ru.aw.launcher.ui.WindowChrome
import ru.aw.launcher.ui.theme.AWTheme
import ru.aw.launcher.ui.theme.isDark

fun main(args: Array<String>) {
    val instance = bootstrap(args)
    val playRequest = PlayArguments.parse(args)?.also { Log.info("asked to start ${it.versionId} (${it.loader})") }
    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val startupProgress = MutableStateFlow(0f to "Запускаюсь")
    val preloaded = MutableStateFlow<PreloadResult?>(null)
    startupScope.launch {
        preloaded.value = Preloader.run { value, text -> startupProgress.value = value to text }
    }

    application {
        LaunchedEffect(Unit) { awaitCancellation() }
        val settings by Settings.state.collectAsState()

        val ready by preloaded.collectAsState()
        val progress by startupProgress.collectAsState()

        DisposableEffect(Unit) { onDispose { startupScope.cancel() } }

        val loaded = ready
        if (loaded == null) {
            Window(
                onCloseRequest = { shutdownAndExit() },
                title = "AWLauncher",
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                state = rememberWindowState(
                    width = 520.dp,
                    height = 180.dp,
                    position = WindowPosition(Alignment.Center),
                ),
            ) {
                LaunchedEffect(Unit) {
                    Log.info("startup: splash at ${sinceProcessStart()} ms")
                    WindowChrome.applyIcon(window)
                }
                AWTheme(mode = settings.themeMode) { SplashContent(progress.first, progress.second) }
            }
        } else {
            val scope = rememberCoroutineScope()
            val state = remember {
                LauncherState(scope, loaded, playRequest).also { it.onQuit = ::shutdownAndExit }
            }
            val windowState = rememberWindowState(
                width = 1280.dp,
                height = 780.dp,
                position = WindowPosition(Alignment.Center),
            )
            var gameProcess by remember { mutableStateOf<Process?>(null) }
            var reportedReady by remember { mutableStateOf(false) }
            var shownDuringGame by remember { mutableStateOf(false) }
            var raised by remember { mutableStateOf(0) }

            LaunchedEffect(Unit) {
                for (handed in instance.requests) {
                    Log.info("another start handed over: ${handed.joinToString(" ").ifEmpty { "no arguments" }}")
                    if (gameProcess != null) shownDuringGame = true
                    windowState.isMinimized = false
                    raised++
                    PlayArguments.parse(handed.toTypedArray())
                        ?.let { state.playFromShortcut(it, gameRunning = gameProcess != null) }
                }
            }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { runCatching { ClassArchives.removeStale() } }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    state.checkForUpdates()
                    kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
                }
            }
            LaunchedEffect(Unit) { state.refreshActivity() }
            LaunchedEffect(Unit) {
                DiscordPresence.show(Presence.Launcher)
                DiscordPresence.start()
            }

            LaunchedEffect(gameProcess) {
                val process = gameProcess ?: return@LaunchedEffect
                Log.info("waiting for the game to exit, pid=${runCatching { process.pid() }.getOrDefault(-1)}")
                if (!shownDuringGame) MemoryRelease.afterWindowClosed()
                withContext(Dispatchers.IO) { runCatching { process.waitFor() } }
                Log.info("game exited with ${process.exitValue()}, showing the launcher again")
                DiscordPresence.show(Presence.Launcher)
                state.gameExited(process.exitValue())
                state.refreshActivity()
                shownDuringGame = false
                gameProcess = null
            }

            if (gameProcess == null || shownDuringGame) {
                val closeWindow: () -> Unit = {
                    if (gameProcess?.isAlive == true) {
                        Log.info("window closed while the game runs, staying in the background")
                        shownDuringGame = false
                        scope.launch { MemoryRelease.afterWindowClosed() }
                    } else shutdownAndExit()
                }
                Window(
                    onCloseRequest = closeWindow,
                    title = "AWLauncher",
                    undecorated = true,
                    resizable = true,
                    state = windowState,
                    onPreviewKeyEvent = { state.onKey(it) },
                ) {
                    window.minimumSize = java.awt.Dimension(600, 420)
                    var nativeFrame by remember(window) { mutableStateOf(false) }
                    DisposableEffect(window) {
                        val release = WindowChrome.configureFramelessBounds(window)
                        nativeFrame = WindowChrome.usesNativeFrame(window)
                        onDispose { release() }
                    }
                    val dark = settings.themeMode.isDark(isSystemInDarkTheme())
                    LaunchedEffect(Unit) {
                        WindowChrome.applyIcon(window)
                        if (!reportedReady) {
                            reportedReady = true
                            Log.info("startup: launcher ready at ${sinceProcessStart()} ms")
                        }
                        if (state.consumePendingPlay()) state.play()
                    }
                    LaunchedEffect(dark, settings.themeMode) { WindowChrome.applyTheme(window, dark) }
                    LaunchedEffect(raised) {
                        if (raised > 0) {
                            window.toFront()
                            window.requestFocus()
                        }
                    }
                    AWTheme(mode = settings.themeMode) {
                        App(state, onGameStarted = {
                            shownDuringGame = Settings.current.keepLauncherOpen
                            gameProcess = it
                        }, titleBar = {
                            LauncherTitleBar(state, this@Window, windowState,
                                gameRunning = gameProcess?.isAlive == true, onClose = closeWindow, nativeFrame = nativeFrame)
                        })
                    }
                }
            }
        }
    }
}

private fun bootstrap(args: Array<String>): SingleInstance {
    Paths.ensureBaseDirs()

    val instance = SingleInstance(Paths.root)
    when (instance.start(args.toList())) {
        SingleInstance.Role.PRIMARY -> Unit
        SingleInstance.Role.HANDED_OVER -> exitProcess(0)
        SingleInstance.Role.UNREACHABLE -> {
            JOptionPane.showMessageDialog(
                null,
                "AWLauncher уже запущен, но не отвечает.\nЗакройте его в диспетчере задач и запустите снова.",
                "AWLauncher",
                JOptionPane.WARNING_MESSAGE,
            )
            exitProcess(0)
        }
    }

    Log.init()
    Log.info("AWLauncher starting, data dir: ${Paths.root}, jvm up in ${sinceProcessStart()} ms")

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        Log.error("uncaught exception on ${thread.name}", error)
    }
    return instance
}

private fun sinceProcessStart(): Long =
    ProcessHandle.current().info().startInstant()
        .map { System.currentTimeMillis() - it.toEpochMilli() }
        .orElse(-1L)

private fun shutdownAndExit() {
    runCatching { DiscordPresence.stop() }
    runCatching { VerifyCache.save() }
    runCatching { Settings.save() }
    runCatching { Http.shutdown() }
    Log.info("bye")
    Log.close()
    exitProcess(0)
}
