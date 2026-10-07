package ru.aw.launcher.packs

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.Storage
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.instance.LocalBuilds
import ru.aw.launcher.meta.LoaderKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ModpacksTest {

    @TempDir
    lateinit var temp: Path

    private fun file(path: String, sha1: String? = "a".repeat(40), client: String = "required", url: String = "https://cdn.modrinth.com/x.jar") =
        PackFile(path, listOfNotNull(sha1?.let { "sha1" to it }).toMap(), mapOf("client" to client), listOf(url), 10)

    @Test
    fun `paths inside a pack cannot leave its folder`() {
        assertEquals("mods/sodium.jar", Modpacks.safePath("mods/sodium.jar"))
        assertEquals("config/iris/a.json", Modpacks.safePath("config\\iris\\a.json"))
        listOf("../evil.jar", "/etc/passwd", "C:/Windows/x", "mods/../../x", "mods//x", "./x", "", Modpacks.MANIFEST)
            .forEach { assertNull(Modpacks.safePath(it), it) }
    }

    @Test
    fun `files are fetched only from trusted hosts over https`() {
        assertTrue(Modpacks.isAllowedUrl("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"))
        assertTrue(Modpacks.isAllowedUrl("https://github.com/owner/repo/releases/download/v1/a.jar"))
        assertFalse(Modpacks.isAllowedUrl("http://cdn.modrinth.com/a.jar"))
        assertFalse(Modpacks.isAllowedUrl("https://evil.example/a.jar"))
        assertFalse(Modpacks.isAllowedUrl("not a url"))
    }

    @Test
    fun `the loader and its exact build come from the dependencies`() {
        assertEquals(LoaderKind.FABRIC to "0.19.5", Modpacks.loaderOf(mapOf("minecraft" to "26.2", "fabric-loader" to "0.19.5")))
        assertEquals(LoaderKind.NEOFORGE to "21.1.77", Modpacks.loaderOf(mapOf("minecraft" to "1.21.1", "neoforge" to "21.1.77")))
        assertEquals(LoaderKind.FORGE to "47.2.0", Modpacks.loaderOf(mapOf("minecraft" to "1.20.1", "forge" to "47.2.0")))
        assertEquals(LoaderKind.QUILT to "0.26.0", Modpacks.loaderOf(mapOf("quilt-loader" to "0.26.0")))
        assertEquals(LoaderKind.VANILLA to null, Modpacks.loaderOf(mapOf("minecraft" to "1.21.1")))
    }

    @Test
    fun `server-only files are skipped, broken entries stop the install`() {
        val index = PackIndex(
            formatVersion = 1,
            game = "minecraft",
            files = listOf(file("mods/a.jar"), file("mods/server.jar", client = "unsupported"), file("mods/opt.jar", client = "optional")),
        )
        assertEquals(listOf("mods/a.jar", "mods/opt.jar"), Modpacks.downloadsOf(index, Modpacks::isAllowedUrl).map { it.path })

        assertThrows<IOException> { Modpacks.downloadsOf(index.copy(files = listOf(file("mods/b.jar", sha1 = null))), Modpacks::isAllowedUrl) }
        assertThrows<IOException> { Modpacks.downloadsOf(index.copy(files = listOf(file("../b.jar"))), Modpacks::isAllowedUrl) }
        assertThrows<IOException> {
            Modpacks.downloadsOf(index.copy(files = listOf(file("mods/b.jar", url = "https://evil.example/b.jar"))), Modpacks::isAllowedUrl)
        }
    }

    @Test
    fun `a local mrpack becomes an isolated profile with its pinned loader`() {
        val originalBuildsFile = LocalBuilds.buildsFile
        LocalBuilds.buildsFile = temp.resolve("local-builds.json")
        val archive = temp.resolve("Better Pack.mrpack")
        val index = """{"formatVersion":1,"game":"minecraft","versionId":"1.0.0","name":"Better Pack","files":[],"dependencies":{"minecraft":"1.21.8","fabric-loader":"0.16.14"}}"""
        Files.newOutputStream(archive).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("modrinth.index.json"))
                zip.write(index.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("overrides/options.txt"))
                zip.write("lang:ru_ru".toByteArray())
                zip.closeEntry()
            }
        }

        try {
            val build = runBlocking { Modpacks.importMrpack(archive, "") }
            val dir = LocalBuilds.dirOf(build)

            assertEquals("Better Pack", build.name)
            assertEquals("1.21.8", build.versionId)
            assertEquals(LoaderKind.FABRIC, build.loader)
            assertEquals("0.16.14", build.loaderVersion)
            assertEquals("lang:ru_ru", dir.resolve("options.txt").readText())
        } finally {
            LocalBuilds.list().forEach { Storage.deleteTree(LocalBuilds.dirOf(it)) }
            LocalBuilds.buildsFile = originalBuildsFile
        }
    }

    @Test
    fun `a taken folder name gets the project id appended`() {
        val dir = Settings.packsDir().resolve("taken-pack").createDirectories()
        dir.resolve(Modpacks.MANIFEST).writeAtomically(
            """{"id":"taken-pack","title":"Taken","version":"1","gameVersion":"26.2","loader":"FABRIC","projectId":"AAAAAAAA","versionId":"v1"}""",
        )
        assertEquals("taken-pack", Modpacks.folderFor("Taken Pack", "AAAAAAAA"))
        assertEquals("taken-pack-bbbbbb", Modpacks.folderFor("taken-pack", "BBBBBBBB"))
        assertEquals("fresh_pack", Modpacks.folderFor("Fresh_Pack!", "CCCCCCCC"))
    }

    @Test
    fun `a pack installs, then updates without touching the player's settings`() {
        val jarA = "mod a".toByteArray()
        val jarB = "mod b".toByteArray()
        var archive = ByteArray(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun serve(path: String, body: () -> ByteArray) = server.createContext(path) { exchange ->
            val bytes = body()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        serve("/pack.mrpack") { archive }
        serve("/a.jar") { jarA }
        serve("/b.jar") { jarB }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"

        fun pack(version: String, files: Map<String, ByteArray>): ByteArray {
            val entries = files.entries.joinToString(",") { (path, bytes) ->
                """{"path":"$path","hashes":{"sha1":"${sha1Of(bytes.inputStream())}"},"env":{"client":"required"},""" +
                    """"downloads":["$base/${path.substringAfter('/')}"],"fileSize":${bytes.size}}"""
            }
            val index = """{"formatVersion":1,"game":"minecraft","versionId":"$version","name":"Test",""" +
                """"files":[$entries,{"path":"mods/server.jar","hashes":{"sha1":"00"},"env":{"client":"unsupported"},"downloads":["$base/none"]}],""" +
                """"dependencies":{"minecraft":"26.2","fabric-loader":"0.19.5"}}"""
            return ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    mapOf(
                        "modrinth.index.json" to index,
                        "overrides/options.txt" to "lang:ru_ru",
                        "overrides/config/x.json" to "{}",
                        "client-overrides/config/x.json" to """{"client":true}""",
                        "overrides/../escape.txt" to "no",
                    ).forEach { (name, text) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
        }

        try {
            val source = PackSource("test-pack", "Test", projectId = "PROJECT1", versionId = "v1", version = "1.0.0", url = "$base/pack.mrpack")
            archive = pack("1.0.0", mapOf("mods/a.jar" to jarA))
            val first = runBlocking { Modpacks.install(source, allowed = { true }) }
            val dir = Modpacks.dirOf(first)

            assertEquals(LoaderKind.FABRIC, first.loader)
            assertEquals("0.19.5", first.loaderVersion)
            assertEquals("26.2", first.gameVersion)
            assertEquals("1.0.0", first.version)
            assertEquals(listOf("mods/a.jar"), first.files)
            assertEquals("PROJECT1" to "v1", first.projectId to first.versionId)
            assertTrue(dir.resolve("mods/a.jar").exists())
            assertFalse(dir.resolve("mods/server.jar").exists())
            assertFalse(dir.parent.resolve("escape.txt").exists())
            assertEquals("""{"client":true}""", dir.resolve("config/x.json").readText())
            assertEquals(first, Modpacks.read(dir))
            assertEquals(listOf(first), Modpacks.list().filter { it.id == "test-pack" })

            dir.resolve("options.txt").writeText("lang:en_us")
            archive = pack("2.0.0", mapOf("mods/b.jar" to jarB))
            val second = runBlocking { Modpacks.install(source.copy(versionId = "v2", version = "2.0.0"), allowed = { true }) }

            assertEquals("2.0.0", second.version)
            assertFalse(dir.resolve("mods/a.jar").exists())
            assertTrue(dir.resolve("mods/b.jar").exists())
            assertEquals("lang:en_us", dir.resolve("options.txt").readText())
        } finally {
            server.stop(0)
        }
    }
}
