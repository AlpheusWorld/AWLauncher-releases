package ru.aw.launcher.dev

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import ru.aw.launcher.activity.PlayHistory
import ru.aw.launcher.activity.PlaySession
import ru.aw.launcher.core.*
import ru.aw.launcher.instance.LocalBuild
import ru.aw.launcher.meta.*
import ru.aw.launcher.ui.*
import ru.aw.launcher.ui.components.LocalDialogPreview
import ru.aw.launcher.ui.theme.AWTheme
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.system.exitProcess

/** Offscreen CPU rendering benchmark. No network, game launch or real user data. */
fun main(args: Array<String>) {
    val output = Path.of(args.firstOrNull() ?: "build/ui-performance.tsv")
    Files.createDirectories(output.parent)
    Notices.file = null
    PlayHistory.cacheFile = null
    PlayHistory.historyFile = output.resolveSibling("ui-benchmark-history.json")
    Settings.update { it.copy(language = Language.RU, themeMode = ThemeMode.OLED) }
    val builds = (1..24).map { index ->
        LocalBuild("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "Survival $index", "1.21.1", LoaderKind.VANILLA)
    }
    val now = System.currentTimeMillis()
    val sessions = builds.flatMapIndexed { index, build -> (1..40).map { day ->
        val start = now - day * 86_400_000L - index * 60_000L
        PlaySession(build.id, build.versionId, build.loader, start, start + 3_600_000,
            buildId = build.id, buildName = build.name)
    } }
    Files.writeString(PlayHistory.historyFile, Json.encodeToString(sessions))
    PlayHistory.resetForTests()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val state = LauncherState(scope, PreloadResult(
        VersionManifest(versions = listOf(ManifestVersion("1.21.1", url = ""))),
        installed = mapOf("1.21.1" to 1L), profiles = emptySet(), builds = builds,
    ))
    runBlocking { state.loadActivity() }
    val scene = ImageComposeScene(1280, 780, density = Density(1f)) {
        CompositionLocalProvider(LocalDialogPreview provides true) {
            AWTheme(ThemeMode.OLED) { App(state, onGameStarted = {}) }
        }
    }
    var time = 0L
    fun frame(): Double {
        time += 16_666_667L
        val started = System.nanoTime()
        scene.render(time).close()
        return (System.nanoTime() - started) / 1_000_000.0
    }
    val actions = linkedMapOf<String, () -> Unit>(
        "library" to { state.screen = Screen.HOME },
        "builds" to { state.screen = Screen.BUILDS },
        "activity" to { state.screen = Screen.ACTIVITY },
        "settings-game" to { state.screen = Screen.HOME; state.openSettings(section = 2) },
        "settings-language" to { state.screen = Screen.HOME; state.openSettings(section = 1) },
    )
    val opens = actions.keys.associateWith { ArrayList<Double>() }
    val frames = actions.keys.associateWith { ArrayList<Double>() }
    fun percentile(values: List<Double>, percentile: Double): Double =
        values.sorted()[(values.lastIndex * percentile).toInt()]
    try {
        repeat(32) { frame() }
        repeat(9) { iteration ->
            actions.forEach { (name, action) ->
                state.modal = null
                state.screen = Screen.ACCOUNTS
                repeat(24) { frame() }
                action()
                val first = frame()
                val sampled = List(24) { frame() }
                if (iteration >= 3) {
                    opens.getValue(name) += first
                    frames.getValue(name) += sampled
                }
            }
        }
        val report = buildString {
            appendLine("case\topen_p50_ms\topen_p95_ms\tframe_p95_ms\tframes_over_16_7_ms")
            actions.keys.forEach { name ->
                val opening = opens.getValue(name)
                val motion = frames.getValue(name)
                appendLine(String.format(Locale.ROOT, "%s\t%.2f\t%.2f\t%.2f\t%d", name,
                    percentile(opening, 0.5), percentile(opening, 0.95), percentile(motion, 0.95), motion.count { it > 16.7 }))
            }
        }
        Files.writeString(output, report)
        print(report)
    } finally {
        scene.close()
        scope.cancel()
    }
    exitProcess(0)
}
