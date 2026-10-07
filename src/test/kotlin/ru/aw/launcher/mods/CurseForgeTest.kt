package ru.aw.launcher.mods

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.instance.InstanceOptions
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.instance.ManagedMod
import ru.aw.launcher.instance.LocalBuilds
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.packs.Modpacks
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CurseForgeTest {
    @TempDir lateinit var temp: Path

    private val file = CurseForge.File(id = 102, modId = 51, gameId = 432, displayName = "Example 2.0", fileName = "example.jar",
        downloadUrl = "https://edge.forgecdn.net/files/0/102/example.jar", fileDate = "2026-09-20T12:00:00Z",
        gameVersions = listOf("1.21.1", "NeoForge", "Java 21"), hashes = listOf(CurseForge.Hash("a".repeat(40), 1)),
        dependencies = listOf(CurseForge.Dependency(61, 3), CurseForge.Dependency(62, 2), CurseForge.Dependency(63, 5)))

    @Test fun `versions keep provider identity loader tags hashes and required dependencies`() {
        val version = CurseForge.mapFile(file, "mod")
        assertEquals("cf:51:102", version.id)
        assertEquals("cf:51", version.projectId)
        assertEquals(listOf("1.21.1"), version.gameVersions)
        assertEquals(listOf("neoforge"), version.loaders)
        assertEquals("a".repeat(40), version.primaryFile?.sha1)
        assertEquals(listOf("cf:61"), version.dependencies.map { it.projectId })
        ModManager.requireCompatibleVersion(version, "cf:51", "1.21.1", listOf("neoforge"))
        assertThrows<IllegalArgumentException> { ModManager.requireCompatibleVersion(version, "cf:51", "1.21.1", listOf("fabric")) }
    }

    @Test fun `restricted and missing URLs cannot become download tasks`() {
        assertNull(CurseForge.mapFile(file, "mod", distributed = false).primaryFile)
        assertNull(CurseForge.mapFile(file.copy(downloadUrl = null), "mod").primaryFile)
        assertNull(CurseForge.mapFile(file.copy(isAvailable = false), "mod").primaryFile)
        assertNull(CurseForge.mapFile(file.copy(downloadUrl = "https://edge.forgecdn.net.evil.example/a.jar"), "mod").primaryFile)
        assertNull(CurseForge.mapFile(file.copy(downloadUrl = "http://edge.forgecdn.net/a.jar"), "mod").primaryFile)
        assertFalse(Modpacks.isAllowedUrl(file.downloadUrl!!)) // Modrinth archives retain their existing host policy.
    }

    @Test fun `resource packs shaders and archive extensions use their own compatibility`() {
        assertEquals(listOf("minecraft"), CurseForge.mapFile(file, "resourcepack").loaders)
        assertEquals(listOf("iris", "optifine"), CurseForge.mapFile(file, "shader").loaders)
        assertTrue(ContentCatalog.isPackFile("cf:51", "pack.zip"))
        assertFalse(ContentCatalog.isPackFile("cf:51", "pack.mrpack"))
        assertTrue(ContentCatalog.isPackFile("ABCDEFGH", "pack.mrpack"))
        assertEquals("https://www.curseforge.com/projects/51", ContentCatalog.page("cf:51", "mod", ""))
    }

    @Test fun `old instance options load without CurseForge metadata`() {
        val options = Json.decodeFromString<InstanceOptions>("""{"fpsBoost":true,"boostMods":[],"blockedUpdates":["old"]}""")
        assertEquals(listOf("old"), options.blockedUpdates)
        assertTrue(options.catalogMods.isEmpty())
    }

    @Test fun `tracked CurseForge mods retain their identity offline and when disabled`() {
        val dir = temp.resolve("instance").also { it.resolve("mods").createDirectories() }
        val jar = dir.resolve("mods/example.jar").also { it.writeText("local content") }
        InstanceStore.update(dir) { it.copy(catalogMods = listOf(
            ManagedMod("cf:51", "cf:51:102", "mods/example.jar", sha1Of(jar), "Example", "2.0", "2026-09-20T12:00:00Z")
        )) }
        val first = runBlocking { ModManager.scan(dir, LoaderKind.NEOFORGE, "1.21.1") }.single()
        assertEquals("cf:51", first.projectId)
        assertEquals("cf:51:102", first.versionId)
        assertEquals("Example", first.title)
        ModManager.setEnabled(first, false)
        val disabled = runBlocking { ModManager.scan(dir, LoaderKind.NEOFORGE, "1.21.1") }.single()
        assertFalse(disabled.enabled)
        assertEquals(first.projectId, disabled.projectId)
        assertEquals("2.0", disabled.versionNumber)
        ModManager.remove(disabled)
        assertTrue(runBlocking { ModManager.scan(dir, LoaderKind.NEOFORGE, "1.21.1") }.isEmpty())
    }

    private fun archive(overrides: String = "overrides", loader: String = "neoforge-21.1.77"): Path {
        val path = temp.resolve("pack.zip")
        ZipOutputStream(path.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"manifestType":"minecraftModpack","manifestVersion":1,"name":"CF test","version":"2","minecraft":{"version":"1.21.1","modLoaders":[{"id":"$loader","primary":true}]},"files":[],"overrides":"$overrides"}""",
                "overrides/config/example.txt" to "hello", "overrides/../escape.txt" to "bad",
                "overrides/aw-instance.json" to "bad").forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
            }
        }
        return path
    }

    @Test fun `CurseForge zip imports an exact loader and safe overrides without an API key`() {
        val original = LocalBuilds.buildsFile
        LocalBuilds.buildsFile = temp.resolve("builds.json")
        try {
            val build = runBlocking { Modpacks.importMrpack(archive(), "CF import") }
            val dir = LocalBuilds.dirOf(build)
            assertEquals(LoaderKind.NEOFORGE, build.loader)
            assertEquals("21.1.77", build.loaderVersion)
            assertEquals("1.21.1", build.versionId)
            assertEquals("hello", dir.resolve("config/example.txt").readText())
            assertFalse(dir.parent.resolve("escape.txt").exists())
            assertFalse(dir.resolve("aw-instance.json").exists())
        } finally {
            LocalBuilds.list().forEach { Storage.deleteTree(LocalBuilds.dirOf(it)) }
            LocalBuilds.buildsFile = original
        }
    }

    @Test fun `unsafe overrides and unsupported loaders are rejected before profile creation`() {
        assertThrows<java.io.IOException> { runBlocking { Modpacks.importMrpack(archive(overrides = "../outside"), "bad") } }
        ZipFile(archive(loader = "unknown-1.0").toFile()).use { zip ->
            assertThrows<java.io.IOException> { CurseForge.packIndex(zip) }
        }
    }
}
