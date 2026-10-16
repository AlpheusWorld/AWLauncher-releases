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
    @Test fun `preview lists local files without hashing or catalog requests`(@TempDir game: Path) = kotlinx.coroutines.runBlocking {
        val jar = ModManager.modsDir(game).createDirectories().resolve("local.jar.disabled").apply { writeText("local mod") }
        val item = ModManager.scan(game, LoaderKind.FABRIC, "26.3", withUpdates = true, previewOnly = true).single()
        assertEquals(jar, item.file)
        assertFalse(item.enabled)
        assertEquals("", item.sha1)
        assertEquals(null, item.projectId)
        assertEquals(null, ru.aw.launcher.core.VerifyCache.cachedSha1(jar))
    }

    @Test fun `preview reuses verified metadata but rejects changed files`(@TempDir game: Path) = kotlinx.coroutines.runBlocking {
        val jar = ModManager.modsDir(game).createDirectories().resolve("known.jar").apply { writeText("original") }
        val sum = ModManager.scan(game, LoaderKind.FABRIC, "26.3", identifyRemote = false).single().sha1
        assertEquals(ru.aw.launcher.core.sha1Of(jar), sum)
        assertEquals(sum, ru.aw.launcher.core.VerifyCache.cachedSha1(jar))
        ru.aw.launcher.instance.InstanceStore.update(game) { it.copy(catalogMods = listOf(
            ru.aw.launcher.instance.ManagedMod("project", "release", "mods/known.jar", sum, "Known mod", "2.0")
        )) }
        val cached = ModManager.scan(game, LoaderKind.FABRIC, "26.3", previewOnly = true).single()
        assertEquals("Known mod", cached.title)
        assertEquals("project", cached.projectId)
        assertEquals("release", cached.versionId)
        assertEquals(sum, cached.sha1)

        val modified = java.nio.file.Files.getLastModifiedTime(jar).toMillis()
        jar.writeText("modified")
        java.nio.file.Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(modified + 2_000))
        val changed = ModManager.scan(game, LoaderKind.FABRIC, "26.3", previewOnly = true).single()
        assertEquals("", changed.sha1)
        assertEquals(null, changed.projectId)
        assertEquals(null, ru.aw.launcher.core.VerifyCache.cachedSha1(jar))
        val rescanned = ModManager.scan(game, LoaderKind.FABRIC, "26.3", identifyRemote = false).single()
        assertEquals(ru.aw.launcher.core.sha1Of(jar), rescanned.sha1)
        assertFalse(sum == rescanned.sha1)
    }

    private fun datapack(path: Path, prefix: String = ""): Path {
        java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(path)).use { zip ->
            for ((name, text) in mapOf("pack.mcmeta" to "{\"pack\":{\"pack_format\":61,\"description\":\"Test\"}}",
                "data/test/function/hello.mcfunction" to "say hello")) {
                zip.putNextEntry(java.util.zip.ZipEntry(prefix + name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return path
    }

    @Test fun `wrapped datapacks are normalized and unsafe archives rejected`(@TempDir game: Path) {
        val valid = datapack(game.resolve("wrapped.zip"), "Test/")
        ModManager.normalizeDataPack(valid)
        java.util.zip.ZipFile(valid.toFile()).use { zip ->
            assertTrue(zip.getEntry("pack.mcmeta") != null)
            assertTrue(zip.getEntry("data/test/function/hello.mcfunction") != null)
        }
        val bad = datapack(game.resolve("unsafe.zip"), "../")
        org.junit.jupiter.api.assertThrows<java.io.IOException> { ModManager.normalizeDataPack(bad) }
        assertFalse(game.resolve("unsafe.zip.normalized").exists())
        val absent = datapack(game.resolve("no-metadata.zip"), "one/two/")
        org.junit.jupiter.api.assertThrows<java.io.IOException> { ModManager.normalizeDataPack(absent) }
    }

    @Test fun `datapacks install only in the selected world and retain metadata when disabled`(@TempDir game: Path) = kotlinx.coroutines.runBlocking {
        val first = game.resolve("saves/First").createDirectories().also { it.resolve("level.dat").writeText("world") }
        val second = game.resolve("saves/Second").createDirectories().also { it.resolve("level.dat").writeText("world") }
        game.resolve("saves/NotAWorld").createDirectories()
        assertEquals(listOf(first, second), ModManager.dataPackWorlds(game))
        val payload = java.nio.file.Files.readAllBytes(datapack(game.resolve("source.zip"), "Example/"))
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pack.zip") { response ->
            response.sendResponseHeaders(200, payload.size.toLong()); response.responseBody.use { it.write(payload) }; response.close()
        }
        server.start()
        try {
            val version = Modrinth.Version("new", "test-datapack", gameVersions = listOf("26.3"), loaders = listOf("datapack"),
                files = listOf(Modrinth.VersionFile("http://127.0.0.1:${server.address.port}/pack.zip", "pack.zip",
                    size = payload.size.toLong(), hashes = mapOf("sha1" to ru.aw.launcher.core.sha1Of(payload.inputStream())))))
            org.junit.jupiter.api.assertThrows<java.io.IOException> {
                ModManager.installDataPack(game, game.resolve("outside"), "26.3", version.projectId, "Test", version)
            }
            ModManager.installDataPack(game, first, "26.3", version.projectId, "Test", version)
            assertFalse(second.resolve("datapacks").exists())
            assertFalse(game.resolve("datapacks").exists())
            val item = ModManager.scan(game, LoaderKind.VANILLA, "26.3", ContentKind.DATA_PACK, identifyRemote = false).single()
            assertEquals("First", item.worldName); assertEquals("test-datapack", item.projectId)
            val disabled = ModManager.setEnabled(item, false)
            assertTrue(disabled.fileName.endsWith(".disabled")); assertFalse(disabled.enabled)
            assertEquals(item.projectId, ModManager.scan(game, LoaderKind.VANILLA, "26.3", ContentKind.DATA_PACK,
                identifyRemote = false).single().projectId)
            ModManager.setEnabled(disabled, true)
            assertTrue(first.resolve("datapacks/pack.zip").exists())
            val folder = first.resolve("datapacks/local").createDirectories()
            folder.resolve("pack.mcmeta").writeText("{\"pack\":{}}")
            val local = ModManager.scan(game, LoaderKind.VANILLA, "26.3", ContentKind.DATA_PACK, identifyRemote = false)
                .first { it.file == folder }
            val moved = ModManager.setEnabled(local, false)
            assertFalse(folder.exists())
            assertEquals("aw-disabled-datapacks", moved.file.parent.fileName.toString())
            assertFalse(ModManager.scan(game, LoaderKind.VANILLA, "26.3", ContentKind.DATA_PACK, identifyRemote = false)
                .first { it.file == moved.file }.enabled)
            ModManager.setEnabled(moved, true)
            assertTrue(folder.resolve("pack.mcmeta").exists())
        } finally { server.stop(0) }
    }
    @Test
    fun `universal Fabric release does not require NeoForge only KotlinLangForge`() {
        assertFalse(ModManager.dependencyApplies(listOf("forge", "neoforge"), listOf("fabric")))
        assertTrue(ModManager.dependencyApplies(listOf("forge", "neoforge"), listOf("neoforge")))
        assertTrue(ModManager.dependencyApplies(listOf("fabric", "quilt"), listOf("fabric")))
        assertTrue(ModManager.dependencyApplies(emptyList(), listOf("fabric")))
    }

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
