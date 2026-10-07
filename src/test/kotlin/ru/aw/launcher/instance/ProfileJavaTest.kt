package ru.aw.launcher.instance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.runtime.JavaManager
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException

class ProfileJavaTest {
    @TempDir lateinit var root: Path

    @Test
    fun `custom Java accepts executable and JDK directory and checks required major`() {
        val home = Files.createDirectories(root.resolve("Custom JDK"))
        val bin = Files.createDirectories(home.resolve("bin"))
        val executable = Files.writeString(bin.resolve("java.exe"), "fixture")
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.9\"\n")
        assertEquals(executable, JavaManager.customExecutable(home.toString(), 21))
        assertEquals(executable, JavaManager.customExecutable("\"$executable\"", 17))
        assertThrows(IOException::class.java) { JavaManager.customExecutable(executable.toString(), 25) }
    }

    @Test
    fun `invalid executable and unknown runtime fail before game launch`() {
        val bin = Files.createDirectories(root.resolve("bin"))
        val other = Files.writeString(bin.resolve("other.exe"), "fixture")
        assertThrows(IOException::class.java) { JavaManager.customExecutable(other.toString()) }
        val java = Files.writeString(bin.resolve("java.exe"), "fixture")
        assertThrows(IOException::class.java) { JavaManager.customExecutable(java.toString()) }
        assertThrows(IOException::class.java) { JavaManager.customExecutable(root.resolve("missing.exe").toString()) }
    }
}
