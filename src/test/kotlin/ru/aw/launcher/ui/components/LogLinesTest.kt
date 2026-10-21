package ru.aw.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWTheme

class LogLinesTest {
    @Test
    fun opensAtEndFollowsNewLinesAndPreservesManualScrollingUntilReturnToEnd() {
        val state = LazyListState()
        val lines = mutableStateOf((0 until 3000).map { "[INFO] Line "+it })
        val scene = ImageComposeScene(560, 280, density = Density(1f)) {
            AWTheme(ThemeMode.DARK) {
                LogLines(lines.value, Modifier.fillMaxSize().background(AWColors.Surface), state)
            }
        }
        var time = 0L
        fun settle() {
            repeat(50) {
                time += 20_000_000L
                scene.render(time).close()
                Thread.sleep(1)
            }
        }
        try {
            settle()
            assertFalse(state.canScrollForward, "The journal must open at its last line")
            assertEquals(2999, state.layoutInfo.visibleItemsInfo.last().index)
            // GameLogs keeps a fixed-size tail, so following must work when the count stays at 3000.
            lines.value = lines.value.drop(1) + "[ERROR] New line\n".repeat(50)
            settle()
            assertFalse(state.canScrollForward, "Even a final row taller than the viewport must be shown at its end")
            scene.sendPointerEvent(PointerEventType.Move, Offset(260f, 110f))
            repeat(5) { scene.sendPointerEvent(PointerEventType.Scroll, Offset(260f, 110f), scrollDelta = Offset(0f, -3f)); settle() }
            assertTrue(state.canScrollForward, "Wheel scrolling must leave the bottom")
            scene.render(time + 20_000_000L).use { image ->
                val output = Path.of("build", "preview", "journal-scroll.png")
                Files.createDirectories(output.parent)
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output, it.bytes) }
            }
            time += 20_000_000L
            val position = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            lines.value = lines.value + "[INFO] Newest entry"
            settle()
            assertEquals(position, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset, "New lines must not interrupt reading older entries")
            // The scrollbar track must accept a click near its top.
            scene.sendPointerEvent(PointerEventType.Press, Offset(553f, 10f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(553f, 10f), button = PointerButton.Primary)
            settle()
            assertTrue(state.canScrollForward)
            // Resume following with the visible return-to-end button.
            scene.sendPointerEvent(PointerEventType.Press, Offset(490f, 244f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(490f, 244f), button = PointerButton.Primary)
            settle()
            assertFalse(state.canScrollForward, "The return-to-end button must resume following")
            lines.value = lines.value + "[INFO] Final entry"
            settle()
            assertFalse(state.canScrollForward)
            lines.value = emptyList()
            settle()
            lines.value = listOf("Search result")
            settle()
            assertEquals(0, state.firstVisibleItemIndex)
        } finally { scene.close() }
    }
}
