package ru.aw.launcher.instance

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.Storage
import ru.aw.launcher.meta.LoaderKind

class LocalBuildsTest {

    @TempDir
    lateinit var temp: Path

    private lateinit var originalFile: Path

    @BeforeEach
    fun useTemporaryStore() {
        originalFile = LocalBuilds.buildsFile
        LocalBuilds.buildsFile = temp.resolve("local-builds.json")
    }

    @AfterEach
    fun restoreStore() {
        LocalBuilds.list().forEach { Storage.deleteTree(LocalBuilds.dirOf(it)) }
        LocalBuilds.buildsFile = originalFile
    }

    @Test
    fun `profiles have independent stable directories and persist across renames`() {
        val first = LocalBuilds.create("Vanilla", "1.21.8", LoaderKind.VANILLA)
        val second = LocalBuilds.create("Fabric test", "1.21.8", LoaderKind.FABRIC)

        assertNotEquals(Settings.gameDir("1.21.8", LoaderKind.VANILLA), LocalBuilds.dirOf(first))
        assertNotEquals(LocalBuilds.dirOf(first), LocalBuilds.dirOf(second))
        assertEquals(2, LocalBuilds.list().size)

        val renamed = LocalBuilds.rename(first.id, "Survival")

        assertEquals("Survival", LocalBuilds.list().first { it.id == first.id }.name)
        assertEquals(LocalBuilds.dirOf(first), LocalBuilds.dirOf(renamed))
    }

    @Test
    fun `profile names must be unique regardless of case`() {
        LocalBuilds.create("Adventure", "1.21.8", LoaderKind.VANILLA)

        assertThrows(IOException::class.java) {
            LocalBuilds.create("adventure", "1.20.4", LoaderKind.FABRIC)
        }
    }

    @Test
    fun `import copies complete game files including logs and custom folders`() {
        val source = temp.resolve("Prism/Profile/.minecraft")
        Files.createDirectories(source.resolve("mods"))
        Files.createDirectories(source.resolve("config"))
        Files.createDirectories(source.resolve("saves/World"))
        Files.createDirectories(source.resolve("logs"))
        Files.writeString(source.resolve("mods/example.jar"), "mod")
        Files.writeString(source.resolve("config/example.json"), "{}")
        Files.writeString(source.resolve("saves/World/level.dat"), "world")
        Files.writeString(source.resolve("logs/latest.log"), "log")
        Files.createDirectories(source.resolve("custom-content/data"))
        Files.writeString(source.resolve("custom-content/data/script.txt"), "custom")
        Files.writeString(source.resolve("_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE"), "credential")

        val build = LocalBuilds.importDirectory(source.parent, "Imported", "1.21.8", LoaderKind.FABRIC)
        val target = LocalBuilds.dirOf(build)

        assertEquals("mod", Files.readString(target.resolve("mods/example.jar")))
        assertEquals("{}", Files.readString(target.resolve("config/example.json")))
        assertEquals("world", Files.readString(target.resolve("saves/World/level.dat")))
        assertEquals("log", Files.readString(target.resolve("logs/latest.log")))
        assertEquals("custom", Files.readString(target.resolve("custom-content/data/script.txt")))
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(target.resolve("_IAS_ACCOUNTS_DO_NOT_SEND_TO_ANYONE")))
        assertEquals("log", Files.readString(source.resolve("logs/latest.log")))
    }

    @Test
    fun `failed copying rolls back the registered build and leaves source intact`() {
        val source = Files.createDirectories(temp.resolve("rollback/mods"))
        Files.writeString(source.resolve("test.jar"), "mod")
        val profile = ImportProfile(source.parent, source.parent, "Rollback", "1.21.1", LoaderKind.FABRIC)
        assertThrows(IOException::class.java) { LocalBuilds.importProfile(profile) { throw IOException("copy failed") } }
        assertEquals(emptyList<LocalBuild>(), LocalBuilds.list())
        assertEquals("mod", Files.readString(source.resolve("test.jar")))
    }

    @Test
    fun `folder import reports copied bytes including large files and excludes credentials`() {
        val source = Files.createDirectories(temp.resolve("progress"))
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 127).toByte() }
        Files.write(source.resolve("world.dat"), bytes)
        Files.writeString(source.resolve("accounts.json"), "private credentials")
        val icon = temp.resolve("icon.png")
        Files.write(icon, byteArrayOf(1, 2, 3))
        val reports = ArrayList<ru.aw.launcher.net.DownloadProgress>()
        val profile = ImportProfile(source, source, "Progress", "1.21.1", LoaderKind.FABRIC, icon = icon)
        val imported = LocalBuilds.importProfile(profile, onProgress = reports::add) { file ->
            if (file.fileName.toString() == "world.dat") Thread.sleep(25)
        }
        assertEquals(bytes.size + 3L, reports.first().totalBytes)
        assertEquals(0f, reports.first().fraction)
        assertTrue(reports.any { it.completedBytes > 0 && it.completedBytes < it.totalBytes }, "Progress must advance inside a large file")
        assertEquals(1f, reports.last().fraction)
        assertEquals(2, reports.last().completedFiles)
        assertEquals(bytes.size + 3L, reports.last().completedBytes)
        assertTrue(reports.zipWithNext().all { (a, b) -> b.completedBytes >= a.completedBytes })
        assertArrayEquals(bytes, Files.readAllBytes(LocalBuilds.dirOf(imported).resolve("world.dat")))
        assertFalse(Files.exists(LocalBuilds.dirOf(imported).resolve("accounts.json")))
    }

    @Test
    fun `cancelling after partial import cleans the destination without changing the source`() {
        val source = Files.createDirectories(temp.resolve("cancel-progress"))
        val bytes = ByteArray(2 * 1024 * 1024) { 42 }
        Files.write(source.resolve("world.dat"), bytes)
        val profile = ImportProfile(source, source, "Cancelled", "1.21.1", LoaderKind.FABRIC)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            LocalBuilds.importProfile(profile, onProgress = { if (it.completedBytes > 0) throw kotlinx.coroutines.CancellationException() }) { file ->
                if (file.fileName.toString() == "world.dat") Thread.sleep(25)
            }
        }
        assertTrue(LocalBuilds.list().isEmpty())
        assertArrayEquals(bytes, Files.readAllBytes(source.resolve("world.dat")))
    }

    @Test
    fun `import preserves loader version and activity and resolves duplicate names`() {
        val source = Files.createDirectories(temp.resolve("duplicate/mods"))
        val profile = ImportProfile(source.parent, source.parent, "Adventure", "1.21.1", LoaderKind.FABRIC, "0.16.14", 36_000_000, 1_700_000_000_000)
        val first = LocalBuilds.importProfile(profile)
        val second = LocalBuilds.importProfile(profile)
        assertEquals("Adventure (2)", second.name)
        assertEquals("0.16.14", LocalBuilds.list().first { it.id == first.id }.loaderVersion)
        assertEquals(36_000_000, first.importedPlayTimeMillis)
        assertEquals(profile.lastPlayed, first.lastPlayed)
    }

    @Test
    fun `duplicating a game directory preserves worlds settings and custom icons but starts fresh activity`() {
        val source = Files.createDirectories(temp.resolve("duplicate-source"))
        Files.createDirectories(source.resolve("logs"))
        Files.createDirectories(source.resolve("saves/World/logs"))
        Files.writeString(source.resolve("logs/latest.log"), "old session")
        Files.writeString(source.resolve("latest-game.log"), "old launcher session")
        Files.writeString(source.resolve("saves/World/level.dat"), "world")
        Files.writeString(source.resolve("saves/World/logs/custom.txt"), "world-owned data")
        InstanceStore.update(source) { it.copy(memoryMb = 3072, favorite = true, iconPreset = "crystal", groupId = "group") }
        val copy = LocalBuilds.importProfile(ImportProfile(source, source, "Copy", "1.21.1", LoaderKind.FABRIC, "0.16.14"), copyHistory = false)
        val dir = LocalBuilds.dirOf(copy)
        assertEquals("world", Files.readString(dir.resolve("saves/World/level.dat")))
        assertEquals("world-owned data", Files.readString(dir.resolve("saves/World/logs/custom.txt")))
        assertEquals(3072, InstanceStore.get(dir).memoryMb)
        assertEquals("crystal", InstanceStore.get(dir).iconPreset)
        assertFalse(Files.exists(dir.resolve("logs")))
        assertFalse(Files.exists(dir.resolve("latest-game.log")))
        assertEquals(0L, copy.importedPlayTimeMillis)
        assertEquals("old session", Files.readString(source.resolve("logs/latest.log")))
    }

    @Test
    fun readableDirectoryNamesKeepSpacesAndUnicodeWithoutOverwritingExistingFolders() {
        val unrelated = Settings.buildsDir().resolve("Моя сборка").also { Files.createDirectories(it) }
        Files.writeString(unrelated.resolve("keep.txt"), "existing data")
        try {
            val build = LocalBuilds.create("Моя сборка", "1.21.1", LoaderKind.FABRIC)
            assertEquals("Моя сборка (2)", LocalBuilds.dirOf(build).fileName.toString())
            assertEquals(build, LocalBuilds.list().single())
            assertEquals("existing data", Files.readString(unrelated.resolve("keep.txt")))
            val first = LocalBuilds.create("Test:pack", "1.21.1", LoaderKind.FABRIC)
            val second = LocalBuilds.create("Test?pack", "1.21.1", LoaderKind.FABRIC)
            assertEquals("Test_pack", LocalBuilds.dirOf(first).fileName.toString())
            assertEquals("Test_pack (2)", LocalBuilds.dirOf(second).fileName.toString())
        } finally { Storage.deleteTree(unrelated) }
    }

    @Test
    fun directoryNamesHandleWindowsDevicesAndPathSeparators() {
        for (name in listOf("CON", "NUL.txt", "LPT1", "COM¹", "..", "../outside", "C:\\Games", "Ending. ")) {
            val build = LocalBuilds.create(name, "1.21.1", LoaderKind.VANILLA)
            val directory = LocalBuilds.dirOf(build).toAbsolutePath().normalize()
            assertEquals(Settings.buildsDir().toAbsolutePath().normalize(), directory.parent)
            assertFalse(directory.fileName.toString().endsWith('.'))
            assertTrue(Files.isDirectory(directory))
        }
    }

    @Test
    fun legacyFoldersMigrateWithWorldsOptionsAndIdentityIntact() {
        val legacy = LocalBuild(java.util.UUID.randomUUID().toString(), "Survival", "1.21.1", LoaderKind.FABRIC)
        buildsFile(listOf(legacy))
        val old = LocalBuilds.dirOf(legacy).also { Files.createDirectories(it.resolve("saves/World")) }
        Files.writeString(old.resolve("saves/World/level.dat"), "world data")
        Files.writeString(old.resolve("aw-instance.json"), "{\"memoryMb\":3584}")
        val migrated = LocalBuilds.migrateDirectories().single()
        val directory = LocalBuilds.dirOf(migrated)
        assertEquals("Survival", directory.fileName.toString())
        assertEquals(legacy.id, migrated.id)
        assertFalse(Files.exists(old))
        assertEquals("world data", Files.readString(directory.resolve("saves/World/level.dat")))
        assertEquals("{\"memoryMb\":3584}", Files.readString(directory.resolve("aw-instance.json")))
        assertEquals(listOf(migrated), LocalBuilds.migrateDirectories())
        assertEquals(directory, LocalBuilds.dirOf(LocalBuilds.list().single()))
    }

    @Test
    fun interruptedMigrationFindsTheOriginalDirectoryAndResumesWithoutOverwritingFiles() {
        val legacy = LocalBuild(java.util.UUID.randomUUID().toString(), "Recovery", "1.21.1", LoaderKind.FABRIC)
        val old = LocalBuilds.dirOf(legacy).also { Files.createDirectories(it) }
        Files.writeString(old.resolve("options.txt"), "personal options")
        val pending = legacy.copy(directoryName = "Recovery")
        buildsFile(listOf(pending))
        assertEquals(old, LocalBuilds.dirOf(LocalBuilds.list().single()))
        val migrated = LocalBuilds.migrateDirectories().single()
        assertEquals("Recovery", LocalBuilds.dirOf(migrated).fileName.toString())
        assertEquals("personal options", Files.readString(LocalBuilds.dirOf(migrated).resolve("options.txt")))
    }

    @Test
    fun folderMigrationDoesNotDuplicateSavedPlayHistory() {
        val history = ru.aw.launcher.activity.PlayHistory
        val oldHistory = history.historyFile
        val oldCache = history.cacheFile
        try {
            history.historyFile = temp.resolve("activity.json")
            history.cacheFile = null
            history.resetForTests()
            val legacy = LocalBuild(java.util.UUID.randomUUID().toString(), "History", "1.21.1", LoaderKind.FABRIC)
            buildsFile(listOf(legacy))
            val old = LocalBuilds.dirOf(legacy)
            Files.createDirectories(old.resolve("logs"))
            Files.writeString(old.resolve("logs/latest.log"), "[10:00:00] Start\n[11:00:00] Stop\n")
            val original = history.scan(listOf(Settings.buildsDir()))
            assertEquals(1, original.size)
            val migrated = LocalBuilds.migrateDirectories().single()
            assertEquals("History", LocalBuilds.dirOf(migrated).fileName.toString())
            assertEquals(original, history.scan(listOf(Settings.buildsDir())))
            history.resetForTests()
            val restored = history.scan(listOf(Settings.buildsDir()))
            assertEquals(1, restored.size)
            assertEquals(3_600_000L, restored.sumOf { it.millis })
            assertEquals(legacy.id, restored.single().buildId)
        } finally {
            history.historyFile = oldHistory
            history.cacheFile = oldCache
            history.resetForTests()
        }
    }

    private fun buildsFile(builds: List<LocalBuild>) {
        Files.writeString(LocalBuilds.buildsFile, ru.aw.launcher.core.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(LocalBuild.serializer()), builds))
    }

}
