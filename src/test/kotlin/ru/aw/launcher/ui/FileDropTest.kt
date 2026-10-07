package ru.aw.launcher.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.ui.components.fileDropPaths
import java.nio.file.Path

class FileDropTest {
    @Test
    fun `file drops decode spaces and Unicode and never treat web addresses as local files`() {
        val path = Path.of(System.getProperty("java.io.tmpdir")).resolve("Набор ресурсов/test mod.jar").toAbsolutePath().normalize()
        val dropped = fileDropPaths(listOf(path.toUri().toASCIIString(), path.toString(), "https://example.com/mod.jar"))
        assertEquals(listOf(path), dropped)
    }
}
