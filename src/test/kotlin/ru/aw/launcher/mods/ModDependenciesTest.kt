package ru.aw.launcher.mods

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.instance.InstanceStore
import ru.aw.launcher.instance.ManagedMod
import ru.aw.launcher.meta.LoaderKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModDependenciesTest {
    @TempDir lateinit var dir: Path
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val catalog = LinkedHashMap<String, Modrinth.Version>()
    private val payloads = HashMap<String, ByteArray>()
    private val requests = arrayListOf<String>()

    @AfterEach fun close() { server.stop(0) }

    private fun release(project: String, number: String = "1.0", depends: Map<String, String> = emptyMap(),
                        dependencies: List<Modrinth.Dependency> = emptyList(), game: String = "1.21.1"): Modrinth.Version {
        val id = "$project-$number"
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            val ranges = depends.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            zip.write("""{"id":"$project","name":"$project","version":"$number","depends":{$ranges}}""".toByteArray())
            zip.closeEntry()
        } }.toByteArray()
        payloads[id] = bytes
        server.createContext("/$id.jar") { exchange ->
            synchronized(requests) { requests += id }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        return Modrinth.Version(id, project, name = project, versionNumber = number, gameVersions = listOf(game),
            loaders = listOf("fabric"), dependencies = dependencies, files = listOf(Modrinth.VersionFile(
                "http://127.0.0.1:${server.address.port}/$id.jar", "$id.jar", true, bytes.size.toLong(),
                mapOf("sha1" to sha1Of(bytes.inputStream()))))).also { catalog[id] = it }
    }

    private fun installed(version: Modrinth.Version, enabled: Boolean = true): Path {
        val name = version.primaryFile!!.filename
        val file = Files.createDirectories(dir.resolve("mods")).resolve(name + if (enabled) "" else ".disabled")
        Files.write(file, payloads.getValue(version.id))
        InstanceStore.update(dir) { it.copy(catalogMods = it.catalogMods + ManagedMod(version.projectId,
            version.id, "mods/$name", sha1Of(file), version.projectId, version.versionNumber)) }
        return file
    }

    private suspend fun install(vararg roots: Modrinth.Version, localFiles: List<Path> = emptyList(),
                                disabledRoots: Set<String> = emptySet(),
                                progress: (ru.aw.launcher.net.DownloadProgress) -> Unit = {}) =
        ModManager.installResolved(dir, LoaderKind.FABRIC, "1.21.1", roots.toList(), localFiles = localFiles,
            disabledRoots = disabledRoots,
            onProgress = progress,
            versions = { id -> catalog.values.filter { it.projectId == id }.reversed() },
            version = { id -> catalog.getValue(id) },
            project = { id -> Modrinth.Project(id, title = id, loaders = listOf("fabric")) },
            identify = { hashes -> catalog.values.filter { it.primaryFile!!.sha1 in hashes }.associateBy { it.primaryFile!!.sha1!! } })

    @Test fun `required transitive dependencies are installed but optional ones are not`() = runBlocking {
        val leaf = release("leaf")
        val optional = release("optional")
        val dependency = release("dependency", depends = mapOf("leaf" to ">=1"), dependencies = listOf(Modrinth.Dependency(leaf.projectId)))
        val root = release("root", depends = mapOf("dependency" to "*"), dependencies = listOf(
            Modrinth.Dependency(dependency.projectId), Modrinth.Dependency(optional.projectId, type = "optional")))
        install(root)
        assertTrue(Files.exists(dir.resolve("mods/${root.primaryFile!!.filename}")))
        assertTrue(Files.exists(dir.resolve("mods/${dependency.primaryFile!!.filename}")))
        assertTrue(Files.exists(dir.resolve("mods/${leaf.primaryFile!!.filename}")))
        assertFalse(Files.exists(dir.resolve("mods/${optional.primaryFile!!.filename}")))
        assertEquals(3, InstanceStore.get(dir).catalogMods.size)
    }

    @Test fun `disabled matching dependency is enabled without another download`() = runBlocking {
        val library = release("library")
        val disabled = installed(library, false)
        val root = release("root", depends = mapOf("library" to "*"), dependencies = listOf(Modrinth.Dependency("library")))
        install(root)
        assertFalse(Files.exists(disabled))
        assertTrue(Files.exists(disabled.resolveSibling(library.primaryFile!!.filename)))
        assertFalse(requests.contains(library.id))
    }

    @Test fun `exact version only dependency wins over the newest release`() = runBlocking {
        val older = release("library", "1.0")
        val newer = release("library", "2.0")
        val root = release("root", depends = mapOf("library" to "1.0"),
            dependencies = listOf(Modrinth.Dependency(versionId = older.id)))
        install(root)
        assertTrue(Files.exists(dir.resolve("mods/${older.primaryFile!!.filename}")))
        assertFalse(Files.exists(dir.resolve("mods/${newer.primaryFile!!.filename}")))
    }

    @Test fun `jar version ranges select a suitable release rather than blindly using the latest`() = runBlocking {
        val older = release("library", "1.4")
        val newer = release("library", "2.0")
        val root = release("root", depends = mapOf("library" to ">=1 <2"), dependencies = listOf(Modrinth.Dependency("library")))
        install(root)
        assertTrue(Files.exists(dir.resolve("mods/${older.primaryFile!!.filename}")))
        assertFalse(Files.exists(dir.resolve("mods/${newer.primaryFile!!.filename}")))
    }

    @Test fun `an outdated disabled dependency is replaced when its version is insufficient`() = runBlocking {
        val old = release("library", "1.0")
        val disabled = installed(old, false)
        val fresh = release("library", "2.0")
        val root = release("root", depends = mapOf("library" to ">=2"), dependencies = listOf(Modrinth.Dependency("library")))
        install(root)
        assertFalse(Files.exists(disabled))
        assertTrue(Files.exists(dir.resolve("mods/${fresh.primaryFile!!.filename}")))
    }

    @Test fun `an unsatisfied dependency leaves previous mods and catalog metadata untouched`() = runBlocking {
        val old = release("old")
        val file = installed(old)
        val before = Files.readString(dir.resolve(InstanceStore.FILE_NAME))
        val root = release("root", dependencies = listOf(Modrinth.Dependency("missing")))
        assertThrows(IOException::class.java) { runBlocking { install(root) } }
        assertArrayEquals(payloads.getValue(old.id), Files.readAllBytes(file))
        assertEquals(before, Files.readString(dir.resolve(InstanceStore.FILE_NAME)))
        assertEquals(1L, Files.list(dir.resolve("mods")).use { it.count() })
    }

    @Test fun `dependency upgrade cannot break another installed mod`() = runBlocking {
        val old = release("library", "1.0")
        val libraryFile = installed(old)
        val consumer = release("consumer", depends = mapOf("library" to "<2"))
        installed(consumer)
        release("library", "2.0")
        val root = release("root", depends = mapOf("library" to ">=2"), dependencies = listOf(Modrinth.Dependency("library")))
        assertThrows(IOException::class.java) { runBlocking { install(root) } }
        assertTrue(Files.exists(libraryFile))
        assertFalse(Files.exists(dir.resolve("mods/${root.primaryFile!!.filename}")))
    }

    @Test fun `updating a mod installs its newly required dependency and removes the old jar`() = runBlocking {
        val previous = release("root", "1.0")
        val oldFile = installed(previous)
        val library = release("library")
        val fresh = release("root", "2.0", depends = mapOf("library" to "*"), dependencies = listOf(Modrinth.Dependency("library")))
        install(fresh)
        assertFalse(Files.exists(oldFile))
        assertTrue(Files.exists(dir.resolve("mods/${fresh.primaryFile!!.filename}")))
        assertTrue(Files.exists(dir.resolve("mods/${library.primaryFile!!.filename}")))
    }

    @Test fun `cancellation during staging never installs a partial dependency graph`() = runBlocking {
        val existing = release("existing")
        val file = installed(existing)
        val root = release("root")
        assertThrows(CancellationException::class.java) { runBlocking { install(root, progress = { throw CancellationException("cancel") }) } }
        assertTrue(Files.exists(file))
        assertFalse(Files.exists(dir.resolve("mods/${root.primaryFile!!.filename}")))
    }

    @Test fun `local Fabric jars with absent dependencies are rejected before copying`() = runBlocking {
        val source = Files.createDirectories(dir.resolve("incoming")).resolve("manual.jar")
        val manual = release("manual", depends = mapOf("missing-library" to ">=1"))
        Files.write(source, payloads.getValue(manual.id))
        assertThrows(IOException::class.java) { runBlocking { install(localFiles = listOf(source)) } }
        assertFalse(Files.exists(dir.resolve("mods/manual.jar")))
    }

    @Test fun `dependency graphs deeper than four levels and cycles are resolved completely`() = runBlocking {
        val versions = (0..6).map { n -> release("mod$n", dependencies = listOf(Modrinth.Dependency("mod${if (n == 6) 0 else n + 1}"))) }
        install(versions.first())
        assertEquals(7, InstanceStore.get(dir).catalogMods.size)
    }

    @Test fun `conflicting exact pins are rejected without installing either release`() = runBlocking {
        val first = release("library", "1.0")
        val second = release("library", "2.0")
        val a = release("first", dependencies = listOf(Modrinth.Dependency("library", first.id)))
        val b = release("second", dependencies = listOf(Modrinth.Dependency("library", second.id)))
        assertThrows(IOException::class.java) { runBlocking { install(a, b) } }
        assertFalse(Files.exists(dir.resolve("mods")))
    }

    @Test fun `a later pin replaces an earlier unpinned choice and drops stale transitive constraints`() = runBlocking {
        val leaf1 = release("leaf", "1.0")
        val leaf2 = release("leaf", "2.0")
        val old = release("library", "1.0", dependencies = listOf(Modrinth.Dependency("leaf", leaf1.id)))
        release("library", "2.0", dependencies = listOf(Modrinth.Dependency("leaf", leaf2.id)))
        val pinning = release("pinning", dependencies = listOf(Modrinth.Dependency("library", old.id)))
        val root = release("root", dependencies = listOf(Modrinth.Dependency("library"), Modrinth.Dependency(pinning.projectId)))
        install(root)
        assertTrue(Files.exists(dir.resolve("mods/${old.primaryFile!!.filename}")))
        assertTrue(Files.exists(dir.resolve("mods/${leaf1.primaryFile!!.filename}")))
        assertFalse(Files.exists(dir.resolve("mods/${leaf2.primaryFile!!.filename}")))
    }

    @Test fun `catalog incompatibilities stop installation before modifying the build`() = runBlocking {
        val incompatible = release("incompatible")
        val file = installed(incompatible)
        val root = release("root", dependencies = listOf(Modrinth.Dependency(incompatible.projectId, type = "incompatible")))
        assertThrows(IOException::class.java) { runBlocking { install(root) } }
        assertTrue(Files.exists(file))
        assertFalse(Files.exists(dir.resolve("mods/${root.primaryFile!!.filename}")))
    }

    @Test fun `local files cannot install duplicate mod ids with different filenames`() = runBlocking {
        val release = release("manual")
        val incoming = Files.createDirectories(dir.resolve("incoming"))
        val first = Files.write(incoming.resolve("one.jar"), payloads.getValue(release.id))
        val second = Files.write(incoming.resolve("two.jar"), payloads.getValue(release.id))
        assertThrows(IOException::class.java) { runBlocking { install(localFiles = listOf(first, second)) } }
        assertFalse(Files.exists(dir.resolve("mods/one.jar")))
    }

    @Test fun `a local unrecognized mod enables a tracked dependency and resolves its own requirements`() = runBlocking {
        val leaf = release("leaf")
        val dependency = release("library", depends = mapOf("leaf" to "*"), dependencies = listOf(Modrinth.Dependency("leaf")))
        val disabled = installed(dependency, false)
        val manual = release("manual", depends = mapOf("library" to "*"))
        val file = Files.write(Files.createDirectories(dir.resolve("incoming")).resolve("manual.jar"), payloads.getValue(manual.id))
        install(localFiles = listOf(file))
        assertFalse(Files.exists(disabled))
        assertTrue(Files.exists(dir.resolve("mods/${dependency.primaryFile!!.filename}")))
        assertTrue(Files.exists(dir.resolve("mods/${leaf.primaryFile!!.filename}")))
        assertTrue(Files.exists(dir.resolve("mods/manual.jar")))
    }

    @Test fun `unrecognized local Fabric jars cannot bypass their Minecraft requirement`() = runBlocking {
        val wrong = release("manual", depends = mapOf("minecraft" to "1.20.1"))
        val file = Files.write(Files.createDirectories(dir.resolve("incoming")).resolve("manual.jar"), payloads.getValue(wrong.id))
        assertThrows(IOException::class.java) { runBlocking { install(localFiles = listOf(file)) } }
        assertFalse(Files.exists(dir.resolve("mods/manual.jar")))
    }

    @Test fun `updating a disabled mod validates dependencies without enabling the mod`() = runBlocking {
        val old = release("root", "1.0")
        val original = installed(old, false)
        val library = release("library", "1.4")
        release("library", "2.0")
        val fresh = release("root", "2.0", depends = mapOf("library" to ">=1 <2"), dependencies = listOf(Modrinth.Dependency("library")))
        install(fresh, disabledRoots = setOf("root"))
        assertFalse(Files.exists(original))
        assertTrue(Files.exists(dir.resolve("mods/${fresh.primaryFile!!.filename}.disabled")))
        assertTrue(Files.exists(dir.resolve("mods/${library.primaryFile!!.filename}.disabled")))
        assertFalse(Files.exists(dir.resolve("mods/${fresh.primaryFile!!.filename}")))
    }
}
