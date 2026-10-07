package ru.aw.launcher.mods

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Json
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class ModCompatTest {

    private val sodiumBeta = ModMeta(
        "sodium", "Sodium", "0.8.15-beta.1+mc1.21.11",
        breaks = mapOf("iris" to listOf("<=1.10.7"), "fabric-api" to listOf("<0.140.0")),
    )
    private val iris = ModMeta("iris", "Iris", "1.10.7+mc1.21.11")
    private val fabricApi = ModMeta("fabric-api", "Fabric API", "0.141.6+1.21.11")

    @Test
    fun `sodium beta breaks the latest iris and says so plainly`() {
        val conflicts = ModCompat.conflicts(sodiumBeta, listOf(iris, fabricApi))
        assertEquals(listOf(Conflict(sodiumBeta, iris)), conflicts)
        assertEquals("Sodium 0.8.15-beta.1 несовместим с Iris 1.10.7", conflicts.single().text)
    }

    @Test
    fun `the check works in both directions`() {
        val newIris = ModMeta("iris", "Iris", "1.11.0+mc1.21.11", breaks = mapOf("sodium" to listOf("<0.8.15")))
        val sodium = ModMeta("sodium", "Sodium", "0.8.14+mc1.21.11")
        assertEquals(1, ModCompat.conflicts(newIris, listOf(sodium)).size)
        assertEquals(1, ModCompat.conflicts(iris, listOf(sodiumBeta)).size)
        assertTrue(ModCompat.conflicts(ModMeta("iris", "Iris", "1.10.8+mc1.21.11"), listOf(sodiumBeta)).isEmpty())
    }

    @Test
    fun `provided ids count as the mod itself`() {
        val fork = ModMeta("rubidium", "Rubidium", "0.8.0", provides = setOf("embeddium"))
        val sodium = ModMeta("sodium", "Sodium", "0.8.14", breaks = mapOf("embeddium" to listOf("*")))
        assertEquals(1, ModCompat.conflicts(sodium, listOf(fork)).size)
    }

    @Test
    fun `fabric version predicates`() {
        assertTrue(FabricVersion.satisfies("1.10.7+mc1.21.11", "<=1.10.7"))
        assertFalse(FabricVersion.satisfies("1.10.8", "<=1.10.7"))
        assertTrue(FabricVersion.satisfies("1.2.0-beta.2", "<1.2.0"))
        assertTrue(FabricVersion.satisfies("1.6.0-beta.1", "<1.6.0-beta.2"))
        assertFalse(FabricVersion.satisfies("1.6.0-beta.10", "<1.6.0-beta.2"))
        assertTrue(FabricVersion.satisfies("0.139.0+1.21.11", ">=0.130 <0.140"))
        assertFalse(FabricVersion.satisfies("0.141.6+1.21.11", ">=0.130 <0.140"))
        assertTrue(FabricVersion.satisfies("1.2.9", "~1.2.3"))
        assertFalse(FabricVersion.satisfies("1.3.0", "~1.2.3"))
        assertTrue(FabricVersion.satisfies("1.9.0", "^1.2.3"))
        assertFalse(FabricVersion.satisfies("2.0.0", "^1.2.3"))
        assertTrue(FabricVersion.satisfies("1.21.4", "1.21.x"))
        assertFalse(FabricVersion.satisfies("1.22.0", "1.21.x"))
        assertTrue(FabricVersion.satisfies("anything", "*"))
        assertTrue(FabricVersion.satisfies("1.21-4.2", "<=1.21-4.5"))
        assertTrue(FabricVersion.satisfies("2.0.0", listOf("<1.0", "2.0.0")))
        assertFalse(FabricVersion.satisfies("build-7", "<2.0"))
        assertTrue(FabricVersion.satisfies("build-7", "build-7"))
    }

    @Test
    fun `reads fabric mod json with provides and array ranges`() {
        val json = Json.parseToJsonElement(
            """{"schemaVersion":1,"id":"demo","version":"1.0.0","name":"Demo",
               "provides":["demo-api"],"breaks":{"iris":["<1.0","2.0.0"],"canvas":"*"}}"""
        ).jsonObject
        val meta = ModCompat.parse(json)!!
        assertEquals(emptySet<String>(), meta.depends)
        assertEquals(setOf("sodium", "fabricloader"), ModCompat.parse(
            Json.parseToJsonElement("""{"id":"iris","version":"1","depends":{"sodium":">=0.8","fabricloader":"*"}}""").jsonObject,
        )!!.depends)
        assertEquals("Demo", meta.name)
        assertEquals(setOf("demo-api"), meta.provides)
        assertEquals(listOf("<1.0", "2.0.0"), meta.breaks["iris"])
        assertEquals(listOf("*"), meta.breaks["canvas"])
        assertNull(ModCompat.parse(Json.parseToJsonElement("""{"version":"1"}""").jsonObject))
    }

    @Test
    fun `reads jars and skips the ones without fabric metadata`(@TempDir dir: Path) {
        jar(dir.resolve("sodium.jar"), """{"id":"sodium","name":"Sodium","version":"0.8.15-beta.1+mc1.21.11","breaks":{"iris":"<=1.10.7"}}""")
        jar(dir.resolve("iris.jar"), """{"id":"iris","name":"Iris","version":"1.10.7+mc1.21.11"}""")
        jar(dir.resolve("forge-mod.jar"), null)
        dir.resolve("broken.jar").outputStream().use { it.write("not a zip".toByteArray()) }

        val all = ModCompat.enabledIn(dir)
        assertEquals(setOf("sodium", "iris"), all.map { it.id }.toSet())
        val sodium = ModCompat.read(dir.resolve("sodium.jar"))!!
        val others = ModCompat.enabledIn(dir, except = setOf(dir.resolve("sodium.jar")))
        assertEquals("Iris", ModCompat.conflicts(sodium, others).single().other.name)
    }

    private fun jar(path: Path, fabricModJson: String?) {
        ZipOutputStream(path.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(if (fabricModJson != null) "fabric.mod.json" else "META-INF/mods.toml"))
            zip.write((fabricModJson ?: "modLoader=\"javafml\"").toByteArray())
            zip.closeEntry()
        }
    }
}
