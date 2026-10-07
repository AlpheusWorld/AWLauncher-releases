package ru.aw.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWTheme
import java.nio.file.Files
import java.nio.file.Path

class SwitchScrollTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mouse wheel scrolling keeps each visible thumb at its actual mod state`(animated: Boolean) {
        val list = LazyListState()
        var changes = 0
        var onThumb = 0
        var offThumb = 0
        var onTrack = 0
        var offTrack = 0
        val scene = ImageComposeScene(width = 180, height = 256, density = Density(1f)) {
            AWTheme(ThemeMode.DARK) {
                onThumb = AWColors.OnAccent.toArgb()
                offThumb = AWColors.TextMuted.toArgb()
                onTrack = AWColors.Accent.toArgb()
                offTrack = AWColors.SurfaceHigh.toArgb()
                LazyColumn(Modifier.fillMaxSize().background(AWColors.Background), state = list) {
                    items((0 until 73).toList(), key = { "mod-$it.jar" }) { index ->
                        Row(Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF000000.toInt() or (index + 1))), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            AWSwitch(index % 3 != 0, animateChanges = animated) { changes++ }
                        }
                    }
                }
            }
        }
        var time = 0L
        val visited = mutableSetOf<Int>()
        fun checkFrame() {
            time += 20_000_000L
            scene.render(time).use { image ->
                Bitmap().use { pixels ->
                    assertTrue(pixels.allocPixels(image.imageInfo))
                    assertTrue(image.readPixels(pixels))
                    // Identify rows from the actual rendered pixels. Wheel-scroll layoutInfo
                    // may already describe the next frame while this image is being returned.
                    var start = 0
                    while (start < 256) {
                        val marker = pixels.getColor(4, start)
                        var end = start + 1
                        while (end < 256 && pixels.getColor(4, end) == marker) end++
                        val index = (marker and 0xFF) - 1
                        if (end - start >= 60 && index in 0 until 73) {
                            visited += index
                            val y = (start + end - 1) / 2
                            val checked = index % 3 != 0
                            assertEquals(if (checked) onThumb else offThumb, pixels.getColor(if (checked) 100 else 80, y),
                                "Thumb in row $index must match the mod while the mouse wheel is moving")
                            assertEquals(if (checked) onTrack else offTrack, pixels.getColor(if (checked) 80 else 100, y))
                        }
                        start = end
                    }
                }
            }
            Thread.sleep(1)
        }
        try {
            repeat(40) { checkFrame() }
            scene.sendPointerEvent(PointerEventType.Move, Offset(90f, 128f))
            for (direction in listOf(1f, -1f, 1f, -1f)) {
                repeat(25) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(90f, 128f), scrollDelta = Offset(0f, direction * 2.5f))
                    checkFrame()
                }
                repeat(40) { checkFrame() }
            }
            assertTrue(visited.size > 10, "The wheel must actually recycle rows, not just render the initial viewport")
            assertEquals(0, changes, "Wheel scrolling must not toggle any mod")
        } finally { scene.close() }
    }

    @Test
    fun `a switch without animation still handles clicks and respects disabled state`() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var changes = 0
        val scene = ImageComposeScene(width = 180, height = 64, density = Density(1f)) {
            AWTheme(ThemeMode.DARK) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    AWSwitch(checked.value, enabled.value, animateChanges = false) { checked.value = it; changes++ }
                }
            }
        }
        var time = 0L
        fun settle() = repeat(40) { time += 16_000_000L; scene.render(time).close() }
        fun click() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(90f, 32f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(90f, 32f), button = PointerButton.Primary)
            settle()
        }
        try {
            settle()
            click()
            assertTrue(checked.value)
            click()
            assertFalse(checked.value)
            assertEquals(2, changes)
            enabled.value = false
            settle()
            click()
            assertFalse(checked.value)
            assertEquals(2, changes)
        } finally { scene.close() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `scrolling alternating mod rows does not animate switches or toggle their state`(animated: Boolean) {
        val list = LazyListState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var changes = 0
        val scene = ImageComposeScene(width = 180, height = 256, density = Density(1f)) {
            AWTheme(ThemeMode.DARK) {
                LazyColumn(Modifier.fillMaxSize().background(AWColors.Background), state = list) {
                    items((0 until 40).toList(), key = { "mod-$it.jar" }) { index ->
                        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            AWSwitch(index % 2 == 0, animateChanges = animated) { changes++ }
                        }
                    }
                }
            }
        }
        var time = 0L
        fun frame(): ByteArray {
            time += 16_000_000L
            return scene.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
        }
        try {
            repeat(40) { frame() }
            for (target in listOf(5, 0, 15, 8, 21, 0)) {
                scope.launch { list.scrollToItem(target) }
                var first = frame()
                repeat(2) { first = frame() }
                assertEquals(target, list.firstVisibleItemIndex)
                var settled = first
                repeat(40) { settled = frame() }
                val folder = Path.of("build", "preview", "switch-scroll").also { Files.createDirectories(it) }
                Files.write(folder.resolve("row-$target-entered.png"), first)
                Files.write(folder.resolve("row-$target-settled.png"), settled)
                assertArrayEquals(first, settled, "Switch thumb must already match its mod on entering row $target")
                assertEquals(0, changes, "Scrolling must not invoke onCheckedChange")
            }
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
