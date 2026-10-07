package ru.aw.launcher.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class SkinHeadsTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val black = 0xFF000000.toInt()

    private fun skin(height: Int, hat: (Int, Int) -> Int): BufferedImage =
        BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 8 until 16) for (x in 8 until 16) setRGB(x, y, red)
            for (y in 8 until 16) for (x in 40 until 48) setRGB(x, y, hat(x - 40, y - 8))
        }

    @Test
    fun `the hat layer is drawn over the face`() {
        val face = SkinHeads.face(skin(64) { x, y -> if (x == 1 && y == 1) green else 0 })!!
        assertEquals(green, face.getRGB(1, 1))
        assertEquals(red, face.getRGB(0, 0))
    }

    @Test
    fun `an opaque hat on an old 64x32 skin is ignored, as the game ignores it`() {
        val face = SkinHeads.face(skin(32) { _, _ -> black })!!
        assertEquals(red, face.getRGB(3, 3))
    }
}
