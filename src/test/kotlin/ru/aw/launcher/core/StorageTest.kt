package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.aw.launcher.meta.LoaderKind
import java.io.IOException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

class StorageTest {

    private val known = setOf("1.21.4", "26.3-rc-1", "1.20.1", "26.3", "1.7.10")

    @Test
    fun `loader profile folders map back to their game version`() {
        assertEquals("1.21.4" to LoaderKind.FABRIC, Storage.profileOwner("fabric-loader-0.16.14-1.21.4", known))
        assertEquals("26.3-rc-1" to LoaderKind.QUILT, Storage.profileOwner("quilt-loader-0.28.0-26.3-rc-1", known))
        assertEquals("1.20.1" to LoaderKind.FORGE, Storage.profileOwner("1.20.1-forge-47.3.0", known))
        assertEquals("1.7.10" to LoaderKind.FORGE, Storage.profileOwner("1.7.10-Forge10.13.4.1614-1.7.10", known))
        assertEquals("1.21.1" to LoaderKind.NEOFORGE, Storage.profileOwner("neoforge-21.1.73", known))
        assertEquals("26.3" to LoaderKind.VANILLA, Storage.profileOwner("26.3", known))
    }

    @Test
    fun `deleting a loader's files spares the vanilla version and the worlds`() {
        val version = "3.14.15"
        val profile = "fabric-loader-0.16.14-$version"
        Paths.versionDir(version).createDirectories()
        Paths.versionJar(version).writeBytes(ByteArray(2048))
        Paths.versionDir(profile).createDirectories()
        Paths.versionJson(profile).writeBytes(ByteArray(100))
        val gameDir = Settings.gameDir(version, LoaderKind.FABRIC)
        gameDir.resolve("saves/My World").createDirectories()
        gameDir.resolve("saves/My World/level.dat").writeBytes(ByteArray(512))

        assertEquals(listOf(profile), Storage.versionIdsOf(version, LoaderKind.FABRIC, listOf(version)))
        assertEquals(listOf(version), Storage.versionIdsOf(version, LoaderKind.VANILLA, listOf(version)))
        assertTrue(Storage.hasWorlds(gameDir))
        assertEquals(512, Storage.sizeOf(gameDir))

        Storage.deleteVersionFiles(Storage.versionIdsOf(version, LoaderKind.FABRIC, listOf(version)))

        assertFalse(Paths.versionDir(profile).exists())
        assertTrue(Paths.versionJar(version).exists(), "the vanilla files belong to another build")
        assertTrue(gameDir.resolve("saves/My World/level.dat").exists())
    }

    @Test
    fun `an instance the Recycle Bin refuses is left where it was`() {
        assumeTrue(Storage.trashAvailable)
        val gameDir = Settings.gameDir("4.4.4", LoaderKind.VANILLA)
        gameDir.resolve("saves/World").createDirectories()
        gameDir.resolve("saves/World/level.dat").writeBytes(ByteArray(16))

        assertThrows<IOException> { Storage.deleteGameDir(gameDir) { false } }
        assertTrue(gameDir.resolve("saves/World/level.dat").exists())
    }

    @Test
    fun `deleting a folder removes a junction inside it, not what it points to`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("win"))
        val shared = Paths.root.resolve("shared-saves")
        shared.resolve("World").createDirectories()
        shared.resolve("World/level.dat").writeBytes(ByteArray(16))
        val gameDir = Settings.gameDir("5.5.5", LoaderKind.VANILLA).createDirectories()
        val junction = ProcessBuilder("cmd", "/c", "mklink", "/J", gameDir.resolve("saves").toString(), shared.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        assumeTrue(junction == 0)

        Storage.deleteTree(gameDir)

        assertFalse(gameDir.exists())
        assertTrue(shared.resolve("World/level.dat").exists())
    }
}
