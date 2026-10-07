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

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsOverlayTest {
    @Test
    fun `settings remain reachable at the minimum window height`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet()))
        val scene = ImageComposeScene(600, 420, density = Density(1f)) { AWTheme { App(state, onGameStarted = {}) } }
        try {
            repeat(20) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(32f, 392f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(32f, 392f))
            assertTrue(state.modal is Modal.Settings, "The bottom navigation button must be visible and clickable")
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
