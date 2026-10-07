package ru.aw.launcher.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrandTest {

    @Test
    fun `every window icon decodes at the size Windows asks for`() {
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64)
        assertEquals(sizes.map { it to it }, Brand.windowIcons.map { it.width to it.height })
        Brand.windowIcons.forEach { assertEquals(0, it.getRGB(0, 0) ushr 24, "corner of ${it.width}px") }
    }

    @Test
    fun `both theme wordmarks have the rail asset size`() {
        assertEquals(624, Brand.wordmarkDark.width)
        assertEquals(104, Brand.wordmarkDark.height)
        assertEquals(624, Brand.wordmarkLight.width)
        assertEquals(104, Brand.wordmarkLight.height)
    }
}
