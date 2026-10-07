package ru.aw.launcher.ui.components

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectDescriptionTest {
    @Test
    fun `project Markdown retains headings links images lists and tables`() {
        val nodes = ProjectDescription.parse("# Explore\n\n**Bold** and [Guide](https://example.com)\n\n![World](https://example.com/world.png)\n\n- Item\n\n| Name | Version |\n| --- | --- |\n| Minecraft | 1.21 |")
        val html = nodes.joinToString { it.outerHtml() }
        assertTrue(html.contains("<h1>Explore</h1>"))
        assertTrue(html.contains("<strong>Bold</strong>"))
        assertTrue(html.contains("https://example.com/world.png"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<table>"))
    }

    @Test
    fun `author HTML cannot embed scripts forms or local images`() {
        val html = ProjectDescription.parse("<div><script>bad()</script><form action='https://example.com'><input></form><img src='file:///secret'><a href='javascript:bad()'>link</a><b>Good</b></div>")
            .joinToString { it.outerHtml() }
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<form"))
        assertFalse(html.contains("file:"))
        assertFalse(html.contains("javascript:"))
        assertTrue(html.contains("<b>Good</b>"))
    }
}
