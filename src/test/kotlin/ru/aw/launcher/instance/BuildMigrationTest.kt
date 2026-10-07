package ru.aw.launcher.instance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.ContentKind
import ru.aw.launcher.mods.InstalledItem
import ru.aw.launcher.mods.Modrinth
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class BuildMigrationTest {
    @Test
    fun `compatible disabled mods get new files without enabling them`() = runBlocking {
        val item = mod("old.jar.disabled", enabled = false)
        val payload = "compatible new mod".toByteArray()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/new.jar") { response ->
            response.sendResponseHeaders(200, payload.size.toLong())
            response.responseBody.use { it.write(payload) }
            response.close()
        }
        server.start()
        try {
            val version = Modrinth.Version("new-release", "project", versionNumber = "new", gameVersions = listOf("1.21.1"),
                loaders = listOf("fabric"), files = listOf(Modrinth.VersionFile("http://127.0.0.1:${server.address.port}/new.jar", "new.jar",
                    size = payload.size.toLong(), hashes = mapOf("sha1" to sha1Of(payload.inputStream())))))
            val ready = BuildMigration.plan(root, "1.20.1", "1.21.1", LoaderKind.FABRIC,
                listOf(item), mapOf(item.sha1 to version), BuildMigration.fingerprint(root))
            BuildMigration.apply(ready, emptySet(), {}, {}, prepareGame = {}, commitVersion = {})
            assertFalse(Files.exists(root.resolve("mods/new.jar")))
            assertFalse(Files.exists(item.file))
            assertArrayEquals(payload, Files.readAllBytes(root.resolve("mods/new.jar.disabled")))
        } finally { server.stop(0) }
    }

    @Test
    fun `managed packs retain their folder and stop automatic official updates after migration`() = runBlocking {
        val settings = ru.aw.launcher.core.Settings.current
        try {
            ru.aw.launcher.core.Settings.update { it.copy(customGameDir = root.toString()) }
            val pack = ru.aw.launcher.packs.Modpack("profile", "Test pack", "release", "1.20.1", LoaderKind.FABRIC,
                projectId = "project", versionId = "official-release")
            val dir = ru.aw.launcher.packs.Modpacks.dirOf(pack)
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(ru.aw.launcher.packs.Modpacks.MANIFEST),
                ru.aw.launcher.core.Json.encodeToString(ru.aw.launcher.packs.Modpack.serializer(), pack))
            val updated = ru.aw.launcher.packs.Modpacks.changeGameVersion(pack, "1.21.1")
            assertEquals(dir, ru.aw.launcher.packs.Modpacks.dirOf(updated))
            assertEquals(pack.id, updated.id)
            assertEquals(pack.projectId, updated.projectId)
            assertEquals("1.21.1", updated.gameVersion)
            assertTrue(updated.customGameVersion)
            assertNull(ru.aw.launcher.packs.Modpacks.update(updated))
        } finally { ru.aw.launcher.core.Settings.update { settings } }
    }

    @TempDir lateinit var root: Path
    private val bytes = byteArrayOf(1, 2, 3, 4)

    private fun mod(name: String = "old.jar", enabled: Boolean = true): InstalledItem {
        Files.createDirectories(root.resolve("mods"))
        val file = root.resolve("mods/$name")
        Files.write(file, bytes)
        return InstalledItem(file, ContentKind.MOD, enabled, sha1Of(file), "project", "Test mod", "old", null, null)
    }

    private fun plan(item: InstalledItem) = BuildMigration.plan(root, "1.20.1", "1.21.1", LoaderKind.FABRIC,
        listOf(item), emptyMap(), BuildMigration.fingerprint(root))

    @Test
    fun `target candidates must match Minecraft and the existing loader`() {
        val item = mod()
        val good = Modrinth.Version("new", "project", gameVersions = listOf("1.21.1"), loaders = listOf("fabric"),
            files = listOf(Modrinth.VersionFile("https://cdn.modrinth.com/new.jar", "new.jar")))
        fun choice(version: Modrinth.Version) = BuildMigration.plan(root, "1.20.1", "1.21.1", LoaderKind.FABRIC,
            listOf(item), mapOf(item.sha1 to version), emptyMap()).mods.single().replacement
        assertEquals(good, choice(good))
        assertNull(choice(good.copy(loaders = listOf("forge"))))
        assertNull(choice(good.copy(gameVersions = listOf("1.20.1"))))
        assertNull(choice(good.copy(files = listOf(Modrinth.VersionFile("https://example.org/file", "../bad.jar")))))
    }

    @Test
    fun `missing mods are disabled in the same directory and old files are backed up`() = runBlocking {
        val item = mod()
        Files.createDirectories(root.resolve("saves/world"))
        Files.writeString(root.resolve("saves/world/level.dat"), "world unchanged")
        Files.writeString(root.resolve("options.txt"), "settings unchanged")
        var committed = false
        val backup = BuildMigration.apply(plan(item), emptySet(), {}, {}, prepareGame = {}, commitVersion = { committed = true })
        assertTrue(committed)
        assertFalse(Files.exists(item.file))
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("mods/old.jar.disabled")))
        assertArrayEquals(bytes, Files.readAllBytes(backup.resolve("mods/old.jar")))
        assertEquals("world unchanged", Files.readString(root.resolve("saves/world/level.dat")))
        assertEquals("settings unchanged", Files.readString(root.resolve("options.txt")))
    }

    @Test
    fun `users can keep a missing mod enabled and existing disabled mods stay disabled`() = runBlocking {
        val enabled = mod()
        val disabled = mod("unused.jar.disabled", enabled = false)
        val ready = BuildMigration.plan(root, "1.20.1", "1.21.1", LoaderKind.FABRIC, listOf(enabled, disabled),
            emptyMap(), BuildMigration.fingerprint(root))
        BuildMigration.apply(ready, setOf(enabled.fileName), {}, {}, prepareGame = {}, commitVersion = {})
        assertArrayEquals(bytes, Files.readAllBytes(enabled.file))
        assertArrayEquals(bytes, Files.readAllBytes(disabled.file))
        assertFalse(Files.exists(root.resolve("mods/unused.jar")))
    }

    @Test
    fun `metadata failure rolls back mods and profile settings`() = runBlocking {
        val item = mod()
        InstanceStore.update(root) { it.copy(javaPath = "old-java", favorite = true) }
        val oldSettings = Files.readAllBytes(root.resolve(InstanceStore.FILE_NAME))
        assertThrows<IOException> {
            BuildMigration.apply(plan(item), emptySet(), {}, {}, prepareGame = {}, commitVersion = { throw IOException("Cannot save metadata") })
        }
        assertArrayEquals(bytes, Files.readAllBytes(item.file))
        assertFalse(Files.exists(root.resolve("mods/old.jar.disabled")))
        assertArrayEquals(oldSettings, Files.readAllBytes(root.resolve(InstanceStore.FILE_NAME)))
        assertEquals("old-java", InstanceStore.get(root).javaPath)
    }

    @Test
    fun `cancelled preparation leaves original files untouched`() = runBlocking {
        val item = mod()
        assertThrows<CancellationException> {
            BuildMigration.apply(plan(item), emptySet(), {}, {}, prepareGame = { throw CancellationException("Cancelled") },
                commitVersion = { fail("Metadata must not change") })
        }
        assertArrayEquals(bytes, Files.readAllBytes(item.file))
        assertFalse(Files.exists(root.resolve("mods/old.jar.disabled")))
    }

    @Test
    fun `changes made after the compatibility check require a new plan`() = runBlocking {
        val item = mod()
        val ready = plan(item)
        Files.write(item.file, byteArrayOf(9))
        assertThrows<IOException> {
            BuildMigration.apply(ready, emptySet(), {}, {}, prepareGame = {}, commitVersion = {})
        }
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(item.file))
    }

    @Test
    fun `local build version changes retain identity and play history`() {
        val previous = LocalBuilds.buildsFile
        try {
            LocalBuilds.buildsFile = root.resolve("builds.json")
            val old = LocalBuilds.create("My build", "1.20.1", LoaderKind.FABRIC, "0.15.0")
            val updated = LocalBuilds.changeGameVersion(old.id, old.versionId, "1.21.1")
            assertEquals(old.id, updated.id)
            assertEquals(old.name, updated.name)
            assertEquals(LocalBuilds.dirOf(old), LocalBuilds.dirOf(updated))
            assertNull(updated.loaderVersion)
            assertEquals("1.21.1", LocalBuilds.list().single().versionId)
            assertThrows<IOException> { LocalBuilds.changeGameVersion(old.id, "1.20.1", "1.21.2") }
        } finally { LocalBuilds.buildsFile = previous }
    }
}
