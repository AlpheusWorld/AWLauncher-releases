package ru.aw.launcher.mods

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.writeAtomically
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readLines

object GameOptions {

    private const val OPTIONS = "options.txt"
    private const val PACKS_KEY = "resourcePacks:"
    private const val VANILLA = "vanilla"
    private const val IRIS = "config/iris.properties"
    private const val SHADER_PACK = "shaderPack"
    private const val SHADERS_ON = "enableShaders"

    fun resourcePacks(gameDir: Path): List<String> =
        lines(gameDir.resolve(OPTIONS)).firstOrNull { it.startsWith(PACKS_KEY) }?.let(::parsePacks) ?: listOf(VANILLA)

    fun isResourcePackOn(gameDir: Path, fileName: String): Boolean = packEntry(fileName) in resourcePacks(gameDir)

    fun setResourcePack(gameDir: Path, fileName: String, enabled: Boolean) {
        val file = gameDir.resolve(OPTIONS)
        val lines = lines(file)
        val current = lines.firstOrNull { it.startsWith(PACKS_KEY) }?.let(::parsePacks) ?: listOf(VANILLA)
        val entry = packEntry(fileName)
        val next = current.filter { it != entry } + listOfNotNull(entry.takeIf { enabled })
        if (next == current) return
        val line = PACKS_KEY + Json.encodeToString(next)
        val updated = if (lines.any { it.startsWith(PACKS_KEY) }) {
            lines.map { if (it.startsWith(PACKS_KEY)) line else it }
        } else {
            lines + line
        }
        file.writeAtomically(updated.joinToString("\n", postfix = "\n"))
    }

    fun activeShader(gameDir: Path): String? {
        val properties = iris(gameDir)
        if (properties.getProperty(SHADERS_ON, "true") == "false") return null
        return properties.getProperty(SHADER_PACK)?.takeIf { it.isNotBlank() }
    }

    fun setShader(gameDir: Path, fileName: String?) {
        val properties = iris(gameDir)
        if (fileName != null) properties.setProperty(SHADER_PACK, fileName)
        properties.setProperty(SHADERS_ON, (fileName != null).toString())
        val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
        gameDir.resolve(IRIS).writeAtomically(bytes)
    }

    fun setFullscreen(gameDir: Path, enabled: Boolean) {
        val file = gameDir.resolve(OPTIONS)
        val current = if (file.exists()) file.readLines() else emptyList()
        val key = "fullscreen:"
        val value = "$key$enabled"
        val updated = if (current.any { it.startsWith(key) }) current.map { if (it.startsWith(key)) value else it } else current + value
        if (updated != current) file.writeAtomically(updated.joinToString("\n", postfix = "\n"))
    }

    internal fun parsePacks(line: String): List<String> = runCatching {
        Json.parseToJsonElement(line.removePrefix(PACKS_KEY)).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrDefault(listOf(VANILLA))

    private fun packEntry(fileName: String) = "file/$fileName"

    private fun lines(file: Path): List<String> =
        if (file.exists()) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()

    private fun iris(gameDir: Path): Properties = Properties().apply {
        val file = gameDir.resolve(IRIS)
        if (file.exists()) runCatching { Files.newInputStream(file).use(::load) }
    }
}
