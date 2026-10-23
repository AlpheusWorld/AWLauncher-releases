package ru.aw.launcher.instance

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.meta.LoaderKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class BuildHistoryTest {
    @TempDir lateinit var root: Path
    private val loader = LoaderKind.FABRIC

    private fun write(name: String, value: String): Path {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        return Files.writeString(file, value)
    }

    private fun tracked(name: String, version: String) {
        val file = write("mods/$name", "mod $version")
        InstanceStore.update(root) { it.copy(catalogMods = listOf(ManagedMod("project", version,
            "mods/$name", sha1Of(file), "Example", version))) }
    }

    @Test fun `update records versions and restores mods settings scripts and datapacks without world progress`() = runBlocking {
        tracked("example-1.jar", "1.0")
        write("config/example.txt", "old config")
        write("scripts/example.zs", "old script")
        write("saves/World/level.dat", "old world")
        write("saves/World/datapacks/example.zip", "old pack")
        BuildHistory.record(root, "1.21.1", loader, "Обновление проектов") {
            Files.delete(root.resolve("mods/example-1.jar"))
            tracked("example-2.jar", "2.0")
            write("config/example.txt", "new config")
            write("scripts/example.zs", "new script")
            write("saves/World/datapacks/example.zip", "new pack")
        }
        write("saves/World/level.dat", "new world")
        write("logs/latest.log", "keep logs")
        write("new-settings.txt", "added after snapshot")
        val snapshot = BuildHistory.list(root).single()
        assertEquals(BuildChange("Example", "1.0", "2.0"), snapshot.changes.single { it.title == "Example" })
        assertTrue(snapshot.changes.any { it.title == "example" })
        assertTrue(snapshot.completed)
        assertFalse(snapshot.includesWorlds)
        assertTrue(snapshot.sizeBytes > 0)
        assertFalse(snapshot.files.keys.any { it.endsWith("level.dat") })
        BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null)
        assertEquals("mod 1.0", Files.readString(root.resolve("mods/example-1.jar")))
        assertFalse(Files.exists(root.resolve("mods/example-2.jar")))
        assertEquals("old config", Files.readString(root.resolve("config/example.txt")))
        assertEquals("old script", Files.readString(root.resolve("scripts/example.zs")))
        assertEquals("old pack", Files.readString(root.resolve("saves/World/datapacks/example.zip")))
        assertEquals("new world", Files.readString(root.resolve("saves/World/level.dat")))
        assertEquals("keep logs", Files.readString(root.resolve("logs/latest.log")))
        assertFalse(Files.exists(root.resolve("new-settings.txt")))
        assertEquals("1.0", InstanceStore.get(root).catalogMods.single().versionNumber)
        val undo = BuildHistory.list(root).single { it.title == "Перед откатом" }
        BuildHistory.restore(root, undo.id, "1.21.1", loader, null)
        assertEquals("mod 2.0", Files.readString(root.resolve("mods/example-2.jar")))
        assertEquals("added after snapshot", Files.readString(root.resolve("new-settings.txt")))
    }

    @Test fun `world copies and world restoration are independent opt in choices`() = runBlocking {
        write("saves/World/level.dat", "old")
        write("saves/World/datapacks/example.zip", "old pack")
        InstanceStore.update(root) { it.copy(historyIncludeWorlds = true) }
        val snapshot = BuildHistory.create(root, "1.21.1", loader, "Manual", completed = true)
        assertTrue(snapshot.includesWorlds)
        write("saves/World/level.dat", "new")
        write("saves/World/datapacks/example.zip", "new pack")
        BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null)
        assertEquals("new", Files.readString(root.resolve("saves/World/level.dat")))
        assertEquals("old pack", Files.readString(root.resolve("saves/World/datapacks/example.zip")))
        write("saves/New world/level.dat", "new world")
        BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null, restoreWorlds = true)
        assertEquals("old", Files.readString(root.resolve("saves/World/level.dat")))
        assertFalse(Files.exists(root.resolve("saves/New world")))
        val undo = BuildHistory.list(root).first { it.title == "Перед откатом" && it.includesWorlds }
        BuildHistory.restore(root, undo.id, "1.21.1", loader, null, restoreWorlds = true)
        assertEquals("new", Files.readString(root.resolve("saves/World/level.dat")))
        assertEquals("new world", Files.readString(root.resolve("saves/New world/level.dat")))
    }

    @Test fun `corrupt snapshot cannot change the current build`() = runBlocking {
        write("mods/example.jar", "old")
        val snapshot = BuildHistory.create(root, "1.21.1", loader, "Manual")
        write("mods/example.jar", "current")
        write("${BuildHistory.FOLDER}/${snapshot.id}/data/mods/example.jar", "corrupt")
        assertThrows(IOException::class.java) { runBlocking { BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null) } }
        assertEquals("current", Files.readString(root.resolve("mods/example.jar")))
        assertEquals(1, BuildHistory.list(root).size)
    }

    @Test fun `failed profile commit rolls back all moved files`() = runBlocking {
        write("mods/old.jar", "old")
        val snapshot = BuildHistory.create(root, "1.20.1", loader, "Manual")
        Files.delete(root.resolve("mods/old.jar"))
        write("mods/current.jar", "current")
        assertThrows(IOException::class.java) {
            runBlocking { BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null) { throw IOException("commit failed") } }
        }
        assertFalse(Files.exists(root.resolve("mods/old.jar")))
        assertEquals("current", Files.readString(root.resolve("mods/current.jar")))
    }

    @Test fun `failed and cancelled batches keep a recovery snapshot`() = runBlocking {
        write("mods/example.jar", "initial")
        assertThrows(IOException::class.java) {
            runBlocking { BuildHistory.record(root, "1.21.1", loader, "Failed") {
                write("mods/example.jar", "partial")
                throw IOException("download failed")
            } }
        }
        val changed = CompletableDeferred<Unit>()
        val job = launch {
            BuildHistory.record(root, "1.21.1", loader, "Cancelled") {
                write("mods/example.jar", "partially cancelled")
                changed.complete(Unit)
                awaitCancellation()
            }
        }
        changed.await()
        job.cancelAndJoin()
        val history = BuildHistory.list(root)
        assertEquals(setOf("Failed", "Cancelled"), history.map { it.title }.toSet())
        assertTrue(history.all { it.completed })
        val cancelled = history.single { it.title == "Cancelled" }
        BuildHistory.restore(root, cancelled.id, "1.21.1", loader, null)
        assertEquals("partial", Files.readString(root.resolve("mods/example.jar")))
    }

    @Test fun `no op changes do not create history entries`() = runBlocking {
        write("mods/example.jar", "initial")
        BuildHistory.record(root, "1.21.1", loader, "No changes") { Unit }
        assertTrue(BuildHistory.list(root).isEmpty())
    }

    @Test fun `Minecraft version only changes are recorded and can be reverted`() = runBlocking {
        var version = "1.20.1"
        BuildHistory.record(root, version, loader, "Migration", targetGameVersion = "1.21.1") { version = "1.21.1" }
        val snapshot = BuildHistory.list(root).single()
        assertEquals(BuildChange("Minecraft", "1.20.1", "1.21.1"), snapshot.changes.single())
        BuildHistory.restore(root, snapshot.id, version, loader, null) { version = it.gameVersion }
        assertEquals("1.20.1", version)
        assertTrue(BuildHistory.list(root).any { it.gameVersion == "1.21.1" && it.targetGameVersion == "1.20.1" })
    }

    @Test fun `unsafe snapshot paths are rejected before mutation`() = runBlocking {
        write("mods/example.jar", "current")
        val snapshot = BuildHistory.create(root, "1.21.1", loader, "Manual")
        write("${BuildHistory.FOLDER}/${snapshot.id}/snapshot.json", Json.encodeToString(snapshot.copy(roots = listOf("../outside"))))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null) } }
        assertEquals("current", Files.readString(root.resolve("mods/example.jar")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { BuildHistory.delete(root, "../outside") } }
        Unit
    }

    @Test fun `old instance configs keep worlds disabled by default`() {
        val options = Json.decodeFromString<InstanceOptions>("{}")
        assertFalse(options.historyIncludeWorlds)
    }

    @Test fun `world folders remain opt in with a different letter case`() = runBlocking {
        write("Saves/World/level.dat", "world progress")
        val snapshot = BuildHistory.create(root, "1.21.1", loader, "Manual")
        assertFalse(snapshot.includesWorlds)
        assertFalse(snapshot.files.keys.any { it.endsWith("level.dat") })
        BuildHistory.restore(root, snapshot.id, "1.21.1", loader, null)
        assertEquals("world progress", Files.readString(root.resolve("Saves/World/level.dat")))
    }
}
