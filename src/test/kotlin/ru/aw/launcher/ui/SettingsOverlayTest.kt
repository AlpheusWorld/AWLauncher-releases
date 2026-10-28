package ru.aw.launcher.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.core.PreloadResult
import ru.aw.launcher.meta.ManifestVersion
import ru.aw.launcher.meta.VersionManifest
import ru.aw.launcher.ui.theme.AWTheme
import ru.aw.launcher.ui.screens.LauncherUpdateProgress
import ru.aw.launcher.ui.screens.LaunchDownloads
import ru.aw.launcher.net.DownloadProgress
import androidx.compose.ui.Modifier
import ru.aw.launcher.update.UpdateManifest
import ru.aw.launcher.update.UpdateState

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsOverlayTest {
    @Test
    fun `account control opens a menu without changing the current page`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet()))
        val scene = ImageComposeScene(600, 420, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 392f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 392f))
            assertTrue(state.accountMenuOpen)
            assertEquals(Screen.HOME, state.screen)
        } finally { scene.close(); scope.cancel() }
    }

    @Test
    fun `home download cancel action cancels active task`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet()))
        state.downloads.enqueue("preview", "Sodium") { reporter ->
            reporter.stage("Загрузка файлов")
            reporter.progress(DownloadProgress(2, 12, 42, 247, 9, "Sodium.jar"))
            awaitCancellation()
        }
        val scene = ImageComposeScene(290, 250, density = Density(1f)) { AWTheme { LaunchDownloads(state, Modifier, false) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(245f, 196f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(245f, 196f))
            assertTrue(state.downloads.active == null || state.downloads.active?.status == DownloadStatus.CANCELLING)
        } finally { scene.close(); scope.cancel() }
    }

    @Test
    fun `play and library navigation remain reachable at the minimum size`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet()))
        val scene = ImageComposeScene(600, 420, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 114f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 114f))
            assertEquals(Screen.PLAY, state.screen)
            repeat(20) { scene.render((it + 20) * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 84f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 84f))
            assertEquals(Screen.HOME, state.screen)
        } finally { scene.close(); scope.cancel() }
    }

    @Test
    fun `update progress cancel button invokes cancellation`() {
        var cancelled = false
        val progress = UpdateState.Downloading(UpdateManifest("1.0.36", "https://example.invalid/update", "0".repeat(64)), 0.17f, 9L * 1024 * 1024)
        val scene = ImageComposeScene(480, 180, density = Density(1f)) { AWTheme { LauncherUpdateProgress(progress) { cancelled = true } } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(416f, 84f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(416f, 84f))
            assertTrue(cancelled, "The cancellation action must remain reachable")
        } finally { scene.close() }
    }

    @Test
    fun `settings remain reachable at the minimum window height`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet()))
        val scene = ImageComposeScene(600, 420, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 264f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 264f))
            assertTrue(state.screen == Screen.SETTINGS, "The bottom navigation button must be visible and clickable")
        } finally { scene.close(); scope.cancel() }
    }

    @Test
    fun `settings remain reachable at the compact navigation boundary`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet()))
        val scene = ImageComposeScene(815, 503, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 264f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 264f))
            assertTrue(state.screen == Screen.SETTINGS, "The bottom navigation button must remain reachable")
        } finally { scene.close(); scope.cancel() }
    }

    @Test
    fun `escape finishes closing the overlay before releasing keyboard navigation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(versions = listOf(ManifestVersion("1.21.1", url = ""))), emptyMap(), emptySet()))
        state.screen = Screen.PLAY
        state.openSettings()
        val scene = ImageComposeScene(1050, 660, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        var time = 0L
        fun frames(count: Int) { repeat(count) { time += 16_666_667; scene.render(time).close() } }
        try {
            frames(20)
            assertEquals(1, state.openMenus)
            val escape = KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown)
            assertTrue(scene.sendKeyEvent(escape))
            assertNull(state.modal)
            assertEquals(1, state.openMenus)
            frames(20)
            assertEquals(0, state.openMenus)
            assertEquals(Screen.PLAY, state.screen)
        } finally { scene.close(); scope.cancel() }
    }

    @Test
    fun `clicking through the backdrop cannot navigate the page behind it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(versions = listOf(ManifestVersion("1.21.1", url = ""))), emptyMap(), emptySet()))
        state.openSettings()
        val scene = ImageComposeScene(1280, 780, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 171f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 171f))
            assertNull(state.modal)
            assertEquals(Screen.HOME, state.screen)
        } finally { scene.close(); scope.cancel() }
    }
}
