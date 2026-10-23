package ru.aw.launcher.mods

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import ru.aw.launcher.core.Json
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

data class ModMeta(
    val id: String,
    val name: String,
    val version: String,
    val provides: Set<String> = emptySet(),
    val breaks: Map<String, List<String>> = emptyMap(),
    val depends: Set<String> = emptySet(),
    val dependencyVersions: Map<String, List<String>> = depends.associateWith { listOf("*") },
) {
    val shortVersion: String get() = version.substringBefore('+')
}

data class Conflict(val mod: ModMeta, val other: ModMeta) {
    val text: String get() = "${mod.name} ${mod.shortVersion} несовместим с ${other.name} ${other.shortVersion}"
}

class IncompatibleModException(val conflicts: List<Conflict>, outcome: String) :
    IOException(conflicts.take(2).joinToString("; ") { it.text } + " — " + outcome)

internal data class MissingDependency(val mod: ModMeta, val id: String, val ranges: List<String>, val installed: String?) {
    val text: String get() = "${mod.name} требует $id ${ranges.joinToString(" или ")}: " +
        (installed?.let { "установлена версия $it" } ?: "мод не установлен или выключен")
}

object ModCompat {

    /** Fabric modules may be bundled as nested jars instead of separate files in mods/. */
    internal fun readAll(jar: Path): List<ModMeta> {
        var expanded = 0L
        var count = 0
        fun read(input: java.io.InputStream, limit: Int): ByteArray {
            val bytes = input.use { it.readNBytes(limit + 1) }
            expanded += bytes.size
            if (bytes.size > limit || expanded > 128L * 1024 * 1024) throw IOException("Вложенные файлы мода слишком большие")
            return bytes
        }
        fun parseJar(entry: (String) -> ByteArray?, depth: Int): List<ModMeta> {
            if (depth > 8 || ++count > 512) throw IOException("В моде слишком много вложенных зависимостей")
            val document = entry("fabric.mod.json") ?: return emptyList()
            val json = Json.parseToJsonElement(document.decodeToString()).jsonObject
            val environment = json["environment"]
            if (environment is JsonPrimitive && environment.contentOrNull == "server") return emptyList()
            if (environment is JsonArray && environment.none { (it as? JsonPrimitive)?.contentOrNull in setOf("*", "client") }) return emptyList()
            val meta = parse(json) ?: throw IOException("В описании мода отсутствуют ID или версия")
            val result = arrayListOf(meta)
            for (nested in (json["jars"] as? JsonArray).orEmpty()) {
                val name = (nested as? JsonObject)?.text("file") ?: throw IOException("Некорректное описание вложенного мода")
                if (name.startsWith('/') || '\\' in name || ':' in name || name.split('/').any { it == ".." || it.isEmpty() })
                    throw IOException("Некорректный путь вложенного мода")
                val bytes = entry(name) ?: throw IOException("В моде отсутствует вложенный файл $name")
                val entries = HashMap<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var seen = 0
                    while (true) {
                        val child = zip.nextEntry ?: break
                        if (++seen > 50_000) throw IOException("Во вложенном моде слишком много файлов")
                        if (child.name == "fabric.mod.json" || child.name.endsWith(".jar")) {
                            val limit = if (child.name == "fabric.mod.json") 1024 * 1024 else 64 * 1024 * 1024
                            val value = zip.readNBytes(limit + 1)
                            expanded += value.size
                            if (value.size > limit || expanded > 128L * 1024 * 1024) throw IOException("Вложенные файлы мода слишком большие")
                            entries[child.name] = value
                        } else {
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = zip.read(buffer)
                                if (size < 0) break
                                expanded += size
                                if (expanded > 128L * 1024 * 1024) throw IOException("Вложенные файлы мода слишком большие")
                            }
                        }
                        zip.closeEntry()
                    }
                }
                result += parseJar(entries::get, depth + 1)
            }
            return result
        }
        return try {
            ZipFile(jar.toFile()).use { zip -> parseJar({ name -> zip.getEntry(name)?.let { entry ->
                read(zip.getInputStream(entry), if (name == "fabric.mod.json") 1024 * 1024 else 64 * 1024 * 1024)
            } }, 0) }
        } catch (e: java.util.zip.ZipException) {
            throw IOException("Повреждён файл мода ${jar.fileName}", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IOException("Некорректное описание зависимостей в моде ${jar.fileName}", e)
        }
    }

    internal fun missingDependencies(mods: List<ModMeta>): List<MissingDependency> = mods.flatMap { mod ->
        mod.dependencyVersions.mapNotNull { (id, ranges) ->
            // These components are supplied by the selected game/runtime, not downloaded as mods.
            if (id in setOf("minecraft", "java", "fabricloader", "quilt_loader")) return@mapNotNull null
            val available = mods.filter { it.id == id || id in it.provides }
            if (available.any { FabricVersion.satisfies(it.version, ranges) }) null
            else MissingDependency(mod, id, ranges, available.firstOrNull()?.version)
        }
    }

    fun read(jar: Path): ModMeta? = runCatching {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry("fabric.mod.json") ?: return null
            parse(Json.parseToJsonElement(zip.getInputStream(entry).readBytes().decodeToString()).jsonObject)
        }
    }.getOrNull()

    fun enabledIn(dir: Path, except: Set<Path> = emptySet()): List<ModMeta> {
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries("*.jar").filter { it !in except }.mapNotNull(::read)
    }

    internal fun parse(root: JsonObject): ModMeta? {
        val id = root.text("id") ?: return null
        val version = root.text("version") ?: return null
        val provides = (root["provides"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .toSet()
        val breaks = (root["breaks"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
        }
        val dependencies = (root["depends"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
        }
        return ModMeta(id, root.text("name") ?: id, version, provides, breaks, dependencies.keys, dependencies)
    }

    fun conflictsAmong(mods: List<ModMeta>): List<Conflict> =
        mods.indices.flatMap { i -> conflicts(mods[i], mods.subList(i + 1, mods.size)) }

    fun conflictsIn(dir: Path): List<Conflict> = conflictsAmong(enabledIn(dir))

    fun conflicts(candidate: ModMeta, others: Collection<ModMeta>): List<Conflict> =
        others.filter { it.id != candidate.id }.mapNotNull { other ->
            when {
                breaks(candidate, other) -> Conflict(candidate, other)
                breaks(other, candidate) -> Conflict(other, candidate)
                else -> null
            }
        }

    private fun breaks(mod: ModMeta, target: ModMeta): Boolean =
        (target.provides + target.id).any { id ->
            mod.breaks[id]?.let { FabricVersion.satisfies(target.version, it) } == true
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

internal object FabricVersion {

    private class Parsed(val core: List<Int>, val pre: List<String>)

    private val OPERATORS = listOf(">=", "<=", ">", "<", "=", "~", "^")
    private val WHITESPACE = Regex("\\s+")
    private val WILDCARDS = setOf("x", "X", "*")

    fun satisfies(version: String, predicates: List<String>): Boolean = predicates.any { satisfies(version, it) }

    fun satisfies(version: String, predicate: String): Boolean {
        val terms = predicate.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val parsed = parse(version) ?: return terms.all { it == "*" || it == version || it == "=$version" }
        return terms.all { term(parsed, it) }
    }

    private fun term(version: Parsed, term: String): Boolean {
        if (term == "*") return true
        val operator = OPERATORS.firstOrNull { term.startsWith(it) }.orEmpty()
        val raw = term.removePrefix(operator)
        val parts = raw.split('.')
        val wildcard = parts.indexOfFirst { it in WILDCARDS }
        if (wildcard == 0) return true
        if (wildcard > 0) {
            val prefix = parts.take(wildcard).map { it.toIntOrNull() ?: return false }
            return (0 until wildcard).all { version.core.getOrElse(it) { 0 } == prefix[it] }
        }
        val target = parse(raw) ?: return false
        val order = compare(version, target)
        return when (operator) {
            ">=" -> order >= 0
            "<=" -> order <= 0
            ">" -> order > 0
            "<" -> order < 0
            "~" -> order >= 0 && sameComponents(version, target, minOf(2, target.core.size))
            "^" -> order >= 0 && sameComponents(version, target, (target.core.indexOfFirst { it != 0 }.takeIf { it >= 0 }
                ?: target.core.lastIndex) + 1)
            else -> order == 0
        }
    }

    private fun sameComponents(a: Parsed, b: Parsed, count: Int): Boolean =
        (0 until count).all { a.core.getOrElse(it) { 0 } == b.core.getOrElse(it) { 0 } }

    private fun parse(text: String): Parsed? {
        val release = text.trim().substringBefore('+')
        val core = release.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
        val pre = if ('-' in release) release.substringAfter('-').split('.') else emptyList()
        return Parsed(core, pre)
    }

    private fun compare(a: Parsed, b: Parsed): Int {
        for (i in 0 until maxOf(a.core.size, b.core.size)) {
            val order = a.core.getOrElse(i) { 0 }.compareTo(b.core.getOrElse(i) { 0 })
            if (order != 0) return order
        }
        if (a.pre.isEmpty() || b.pre.isEmpty()) return b.pre.size.coerceAtMost(1) - a.pre.size.coerceAtMost(1)
        for (i in 0 until minOf(a.pre.size, b.pre.size)) {
            val x = a.pre[i]
            val y = b.pre[i]
            val xn = x.toLongOrNull()
            val yn = y.toLongOrNull()
            val order = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (order != 0) return order
        }
        return a.pre.size.compareTo(b.pre.size)
    }
}
