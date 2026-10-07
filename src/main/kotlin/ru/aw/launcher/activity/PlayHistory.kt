package ru.aw.launcher.activity

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.packs.Modpacks
import ru.aw.launcher.instance.LocalBuilds
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Serializable
data class PlaySession(
    val instance: String,
    val versionId: String,
    val loader: LoaderKind,
    val start: Long,
    val end: Long,
    val server: String? = null,
    val pack: String? = null,
    val buildId: String? = null,
    val buildName: String? = null,
) {
    val millis: Long get() = end - start
    val label: String get() = buildName ?: pack ?: if (loader.isModded) "$versionId ${loader.label}" else versionId
}

object PlayHistory {

    private const val MIN_SESSION_MILLIS = 60_000L
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    private val TIME = Regex("""^\[(\d{2}):(\d{2}):(\d{2})""")
    private val FORGE_TIME = Regex("""^\[(\d{2})([A-Za-z]{3})(\d{4}) (\d{2}):(\d{2}):(\d{2})""")
    private val ROLLED = Regex("""^(\d{4})-(\d{2})-(\d{2})-\d+\.log\.gz$""")
    private val CONNECT = Regex("""]: Connecting to ([^\s,]+), \d+\s*$""")
    private val MONTHS = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL,
        "may" to Month.MAY, "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )

    @Serializable
    private data class Cached(val size: Long, val modified: Long, val session: PlaySession?)

    private val cache = ConcurrentHashMap<String, Cached>()
    private var cacheLoaded = false
    internal var cacheFile: Path? = Paths.cache.resolve("activity-index-2.json")
    internal var historyFile: Path? = Paths.root.resolve("activity.json")

    private val history = LinkedHashMap<String, PlaySession>()
    private var historyLoaded = false

    @Synchronized
    fun scan(roots: List<Path>, zone: ZoneId = ZoneId.systemDefault()): List<PlaySession> {
        loadCache()
        loadHistory()
        val seen = HashSet<String>()
        val buildsByFolder = LocalBuilds.list().associateBy { LocalBuilds.dirOf(it).fileName.toString() }
        val found = roots.filter { it.isDirectory() }.flatMap { root ->
            runCatching { root.listDirectoryEntries() }.getOrDefault(emptyList())
                .filter { it.resolve("logs").isDirectory() }
                .flatMap { instance -> sessionsOf(instance, zone, seen, buildsByFolder[instance.name]) }
        }
        cache.keys.retainAll(seen)
        saveCache()
        if (remember(found)) saveHistory()
        return history.values.sortedBy { it.start }
    }

    private fun remember(found: List<PlaySession>): Boolean {
        var changed = false
        found.forEach { session ->
            val key = "${session.instance}@${session.start}"
            val known = history[key]
            val merged = if (known == null) session else known.copy(
                versionId = session.versionId,
                loader = session.loader,
                end = maxOf(known.end, session.end),
                server = session.server ?: known.server,
                buildId = session.buildId ?: known.buildId,
                buildName = session.buildName ?: known.buildName,
            )
            if (merged != known) {
                history[key] = merged
                changed = true
            }
        }
        return changed
    }

    private fun loadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        val file = historyFile ?: return
        if (!file.exists()) return
        runCatching { Json.decodeFromString<List<PlaySession>>(file.readText()) }
            .onSuccess { list -> list.forEach { history["${it.instance}@${it.start}"] = it } }
            .onFailure { Log.warn("activity.json unreadable: ${it.message}") }
    }

    private fun saveHistory() {
        val file = historyFile ?: return
        runCatching { file.writeAtomically(Json.encodeToString(history.values.sortedBy { it.start })) }
            .onFailure { Log.warn("could not save activity history: ${it.message}") }
    }

    internal fun resetForTests() {
        history.clear()
        historyLoaded = false
        cache.clear()
        cacheLoaded = false
    }

    private fun sessionsOf(instance: Path, zone: ZoneId, seen: MutableSet<String>, build: ru.aw.launcher.instance.LocalBuild?): List<PlaySession> {
        val pack = Modpacks.read(instance)
        val (versionId, loader) = build?.let { it.versionId to it.loader } ?: pack?.let { it.gameVersion to it.loader } ?: identify(instance.name)
        val logs = runCatching { instance.resolve("logs").listDirectoryEntries() }.getOrDefault(emptyList())
        return logs.mapNotNull { file ->
            val date = ROLLED.matchEntire(file.name)?.let { m ->
                runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            } ?: if (file.name == "latest.log") {
                LocalDate.ofInstant(file.getLastModifiedTime().toInstant(), zone)
            } else {
                return@mapNotNull null
            }
            val key = file.toAbsolutePath().normalize().toString()
            seen += key
            val size = runCatching { file.fileSize() }.getOrDefault(-1)
            val modified = runCatching { file.getLastModifiedTime().toMillis() }.getOrDefault(-1)
            cache[key]?.takeIf { it.size == size && it.modified == modified }?.let {
                return@mapNotNull it.session?.let { session ->
                    if (build == null) session else session.copy(versionId = build.versionId, loader = build.loader, buildId = build.id, buildName = build.name)
                }
            }
            val session = runCatching { read(file, date, zone, instance.name, versionId, loader, pack?.title, build?.id, build?.name) }
                .onFailure { Log.warn("activity: could not read ${file.name}: ${it.message}") }
                .getOrNull()
            cache[key] = Cached(size, modified, session)
            session
        }
    }

    private fun read(
        file: Path,
        date: LocalDate,
        zone: ZoneId,
        instance: String,
        versionId: String,
        loader: LoaderKind,
        pack: String?,
        buildId: String? = null,
        buildName: String? = null,
    ): PlaySession? {
        val stream = Files.newInputStream(file).let { if (file.name.endsWith(".gz")) GZIPInputStream(it) else it }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            parse(reader.lineSequence(), date, zone, instance, versionId, loader, pack, buildId, buildName)
        }
    }

    internal fun parse(
        lines: Sequence<String>,
        date: LocalDate,
        zone: ZoneId,
        instance: String,
        versionId: String,
        loader: LoaderKind,
        pack: String? = null,
        buildId: String? = null,
        buildName: String? = null,
    ): PlaySession? {
        var first: Long? = null
        var last: Long? = null
        var server: String? = null
        for (line in lines) {
            val at = timestamp(line, date, zone) ?: continue
            if (first == null) first = at
            last = at
            CONNECT.find(line)?.let { server = it.groupValues[1] }
        }
        val start = first ?: return null
        var end = last ?: return null
        if (end < start) end += DAY_MILLIS
        if (end - start < MIN_SESSION_MILLIS) return null
        return PlaySession(instance, versionId, loader, start, end, server, pack, buildId, buildName)
    }

    private fun timestamp(line: String, date: LocalDate, zone: ZoneId): Long? {
        if (!line.startsWith("[")) return null
        TIME.find(line)?.let { m ->
            val (h, min, s) = m.destructured
            return time(date, h.toInt(), min.toInt(), s.toInt(), zone)
        }
        FORGE_TIME.find(line)?.let { m ->
            val (day, month, year, h, min, s) = m.destructured
            val parsed = MONTHS[month.lowercase()]?.let { runCatching { LocalDate.of(year.toInt(), it, day.toInt()) }.getOrNull() }
            return time(parsed ?: date, h.toInt(), min.toInt(), s.toInt(), zone)
        }
        return null
    }

    private fun time(date: LocalDate, h: Int, min: Int, s: Int, zone: ZoneId): Long? =
        runCatching { date.atTime(LocalTime.of(h, min, s)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()

    internal fun identify(folder: String): Pair<String, LoaderKind> {
        val suffix = folder.substringAfterLast('-', "")
        val loader = LoaderKind.entries.firstOrNull { it.isModded && it.name.lowercase() == suffix }
        return if (loader != null) folder.removeSuffix("-$suffix") to loader else folder to LoaderKind.VANILLA
    }

    private fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        val file = cacheFile ?: return
        if (!file.exists()) return
        runCatching { Json.decodeFromString<Map<String, Cached>>(file.readText()) }
            .onSuccess { cache.putAll(it) }
    }

    private fun saveCache() {
        val file = cacheFile ?: return
        runCatching { file.writeAtomically(Json.encodeToString(cache.toMap())) }
    }
}
