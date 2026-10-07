package ru.aw.launcher.install

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Paths
import ru.aw.launcher.meta.VersionJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class LegacyForgeTest {

    private val forgeJar = ByteArray(1024) { it.toByte() }

    private fun installer(profile: String, vararg files: Pair<String, ByteArray>): Path {
        val jar = Files.createTempFile(Paths.cache.createDirectories(), "forge-installer", ".jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("install_profile.json"))
            zip.write(profile.toByteArray())
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
            }
        }
        return jar
    }

    @Test
    fun `an old-style installer is unpacked into the profile and the Forge jar`() {
        val jar = installer(
            """
            {"install":{"path":"net.minecraftforge:forge:7.7.10-10.13.4.1614-7.7.10",
                        "filePath":"forge-7.7.10-10.13.4.1614-7.7.10-universal.jar","target":"7.7.10-Forge10.13.4.1614-7.7.10"},
             "versionInfo":{"id":"7.7.10-Forge10.13.4.1614-7.7.10","inheritsFrom":"7.7.10","jar":"7.7.10",
                            "mainClass":"net.minecraft.launchwrapper.Launch",
                            "libraries":[{"name":"net.minecraftforge:forge:7.7.10-10.13.4.1614-7.7.10"}]}}
            """.trimIndent(),
            "forge-7.7.10-10.13.4.1614-7.7.10-universal.jar" to forgeJar,
        )

        val profile = LegacyForge.read(jar)
        assertNotNull(profile)
        val id = LegacyForge.install(profile!!, jar)

        assertEquals("7.7.10-Forge10.13.4.1614-7.7.10", id)
        val written = Json.decodeFromString<VersionJson>(Paths.versionJson(id).readText())
        assertEquals("7.7.10", written.inheritsFrom)
        assertEquals("net.minecraft.launchwrapper.Launch", written.mainClass)
        val library = Paths.libraryPath(
            "net/minecraftforge/forge/7.7.10-10.13.4.1614-7.7.10/forge-7.7.10-10.13.4.1614-7.7.10.jar"
        )
        assertArrayEquals(forgeJar, library.readBytes())
    }

    @Test
    fun `a new installer is left to install itself`() {
        assertNull(LegacyForge.read(installer("""{"spec":0,"profile":"forge","version":"1.12.2-forge-14.23.5.2859"}""")))
    }

    @Test
    fun `a profile that does not build on vanilla is refused with a reason`() {
        val jar = installer(
            """{"install":{"path":"a:b:1","filePath":"b.jar"},"versionInfo":{"id":"1.6.4-Forge9.11.1.1345"}}"""
        )
        val error = assertThrows<IOException> { LegacyForge.read(jar) }
        assertEquals(true, error.message?.contains("1.7.10"))
    }
}
