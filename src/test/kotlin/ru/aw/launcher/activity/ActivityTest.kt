package ru.aw.launcher.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.ui.screens.formatPlayTime
import ru.aw.launcher.ui.screens.plural
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ActivityTest {
    @Test
    fun `general activity excludes foreign launcher profiles and previously cached foreign sessions`(@TempDir root: Path) {
        val historyFile = PlayHistory.historyFile
        val cacheFile = PlayHistory.cacheFile
        val settings = ru.aw.launcher.core.Settings.current
        try {
            PlayHistory.historyFile = root.resolve("history.json")
            PlayHistory.cacheFile = null
            PlayHistory.resetForTests()
            val imported = root.resolve("aw-profile")
            val foreign = root.resolve("modrinth-other-profile")
            for (dir in listOf(imported, foreign)) {
                dir.resolve("logs").createDirectories()
                dir.resolve("logs/2026-09-27-1.log.gz").writeBytes(ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("[10:00:00] Start\n[11:00:00] Stop\n".toByteArray()) } }.toByteArray())
            }
            imported.resolve(ru.aw.launcher.instance.InstanceStore.FILE_NAME).writeText("{}")
            val stale = PlaySession("modrinth-other-profile", "1.21.1", LoaderKind.FABRIC, 0, 7_200_000)
            PlayHistory.historyFile!!.writeText(ru.aw.launcher.core.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PlaySession.serializer()), listOf(stale)))
            PlayHistory.resetForTests()
            val sessions = PlayHistory.scan(listOf(root), zone)
            assertEquals(listOf("aw-profile"), sessions.map { it.instance })
            assertEquals(3_600_000L, ActivityStats.of(sessions).totalMillis)
            PlayHistory.resetForTests()
            assertEquals(3_600_000L, ActivityStats.of(PlayHistory.scan(listOf(root), zone)).totalMillis)
        } finally {
            PlayHistory.historyFile = historyFile
            PlayHistory.cacheFile = cacheFile
            PlayHistory.resetForTests()
            ru.aw.launcher.core.Settings.update { settings }
        }
    }


    @Test
    fun `calendar hit testing respects gaps, bounds and right to left layout`() {
        val first = LocalDate.of(2026, 9, 21)
        fun at(x: Float, y: Float, rtl: Boolean = false) = ru.aw.launcher.ui.screens.heatmapDayAt(first, 4, x, y, 13f, 17f, rtl)
        assertEquals(first, at(0f, 0f))
        assertEquals(first.plusDays(9), at(18f, 35f))
        assertEquals(first.plusDays(21), at(0f, 0f, true))
        assertEquals(first, at(52f, 0f, true))
        assertNull(at(14f, 0f))
        assertNull(at(0f, 14f))
        assertNull(at(68f, 0f))
        assertNull(at(0f, 119f))
        assertNull(at(-1f, 0f))
        assertNull(at(Float.NaN, 0f))
    }

    @Test
    fun `imported activity fills missing history without counting copied logs twice`() {
        val imported = ru.aw.launcher.instance.LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Imported", "1.21.1", LoaderKind.FABRIC,
            importedPlayTimeMillis = 10_000, importedAt = 20_000)
        val historical = PlaySession("folder", "1.21.1", LoaderKind.FABRIC, 0, 5_000, buildId = imported.id, buildName = imported.name)
        val newSession = historical.copy(start = 30_000, end = 32_000)
        val stats = ActivityStats.of(listOf(historical, newSession), imports = listOf(imported))
        assertEquals(12_000L, stats.totalMillis)
        assertEquals(5_000L, stats.importedMillis)
        assertEquals(12_000L, stats.versions.single().millis)
        assertEquals(7_000L, stats.days.values.sum())
    }

    @Test
    fun `a session across import time counts only its historical part against the counter`() {
        val imported = ru.aw.launcher.instance.LocalBuild("72a4ca77-3aae-4c19-b237-652bf6a4fd12", "Imported", "1.21.1", LoaderKind.FABRIC,
            importedPlayTimeMillis = 10_000, importedAt = 20_000)
        val session = PlaySession("folder", "1.21.1", LoaderKind.FABRIC, 15_000, 25_000, buildId = imported.id, buildName = imported.name)
        assertEquals(15_000L, ActivityStats.of(listOf(session), imports = listOf(imported)).totalMillis)
    }

    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 9, 27)

    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun session(date: LocalDate, from: Pair<Int, Int>, to: Pair<Int, Int>, label: String = "1.21.11", loader: LoaderKind = LoaderKind.FABRIC) =
        PlaySession("$label-fabric", label, loader, at(date, from.first, from.second), at(date, to.first, to.second))

    @Test
    fun `a session runs from the first to the last timestamp and remembers the server`() {
        val lines = sequenceOf(
            "[18:39:48] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.19.5",
            "\tat some.stack.Trace(Trace.java:1)",
            "[18:41:02] [Render thread/INFO]: Connecting to play.example.org, 25565",
            "[18:41:03] [Render thread/INFO]: [voicechat] Connecting to voice chat server: '95.182.102.54:24450'",
            "[19:25:09] [Render thread/INFO]: Stopping!",
        )
        val session = PlayHistory.parse(lines, day, zone, "1.21.11-fabric", "1.21.11", LoaderKind.FABRIC)!!
        assertEquals(at(day, 18, 39) + 48_000, session.start)
        assertEquals(45 * 60_000L + 21_000, session.millis)
        assertEquals("play.example.org", session.server)
        assertEquals("1.21.11 Fabric", session.label)
    }

    @Test
    fun `forge timestamps carry their own date, midnight is crossed, crashes at start are skipped`() {
        val forge = sequenceOf(
            "[26Sep2026 23:50:00.123] [main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: starting",
            "[27Sep2026 00:20:00.456] [Render thread/INFO] [net.minecraft.client.Minecraft/]: Stopping!",
        )
        val session = PlayHistory.parse(forge, day, zone, "1.20.1-forge", "1.20.1", LoaderKind.FORGE)!!
        assertEquals(30 * 60_000L, session.millis)

        val overnight = sequenceOf("[23:40:00] [main/INFO]: a", "[00:10:00] [main/INFO]: b")
        assertEquals(30 * 60_000L, PlayHistory.parse(overnight, day, zone, "x", "x", LoaderKind.VANILLA)!!.millis)

        val crash = sequenceOf("[17:49:24] [main/INFO]: Loading", "[17:49:26] [main/ERROR]: Incompatible mods found!")
        assertNull(PlayHistory.parse(crash, day, zone, "x", "x", LoaderKind.VANILLA))
    }

    @Test
    fun `instance folders map to version and loader`() {
        assertEquals("1.21.11" to LoaderKind.FABRIC, PlayHistory.identify("1.21.11-fabric"))
        assertEquals("1.20.1" to LoaderKind.NEOFORGE, PlayHistory.identify("1.20.1-neoforge"))
        assertEquals("26.3" to LoaderKind.VANILLA, PlayHistory.identify("26.3"))
        assertEquals("1.20.5-pre1" to LoaderKind.VANILLA, PlayHistory.identify("1.20.5-pre1"))
    }

    @Test
    fun `sessions stay in the history after minecraft deletes old logs`(@TempDir root: Path) {
        PlayHistory.resetForTests()
        PlayHistory.cacheFile = null
        PlayHistory.historyFile = root.resolve("activity.json")
        val logs = root.resolve("games/1.21.11-fabric/logs").createDirectories()
        logs.parent.resolve(ru.aw.launcher.instance.InstanceStore.FILE_NAME).writeText("{}")
        val old = logs.resolve("latest.log").apply { writeText("[18:39:48] [main/INFO]: a\n[19:25:09] [main/INFO]: b\n") }
        assertEquals(1, PlayHistory.scan(listOf(root.resolve("games")), zone).size)

        old.writeText("[20:00:00] [main/INFO]: a\n[20:10:00] [main/INFO]: b\n")
        val sessions = PlayHistory.scan(listOf(root.resolve("games")), zone)
        assertEquals(listOf(45L, 10L), sessions.map { it.millis / 60_000 })

        PlayHistory.resetForTests()
        assertEquals(2, PlayHistory.scan(emptyList(), zone).size)
        PlayHistory.historyFile = null
        PlayHistory.resetForTests()
    }

    @Test
    fun `scans rolled and latest logs of every instance`(@TempDir root: Path) {
        PlayHistory.resetForTests()
        PlayHistory.cacheFile = null
        PlayHistory.historyFile = null
        val logs = root.resolve("1.21.11-fabric/logs").createDirectories()
        logs.parent.resolve(ru.aw.launcher.instance.InstanceStore.FILE_NAME).writeText("{}")
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("[10:00:00] [main/INFO]: a\n[10:40:00] [main/INFO]: b\n".toByteArray()) }
        }
        logs.resolve("2026-09-25-1.log.gz").writeBytes(gz.toByteArray())
        logs.resolve("latest.log").writeText("[12:00:00] [main/INFO]: a\n[12:30:00] [main/INFO]: b\n")
        logs.resolve("debug-1.log.gz").writeBytes(gz.toByteArray())
        root.resolve("26.3/logs").createDirectories()

        val sessions = PlayHistory.scan(listOf(root), zone)
        assertEquals(listOf(40L, 30L), sessions.map { it.millis / 60_000 })
        assertEquals(LocalDate.of(2026, 9, 25), java.time.Instant.ofEpochMilli(sessions.first().start).atZone(zone).toLocalDate())
    }

    @Test
    fun `a pack folder is named after the pack`(@TempDir root: Path) {
        PlayHistory.resetForTests()
        PlayHistory.cacheFile = null
        PlayHistory.historyFile = null
        val dir = root.resolve("fabulously-optimized")
        dir.resolve("logs").createDirectories().resolve("latest.log").writeText("[12:00:00] [main/INFO]: a\n[12:30:00] [main/INFO]: b\n")
        dir.resolve("aw-pack.json").writeText(
            """{"id":"fabulously-optimized","title":"Fabulously Optimized","version":"14.1.0","gameVersion":"26.2","loader":"FABRIC","projectId":"1KVo5zza","versionId":"ssWn7YI0"}""",
        )

        val session = PlayHistory.scan(listOf(root), zone).single()
        assertEquals("Fabulously Optimized", session.label)
        assertEquals("26.2" to LoaderKind.FABRIC, session.versionId to session.loader)
    }

    @Test
    fun `totals, week, streaks, longest and favourites`() {
        val sessions = listOf(
            session(day, 18 to 0, 19 to 30),
            session(day.minusDays(1), 20 to 0, 20 to 45),
            session(day.minusDays(2), 21 to 0, 21 to 10, "26.3", LoaderKind.VANILLA),
            session(day.minusDays(5), 10 to 0, 12 to 0, "26.3", LoaderKind.VANILLA),
            session(day.minusDays(6), 10 to 0, 10 to 20, "26.3", LoaderKind.VANILLA),
        )
        val stats = ActivityStats.of(sessions, day, zone)
        assertEquals((90 + 45 + 10 + 120 + 20) * 60_000L, stats.totalMillis)
        assertEquals(3, stats.streak)
        assertEquals(3, stats.bestStreak)
        assertEquals(5, stats.activeDays)
        assertEquals((90 + 45 + 10 + 120 + 20) * 60_000L, stats.weekMillis + stats.lastWeekMillis)
        assertEquals(120 * 60_000L, stats.longest!!.millis)
        assertEquals(listOf("26.3", "1.21.11 Fabric"), stats.versions.map { it.label })
        assertEquals(session(day, 18 to 0, 19 to 30).start, stats.recent.first().start)
    }

    @Test
    fun `a night session is split between two days`() {
        val night = PlaySession("x", "x", LoaderKind.VANILLA, at(day.minusDays(1), 23, 30), at(day, 0, 45))
        val parts = ActivityStats.split(night, zone)
        assertEquals(listOf(day.minusDays(1) to 30 * 60_000L, day to 45 * 60_000L), parts)
    }

    @Test
    fun `time and plurals read naturally`() {
        assertEquals("< 1 мин", formatPlayTime(30_000))
        assertEquals("45 мин", formatPlayTime(45 * 60_000L))
        assertEquals("2 ч", formatPlayTime(120 * 60_000L))
        assertEquals("4 ч 12 мин", formatPlayTime(252 * 60_000L))
        assertEquals("сессия", plural(21, "сессия", "сессии", "сессий"))
        assertEquals("сессии", plural(3, "сессия", "сессии", "сессий"))
        assertEquals("сессий", plural(12, "сессия", "сессии", "сессий"))
    }
}
