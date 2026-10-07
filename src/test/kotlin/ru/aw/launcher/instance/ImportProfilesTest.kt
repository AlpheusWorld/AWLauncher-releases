package ru.aw.launcher.instance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import ru.aw.launcher.meta.LoaderKind

class ImportProfilesTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `Prism instance and inner game folder resolve metadata and activity`() {
        val root = Files.createDirectories(temp.resolve("Prism/instances/Adventure"))
        Files.createDirectories(root.resolve(".minecraft/mods"))
        Files.writeString(root.resolve("instance.cfg"), "[General]\nname=Adventure\ntotalTimePlayed=7200\nlastLaunchTime=1720000000000\n")
        Files.writeString(root.resolve("mmc-pack.json"), """{"components":[{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.14"}]}""")
        val profile = ImportProfiles.discover(root).single()
        assertEquals("Adventure", profile.name)
        assertEquals("1.21.1", profile.versionId)
        assertEquals(LoaderKind.FABRIC, profile.loader)
        assertEquals("0.16.14", profile.loaderVersion)
        assertEquals(7_200_000L, profile.playTimeMillis)
        assertEquals(1_720_000_000_000L, profile.lastPlayed)
        assertEquals(profile, ImportProfiles.discover(root.resolve(".minecraft")).single())
        assertEquals(profile, ImportProfiles.discover(root.parent.parent).single())
    }

    @Test
    fun `modern Modrinth SQLite instance uses the applied content set and sums seconds`() {
        val game = Files.createDirectories(temp.resolve("profiles/Folder name/mods")).parent
        val db = temp.resolve("app.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection -> connection.createStatement().use { s ->
            s.execute("CREATE TABLE instances (name TEXT, path TEXT, icon_path TEXT, last_played INTEGER, submitted_time_played INTEGER, recent_time_played INTEGER, applied_content_set_id TEXT)")
            s.execute("CREATE TABLE instance_content_sets (id TEXT, game_version TEXT, loader TEXT, loader_version TEXT)")
            s.execute("INSERT INTO instances VALUES ('Fabulously Optimized','Folder name',NULL,1720000000,3600,1200,'current')")
            s.execute("INSERT INTO instance_content_sets VALUES ('current','1.21.4','fabric','0.18.5')")
            s.execute("INSERT INTO instance_content_sets VALUES ('old','1.20.1','forge','47.0.1')")
        } }
        val before = Files.readAllBytes(db)
        val profile = ImportProfiles.discover(game).single()
        assertEquals("1.21.4", profile.versionId)
        assertEquals("0.18.5", profile.loaderVersion)
        assertEquals("Fabulously Optimized", profile.name)
        assertEquals(4_800_000L, profile.playTimeMillis)
        assertEquals(1_720_000_000_000L, profile.lastPlayed)
        assertEquals(profile, ImportProfiles.discover(temp).single())
        assertArrayEquals(before, Files.readAllBytes(db))
    }

    @Test
    fun `legacy Modrinth profile JSON recognizes metadata and loader version object`() {
        val root = Files.createDirectories(temp.resolve("legacy/mods")).parent
        Files.writeString(root.resolve("profile.json"), """{"metadata":{"name":"Legacy","game_version":"1.20.1","loader":"fabric","loader_version":{"id":"0.15.11"},"submitted_time_played":1800,"recent_time_played":600,"last_played":"2024-07-03T09:46:40Z"}}""")
        val profile = ImportProfiles.discover(root).single()
        assertEquals("Legacy", profile.name)
        assertEquals("0.15.11", profile.loaderVersion)
        assertEquals(2_400_000L, profile.playTimeMillis)
    }

    @Test
    fun `CurseForge installed folder recognizes name minecraft and forge`() {
        val root = Files.createDirectories(temp.resolve("curse/mods")).parent
        Files.writeString(root.resolve("minecraftinstance.json"), """{"name":"Better MC","minecraftVersion":"1.20.1","baseModLoader":{"name":"forge-47.2.0","forgeVersion":"47.2.0","minecraftVersion":"1.20.1"}}""")
        val profile = ImportProfiles.discover(root).single()
        assertEquals("Better MC", profile.name)
        assertEquals("1.20.1", profile.versionId)
        assertEquals(LoaderKind.FORGE, profile.loader)
        assertEquals("47.2.0", profile.loaderVersion)
    }

    @Test
    fun `unknown folder asks for version instead of choosing a latest release`() {
        val root = Files.createDirectories(temp.resolve("unknown/mods")).parent
        assertNull(ImportProfiles.discover(root).single().versionId)
    }

    @Test
    fun `ATLauncher reads nested loader and name and preserves the launch counter`() {
        val root = Files.createDirectories(temp.resolve("AT/instances/Instance/mods")).parent
        Files.writeString(root.resolve("instance.json"), """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","launcher":{"name":"AT Adventure","loaderVersion":{"type":"Forge","version":"47.2.0"},"numPlays":42,"lastPlayed":"2024-07-03T09:46:40Z"}}""")
        val profile = ImportProfiles.discover(root).single()
        assertEquals("AT Adventure", profile.name)
        assertEquals("1.20.1", profile.versionId)
        assertEquals(LoaderKind.FORGE, profile.loader)
        assertEquals("47.2.0", profile.loaderVersion)
        assertEquals(42L, profile.launchCount)
        assertNotNull(profile.lastPlayed)
    }

    @Test
    fun `metadata can be recovered from the Fabric startup log`() {
        val log = Files.createDirectories(temp.resolve("logged/logs"))
        Files.writeString(log.resolve("latest.log"), "[18:00:00] [main/INFO]: Loading Minecraft 1.21.4 with Fabric Loader 0.18.1\n")
        val profile = ImportProfiles.discover(log.parent).single()
        assertEquals("1.21.4", profile.versionId)
        assertEquals(LoaderKind.FABRIC, profile.loader)
        assertEquals("0.18.1", profile.loaderVersion)
    }
}
