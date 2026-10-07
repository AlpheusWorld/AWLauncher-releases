package ru.aw.launcher.instance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.LauncherSettings
import ru.aw.launcher.core.Json
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException

class InstanceStoreTest {
    @TempDir lateinit var root: Path

    @Test
    fun `old profiles inherit launch settings and retain managed mods`() {
        val old = Json.decodeFromString<InstanceOptions>("""{"fpsBoost":true,"blockedUpdates":["blocked-version"]}""")
        val defaults = LauncherSettings(memoryMb = 4096, jvmArgs = "-XX:+UseG1GC")
        assertEquals(defaults, old.launchSettings(defaults))
        assertNull(old.javaPath)
        assertNull(old.windowWidth)
        assertNull(old.fullscreen)
        assertEquals(listOf("blocked-version"), old.blockedUpdates)
    }

    @Test
    fun `profile overrides persist separately without changing shared settings or mod tracking`() {
        val first = root.resolve("first")
        val second = root.resolve("second")
        val mod = ManagedMod("project", "version", "example.jar", "hash")
        InstanceStore.update(first) { it.copy(catalogMods = listOf(mod), blockedUpdates = listOf("blocked")) }
        InstanceStore.update(first) { it.copy(memoryMb = 3072, jvmArgs = "", javaPath = "C:/Java/bin/java.exe", windowWidth = 1280, windowHeight = 720, fullscreen = false,
            favorite = true, groupId = "group-id", iconPreset = "fox", iconBackground = "rose") }
        InstanceStore.update(second) { it.copy(memoryMb = 6144, jvmArgs = "-Dprofile=second") }
        InstanceStore.forget(first)
        InstanceStore.forget(second)
        val stored = InstanceStore.get(first)
        assertEquals(3072, stored.memoryMb)
        assertEquals("", stored.jvmArgs)
        assertEquals(1280, stored.windowWidth)
        assertEquals(false, stored.fullscreen)
        assertTrue(stored.favorite)
        assertEquals("group-id", stored.groupId)
        assertEquals("fox", stored.iconPreset)
        assertEquals(listOf(mod), stored.catalogMods)
        assertEquals(listOf("blocked"), stored.blockedUpdates)
        val defaults = LauncherSettings(memoryMb = 4096, jvmArgs = "-XX:+UseG1GC")
        assertEquals(3072, stored.launchSettings(defaults).memoryMb)
        assertEquals("", stored.launchSettings(defaults).jvmArgs)
        assertEquals(6144, InstanceStore.get(second).launchSettings(defaults).memoryMb)
        assertEquals(4096, defaults.memoryMb)
        InstanceStore.update(first) { it.copy(memoryMb = null, jvmArgs = null, javaPath = null, windowWidth = null, windowHeight = null, fullscreen = null) }
        assertEquals(defaults, InstanceStore.get(first).launchSettings(defaults))
        assertEquals(listOf(mod), InstanceStore.get(first).catalogMods)
        assertTrue(InstanceStore.get(first).favorite)
    }

    @Test
    fun `former performance mods migrate without deleting their files or tracking`() {
        Files.createDirectories(root.resolve("mods"))
        val mod = Files.writeString(root.resolve("mods/sodium.jar"), "existing mod")
        Files.writeString(root.resolve(InstanceStore.FILE_NAME), """{"fpsBoost":true,"boostMods":[{"projectId":"sodium","versionId":"v1","fileName":"sodium.jar","sha1":"hash"}],"memoryMb":3072}""")
        val migrated = InstanceStore.get(root)
        assertEquals("mods/sodium.jar", migrated.catalogMods.single().fileName)
        assertEquals(3072, migrated.memoryMb)
        InstanceStore.update(root) { it }
        assertFalse(Files.readString(root.resolve(InstanceStore.FILE_NAME)).contains("fpsBoost"))
        assertEquals("existing mod", Files.readString(mod))
        InstanceStore.forget(root)
        assertEquals(migrated, InstanceStore.get(root))
    }

    @Test
    fun `failed saving does not replace the previously cached settings`() {
        val dir = Files.createDirectories(root.resolve("unwritable"))
        val file = Files.createDirectories(dir.resolve(InstanceStore.FILE_NAME))
        Files.writeString(file.resolve("keep"), "existing data")
        val before = InstanceStore.get(dir)
        assertThrows(IOException::class.java) { InstanceStore.update(dir) { it.copy(memoryMb = 8192) } }
        assertEquals(before, InstanceStore.get(dir))
        assertEquals("existing data", Files.readString(file.resolve("keep")))
    }
}
