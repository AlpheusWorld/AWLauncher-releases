package ru.aw.launcher.auth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

class SkinLibraryTest {
    @TempDir lateinit var directory: Path
    private fun image(color: Int = 0xFF5577AA.toInt()) = MinecraftSkins.fromImage(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until 64) for (x in 0 until 64) setRGB(x, y, color)
    })

    @Test fun `collection survives a restart and preserves edited model and cape`() = runBlocking {
        val library = SkinLibrary(directory)
        val first = library.save(image(), "Survival", SkinModel.CLASSIC, "cape-owned")
        val second = library.save(image(0xFFAABBCC.toInt()), "Creative", SkinModel.SLIM, null)
        val edited = library.save(image(0xFF123456.toInt()), "Explorer", SkinModel.SLIM, null, first.id)
        val restarted = SkinLibrary(directory)
        assertEquals(listOf(edited, second), restarted.list())
        assertEquals(0xFF123456.toInt(), restarted.image(edited).image.getRGB(12, 12))
        assertEquals(SkinModel.SLIM, edited.model)
        assertNull(edited.capeId)
    }

    @Test fun `reordering and removing only affect requested preset`() = runBlocking {
        val library = SkinLibrary(directory)
        val first = library.save(image(), "First", SkinModel.CLASSIC, null)
        val second = library.save(image(), "Second", SkinModel.CLASSIC, null)
        val third = library.save(image(), "Third", SkinModel.CLASSIC, null)
        assertEquals(listOf(third, first, second), library.move(third.id, 0))
        assertEquals(listOf(third, second), library.remove(first.id))
        assertFalse(Files.exists(directory.resolve("${first.id}.png")))
        assertTrue(Files.exists(directory.resolve("${second.id}.png")))
    }

    @Test fun `invalid identifiers cannot access files outside the collection`() = runBlocking {
        val outside = directory.resolveSibling("outside.png")
        Files.write(outside, image().png)
        val library = SkinLibrary(directory)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.remove("../outside") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { library.image(SavedSkin("../outside", "Bad", SkinModel.CLASSIC)) } }
        assertTrue(Files.exists(outside))
    }

    @Test fun `a damaged index is reported and never overwritten by adding a skin`() = runBlocking {
        val index = directory.resolve("library.json")
        Files.writeString(index, "broken")
        val library = SkinLibrary(directory)
        assertThrows(Exception::class.java) { runBlocking { library.save(image(), "New", SkinModel.CLASSIC, null) } }
        assertEquals("broken", Files.readString(index))
    }

    @Test fun `arm model is detected while legacy textures remain classic`() {
        val modern = image().image
        assertEquals(SkinModel.CLASSIC, MinecraftSkins.modelOf(modern))
        for (y in 20 until 32) for (x in 54 until 56) modern.setRGB(x, y, 0)
        assertEquals(SkinModel.SLIM, MinecraftSkins.modelOf(modern))
        assertEquals(SkinModel.CLASSIC, MinecraftSkins.modelOf(BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)))
    }
}
