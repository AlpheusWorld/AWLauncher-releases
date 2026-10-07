package ru.aw.launcher.ui.screens

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.ui.ModIcons
import ru.aw.launcher.ui.LauncherState
import ru.aw.launcher.ui.theme.AWTheme
import ru.aw.launcher.core.PreloadResult
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.meta.VersionManifest
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.instance.LocalBuild
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import java.util.UUID

class ScreenshotsTest {
    @TempDir lateinit var root: Path

    @Test fun `gallery combines folders without duplicates and sorts newest first`() = runBlocking {
        val first = ScreenshotFolder("one", "Выживание", root.resolve("one"))
        val second = ScreenshotFolder("two", "Творчество", root.resolve("two"))
        val a = Files.createDirectories(first.gameDir.resolve("screenshots")).resolve("old.PNG")
        val b = Files.createDirectories(second.gameDir.resolve("screenshots")).resolve("new.png")
        Files.write(a, byteArrayOf(1)); Files.write(b, byteArrayOf(2))
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_000)); Files.setLastModifiedTime(b, FileTime.fromMillis(2_000))
        Files.write(a.resolveSibling("options.txt"), byteArrayOf(3))
        Files.createDirectories(a.resolveSibling("folder.png"))
        val result = scanScreenshots(listOf(first, second, first.copy(key = "duplicate")))
        assertEquals(listOf(b, a), result.files.map { it.path })
        assertEquals(listOf("two", "one"), result.files.map { it.folder.key })
        assertTrue(result.errors.isEmpty())
    }

    @Test fun `missing directories are empty without creating game files`() = runBlocking {
        val result = scanScreenshots(listOf(ScreenshotFolder("empty", "Пустая сборка", root.resolve("missing"))))
        assertTrue(result.files.isEmpty()); assertTrue(result.errors.isEmpty())
        assertFalse(Files.exists(root.resolve("missing")))
    }

    @Test fun `bad screenshot directory does not hide other builds`() = runBlocking {
        Files.write(root.resolve("screenshots"), byteArrayOf(1))
        val other = Files.createDirectories(root.resolve("other/screenshots")).resolve("ok.webp")
        Files.write(other, byteArrayOf(2))
        val result = scanScreenshots(listOf(ScreenshotFolder("bad", "Недоступная", root), ScreenshotFolder("ok", "Доступная", root.resolve("other"))))
        assertEquals(listOf(other), result.files.map { it.path })
        assertEquals(1, result.errors.size)
    }

    @Test fun `gallery does not follow symbolic screenshots`() = runBlocking {
        val outside = Files.write(root.resolve("outside.png"), byteArrayOf(1))
        val directory = Files.createDirectories(root.resolve("game/screenshots"))
        val linked = runCatching { Files.createSymbolicLink(directory.resolve("linked.png"), outside) }.isSuccess
        assumeTrue(linked, "Symbolic links unavailable on this host")
        assertTrue(scanScreenshots(listOf(ScreenshotFolder("game", "Сборка", directory.parent))).files.isEmpty())
    }

    @Test fun `image cache respects file revision and rejects corrupt images`() = runBlocking {
        val file = root.resolve("preview.png")
        fun write(color: Color) {
            val image = BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().run { this.color = color; fillRect(0, 0, 32, 16); dispose() }
            ImageIO.write(image, "png", file.toFile())
        }
        write(Color.RED)
        val first = ModIcons.loadLocal(file, 384, stamp = "first")!!
        write(Color.BLUE)
        val second = ModIcons.loadLocal(file, 384, stamp = "second")!!
        assertEquals(32, second.width); assertEquals(16, second.height)
        assertTrue(first.toPixelMap()[0, 0].red > .9f)
        assertTrue(second.toPixelMap()[0, 0].blue > .9f)
        val corrupt = Files.write(root.resolve("broken.png"), "broken image".toByteArray())
        assertNull(ModIcons.loadLocal(corrupt, 384))
        assertNull(ModIcons.loadLocal(file, 384, stamp = "bounded", maxBytes = 1))
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    fun `thumbnail opens viewer and arrows and escape are handled inside launcher`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val build = LocalBuild(UUID.randomUUID().toString(), "Gallery", "1.21.1", LoaderKind.VANILLA)
        val state = LauncherState(scope, PreloadResult(VersionManifest(), emptyMap(), emptySet(), builds = listOf(build)))
        val directory = Files.createDirectories(state.gameDirOf(state.entryFor(build)).resolve("screenshots"))
        val paths = listOf(directory.resolve("first.png"), directory.resolve("second.png"))
        paths.forEach { file -> ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file.toFile()) }
        val scene = ImageComposeScene(1000, 650, density = Density(1f)) { AWTheme(ThemeMode.OLED) { ScreenshotsScreen(state) } }
        var time = 0L
        fun frames() { repeat(30) { time += 16_666_667; scene.render(time).close(); Thread.sleep(10) } }
        try {
            frames()
            scene.sendPointerEvent(PointerEventType.Press, Offset(70f, 220f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(70f, 220f))
            frames()
            assertTrue(scene.sendKeyEvent(KeyEvent(key = Key.DirectionRight, type = KeyEventType.KeyDown)))
            frames()
            assertTrue(scene.sendKeyEvent(KeyEvent(key = Key.DirectionLeft, type = KeyEventType.KeyDown)))
            assertTrue(scene.sendKeyEvent(KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown)))
            frames()
            assertFalse(scene.sendKeyEvent(KeyEvent(key = Key.DirectionRight, type = KeyEventType.KeyDown)))
        } finally {
            scene.close(); scope.cancel()
            paths.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(directory); Files.deleteIfExists(directory.parent)
        }
    }
}
