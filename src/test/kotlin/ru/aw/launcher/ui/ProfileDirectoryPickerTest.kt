package ru.aw.launcher.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.ui.dialogs.profileDirectories
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ProfileDirectoryPickerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `browser lists only immediate folders in alphabetical order`() {
        Files.createDirectory(directory.resolve("zebra"))
        Files.createDirectories(directory.resolve("Alpha/nested"))
        Files.createDirectory(directory.resolve(".minecraft"))
        Files.writeString(directory.resolve("profile.json"), "{}")
        assertEquals(listOf(".minecraft", "Alpha", "zebra"),
            profileDirectories(directory).map { it.fileName.toString() })
        // All handles must be closed so Windows allows the selected folder to move.
        val renamed = directory.resolveSibling(directory.fileName.toString() + "-moved")
        Files.move(directory, renamed)
        Files.move(renamed, directory)
    }

    @Test
    fun `missing directories and ordinary files report failure instead of an empty selection`() {
        assertThrows<IOException> { profileDirectories(directory.resolve("missing")) }
        val file = Files.writeString(directory.resolve("file.txt"), "text")
        assertThrows<IOException> { profileDirectories(file) }
        assertEquals(emptyList<Path>(), profileDirectories(directory))
    }
}
