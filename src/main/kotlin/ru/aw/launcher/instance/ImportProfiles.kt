package ru.aw.launcher.instance

import kotlinx.serialization.json.*
import org.sqlite.SQLiteConfig
import ru.aw.launcher.core.Json
import ru.aw.launcher.meta.LoaderKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.*

data class ImportProfile(
    val source: Path,
    val gameDir: Path,
    val name: String,
    val versionId: String?,
    val loader: LoaderKind = LoaderKind.VANILLA,
    val loaderVersion: String? = null,
    val playTimeMillis: Long = 0,
    val lastPlayed: Long? = null,
    val icon: Path? = null,
    val launcher: String = "Minecraft",
    val launchCount: Long = 0,
)

/** Reads launcher metadata without modifying the source or importing account credentials. */
object ImportProfiles {
    private val markers = listOf("mods", "saves", "options.txt", "servers.dat", "resourcepacks", "logs")

    fun discover(source: Path): List<ImportProfile> {
        val root = source.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Выбери папку профиля или папку лаунчера")
        val ancestors = generateSequence(root) { it.parent }.take(4).toList()
        ancestors.firstOrNull { it.resolve("app.db").isRegularFile() }?.let { app ->
            val records = modrinthDatabase(app).filter { profile ->
                profile.gameDir.startsWith(root) || root == profile.gameDir || root == profile.source
            }
            if (records.isNotEmpty()) return records.sortedBy { it.name.lowercase() }
        }
        val selected = if (root.fileName.toString() in listOf(".minecraft", "minecraft") && hasMetadata(root.parent)) root.parent else root
        if (hasMetadata(selected) || markers.any { selected.resolve(it).exists() }) return listOf(detect(selected))
        val containers = listOf(root.resolve("instances"), root.resolve("profiles"), root).filter { it.isDirectory() }
        val profiles = containers.flatMap { container ->
            Files.list(container).use { stream -> stream.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.toList() }
        }.distinct().filter { candidate ->
            hasMetadata(candidate) || listOf(candidate, candidate.resolve(".minecraft"), candidate.resolve("minecraft"))
                .any { dir -> markers.any { dir.resolve(it).exists() } }
        }.map(::detect)
        if (profiles.isEmpty()) throw IOException("В выбранной папке не найдены профили Minecraft")
        return profiles.sortedBy { it.name.lowercase() }
    }

    private fun hasMetadata(dir: Path?): Boolean = dir != null && listOf(
        "instance.cfg", "mmc-pack.json", "profile.json", "minecraftinstance.json", "instance.json", "manifest.json",
    ).any { dir.resolve(it).isRegularFile() }

    fun detect(source: Path): ImportProfile {
        val game = listOf(source.resolve(".minecraft"), source.resolve("minecraft"), source)
            .firstOrNull { Files.isDirectory(it, NOFOLLOW_LINKS) } ?: throw IOException("Папка игры не найдена")
        val cfg = source.resolve("instance.cfg").takeIf { it.isRegularFile() }?.let { file ->
            file.readLines().filter { '=' in it && !it.trimStart().startsWith('#') }.associate {
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            }
        }.orEmpty()
        val prism = json(source.resolve("mmc-pack.json"))
        val components = prism?.get("components") as? JsonArray
        val minecraft = components?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("uid") == "net.minecraft" }
        val modLoader = components?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("uid") in loaderUids }
        val profile = json(source.resolve("profile.json"))
        val modrinth = (profile?.get("metadata") as? JsonObject) ?: profile
        val curse = json(source.resolve("minecraftinstance.json")) ?: json(source.resolve("manifest.json"))
        val curseMinecraft = curse?.get("minecraft") as? JsonObject
        val curseLoader = curse?.get("baseModLoader") as? JsonObject
        val manifestLoader = (curseMinecraft?.get("modLoaders") as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.let { list -> list.firstOrNull { it["primary"]?.jsonPrimitive?.booleanOrNull == true } ?: list.firstOrNull() }?.text("id")
        val at = json(source.resolve("instance.json"))
        val atLauncher = at?.get("launcher") as? JsonObject
        val atLoader = atLauncher?.get("loaderVersion") as? JsonObject
        val aw = ru.aw.launcher.packs.Modpacks.read(game)
        var loader = modLoader?.text("uid")?.let(loaderUids::get)
            ?: loader(modrinth?.text("loader") ?: curseLoader?.text("name") ?: manifestLoader ?: atLoader?.text("type"))
        var loaderVersion = modLoader?.text("version") ?: modrinth?.text("loader_version")
            ?: (modrinth?.get("loader_version") as? JsonObject)?.text("id") ?: curseLoader?.text("forgeVersion")
            ?: manifestLoader?.substringAfter('-', "")?.takeIf { it.isNotBlank() }
            ?: atLoader?.text("version")
        var version = minecraft?.text("version") ?: modrinth?.text("game_version") ?: curse?.text("minecraftVersion")
            ?: curseLoader?.text("minecraftVersion") ?: curseMinecraft?.text("version") ?: at?.text("inheritsFrom") ?: at?.text("id") ?: aw?.gameVersion
        if (aw != null) { loader = aw.loader; loaderVersion = aw.loaderVersion }
        if (version == null) {
            val log = game.resolve("logs/latest.log")
            if (log.isRegularFile()) Files.newBufferedReader(log).use { reader ->
                val line = reader.lineSequence().take(250).mapNotNull { FABRIC_LOG.find(it) }.firstOrNull()
                line?.let {
                    version = it.groupValues[1]
                    loader = loader(it.groupValues[2])
                    loaderVersion = it.groupValues[3]
                }
            }
        }
        val name = cfg["name"] ?: modrinth?.text("name") ?: curse?.text("name") ?: atLauncher?.text("name") ?: aw?.title ?: source.name
        val last = cfg["lastLaunchTime"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: modrinth?.get("last_played")?.let(::timestamp)
            ?: atLauncher?.get("lastPlayed")?.let(::timestamp)
        val time = cfg["totalTimePlayed"]?.toLongOrNull()?.let(::seconds)
            ?: seconds((modrinth?.number("submitted_time_played") ?: 0) + (modrinth?.number("recent_time_played") ?: 0))
        val launcher = when {
            prism != null -> "Prism / MultiMC"
            profile != null -> "Modrinth"
            curse != null -> "CurseForge"
            at != null -> "ATLauncher"
            aw != null -> "AWLauncher"
            else -> "Minecraft"
        }
        val icon = listOf(source.resolve("icon.png"), source.resolve("instance.png"), game.resolve("icon.png"))
            .firstOrNull { it.isRegularFile() }
        return ImportProfile(source, game, name.take(48), version, loader, loaderVersion, time, last, icon, launcher,
            launchCount = (atLauncher?.number("numPlays") ?: 0).coerceAtLeast(0))
    }

    private fun modrinthDatabase(app: Path): List<ImportProfile> {
        val config = SQLiteConfig().apply { setReadOnly(true); setBusyTimeout(3000) }
        DriverManager.getConnection("jdbc:sqlite:${app.resolve("app.db")}", config.toProperties()).use { db ->
            val tables = db.createStatement().use { stmt -> stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            } }
            val query = when {
                "instances" in tables && "instance_content_sets" in tables -> """
                    SELECT i.name, i.path, i.icon_path, i.last_played, i.submitted_time_played, i.recent_time_played,
                        c.game_version, c.loader, c.loader_version
                    FROM instances i JOIN instance_content_sets c ON c.id=i.applied_content_set_id
                """.trimIndent()
                "profiles" in tables -> {
                    val columns = db.createStatement().use { stmt -> stmt.executeQuery("PRAGMA table_info(profiles)").use { rows ->
                        buildSet { while (rows.next()) add(rows.getString("name")) }
                    } }
                    fun field(names: List<String>, alias: String, fallback: String = "NULL"): String =
                        "${names.firstOrNull { it in columns } ?: fallback} AS $alias"
                    "SELECT " + listOf(
                        field(listOf("name"), "name", "''"), field(listOf("path", "id"), "path"),
                        field(listOf("icon_path"), "icon_path"), field(listOf("last_played"), "last_played"),
                        field(listOf("submitted_time_played"), "submitted_time_played", "0"),
                        field(listOf("recent_time_played"), "recent_time_played", "0"),
                        field(listOf("game_version"), "game_version"), field(listOf("loader", "mod_loader"), "loader"),
                        field(listOf("loader_version", "mod_loader_version"), "loader_version"),
                    ).joinToString(", ") + " FROM profiles"
                }
                else -> throw IOException("Не удалось распознать базу профилей Modrinth")
            }
            return db.createStatement().use { stmt -> stmt.executeQuery(query).use { rows -> buildList {
                while (rows.next()) {
                    val path = rows.getString("path") ?: continue
                    val game = app.resolve("profiles").resolve(path).normalize()
                    if (!game.startsWith(app.resolve("profiles")) || !Files.isDirectory(game, NOFOLLOW_LINKS)) continue
                    val icon = rows.getString("icon_path")?.let { value -> runCatching { Path.of(value) }.getOrNull() }
                        ?.let { if (it.isAbsolute) it else app.resolve(it) }?.takeIf { it.isRegularFile() }
                    val last = rows.getLong("last_played").takeIf { !rows.wasNull() && it > 0 }?.let(::seconds)
                    add(ImportProfile(game, game, rows.getString("name").take(48), rows.getString("game_version"),
                        loader(rows.getString("loader")), rows.getString("loader_version"),
                        seconds(rows.getLong("submitted_time_played") + rows.getLong("recent_time_played")), last, icon, "Modrinth"))
                }
            } } }
        }
    }

    private fun json(path: Path): JsonObject? {
        if (!path.isRegularFile()) return null
        if (path.fileSize() > 2 * 1024 * 1024) throw IOException("Слишком большой файл профиля: ${path.name}")
        return try { Json.parseToJsonElement(path.readText()) as? JsonObject }
        catch (e: Exception) { throw IOException("Не удалось прочитать ${path.name}", e) }
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
    private fun timestamp(value: JsonElement): Long? = (value as? JsonPrimitive)?.let { primitive ->
        primitive.longOrNull?.let { if (it < 100_000_000_000L) seconds(it) else it }
            ?: primitive.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
    private fun seconds(value: Long): Long = value.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
    private fun loader(value: String?): LoaderKind {
        val name = value.orEmpty().lowercase()
        return LoaderKind.entries.firstOrNull { name.startsWith(it.name.lowercase()) } ?: LoaderKind.VANILLA
    }
    private val loaderUids = mapOf("net.fabricmc.fabric-loader" to LoaderKind.FABRIC, "org.quiltmc.quilt-loader" to LoaderKind.QUILT,
        "net.minecraftforge" to LoaderKind.FORGE, "net.neoforged" to LoaderKind.NEOFORGE)
    private val FABRIC_LOG = Regex("Loading Minecraft (\\S+) with (Fabric|Quilt) Loader (\\S+)", RegexOption.IGNORE_CASE)
}
