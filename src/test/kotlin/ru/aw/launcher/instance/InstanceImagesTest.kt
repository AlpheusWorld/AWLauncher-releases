package ru.aw.launcher.instance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import javax.imageio.ImageIO

class InstanceImagesTest {
    @TempDir lateinit var root: Path

    @Test
    fun `uploaded icons stay inside the profile and are addressed by content`() {
        val source = root.resolve("source.png")
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", source.toFile())
        val dir = root.resolve("profile")
        val first = InstanceImages.import(dir, source)
        assertEquals(first, InstanceImages.import(dir, source))
        val copy = InstanceImages.resolve(dir, first)!!
        assertTrue(copy.startsWith(dir))
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(copy))
        assertNull(InstanceImages.resolve(dir, "../accounts.json"))
        assertNull(InstanceImages.resolve(dir, source.toString()))
    }

    @Test
    fun `corrupt and oversized images do not create icon files`() {
        val invalid = Files.writeString(root.resolve("fake.png"), "not an image")
        val dir = root.resolve("profile")
        assertThrows(IOException::class.java) { InstanceImages.import(dir, invalid) }
        val large = root.resolve("large.png")
        Files.newOutputStream(large).use { it.write(ByteArray(InstanceImages.MAX_BYTES + 1)) }
        assertThrows(IOException::class.java) { InstanceImages.import(dir, large) }
        assertFalse(Files.exists(dir))
    }
}
