package ru.aw.launcher.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.meta.LoaderKind
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ModManagerTest {

    @Test
    fun `explicit versions must match the selected project game and loader`() {
        val version = Modrinth.Version("release", "project", gameVersions = listOf("1.21.1"), loaders = listOf("fabric"))
        ModManager.requireCompatibleVersion(version, "project", "1.21.1", listOf("fabric"))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ModManager.requireCompatibleVersion(version, "different", "1.21.1", listOf("fabric"))
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ModManager.requireCompatibleVersion(version, "project", "1.20.1", listOf("fabric"))
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ModManager.requireCompatibleVersion(version, "project", "1.21.1", listOf("forge"))
        }
    }

    private fun mod(file: Path, enabled: Boolean) = InstalledItem(
        file = file, kind = ContentKind.MOD, enabled = enabled, sha1 = "", projectId = null, title = "Test", versionNumber = "",
        iconUrl = null, update = null,
    )

    @Test
    fun `quilt also takes fabric mods, vanilla takes none`() {
        assertEquals(listOf("quilt", "fabric"), ModManager.loadersFor(LoaderKind.QUILT))
        assertEquals(listOf("neoforge"), ModManager.loadersFor(LoaderKind.NEOFORGE))
        assertTrue(ModManager.loadersFor(LoaderKind.VANILLA).isEmpty())
    }

    @Test
    fun `disabling renames the jar and counts only enabled mods`(@TempDir game: Path) {
        val mods = ModManager.modsDir(game).createDirectories()
        val jar = mods.resolve("sodium.jar").apply { writeText("x") }
        mods.resolve("lithium.jar").writeText("y")
        assertEquals(2, ModManager.count(game))

        ModManager.setEnabled(mod(jar, enabled = true), enabled = false)
        assertFalse(jar.exists())
        val disabled = mods.resolve("sodium.jar.disabled")
        assertTrue(disabled.exists())
        assertEquals(1, ModManager.count(game))

        ModManager.setEnabled(mod(disabled, enabled = false), enabled = true)
        assertTrue(jar.exists())
        assertEquals(2, ModManager.count(game))
    }

    @Test
    fun `removing deletes the file`(@TempDir game: Path) {
        val jar = ModManager.modsDir(game).createDirectories().resolve("jei.jar").apply { writeText("z") }
        ModManager.remove(mod(jar, enabled = true))
        assertFalse(jar.exists())
        assertEquals(0, ModManager.count(game))
    }

    private fun version(id: String, type: String, date: String) =
        Modrinth.Version(id = id, projectId = "AANobbMI", versionType = type, datePublished = date)

    @Test
    fun `updates go only to stable releases`() {
        val installed = version("0.8.14", "release", "2026-08-28T10:00:00.123Z")
        val beta = version("0.8.15-beta.1", "beta", "2026-09-20T10:00:00Z")
        val alpha = version("0.9.0-alpha", "alpha", "2026-09-25T10:00:00Z")
        val release = version("0.8.15", "release", "2026-10-01T10:00:00Z")

        assertFalse(ModManager.isUpgrade(beta, installed))
        assertFalse(ModManager.isUpgrade(alpha, installed))
        assertTrue(ModManager.isUpgrade(release, installed))
        assertFalse(ModManager.isUpgrade(installed, installed))
        assertEquals(installed, ModManager.newestRelease(listOf(alpha, beta, installed)))
        assertEquals(release, ModManager.newestRelease(listOf(beta, installed, release, alpha)))
        assertEquals(null, ModManager.newestRelease(listOf(alpha, beta)))
    }

    @Test
    fun `a beta install is moved to a newer release, never to an older one`() {
        val installedBeta = version("0.8.15-beta.1", "beta", "2026-09-20T10:00:00Z")
        val olderRelease = version("0.8.14", "release", "2026-08-28T10:00:00Z")
        val newerRelease = version("0.8.15", "release", "2026-10-01T10:00:00Z")
        assertFalse(ModManager.isUpgrade(olderRelease, installedBeta))
        assertTrue(ModManager.isUpgrade(newerRelease, installedBeta))
    }
}
