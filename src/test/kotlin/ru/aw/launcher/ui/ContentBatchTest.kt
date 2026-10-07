package ru.aw.launcher.ui

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.*
import java.nio.file.Files
import java.nio.file.Path

class ContentBatchTest {
    @TempDir lateinit var dir: Path

    private fun item(name: String): InstalledItem {
        val mods = Files.createDirectories(dir.resolve("mods"))
        val file = Files.writeString(mods.resolve(name), "test mod")
        return InstalledItem(file, ContentKind.MOD, true, "", null, name, "", null, null)
    }

    @Test
    fun `bulk toggles and removal include all selected mods`() = runBlocking {
        val normal = item("normal.jar")
        val second = item("second.jar")
        suspend fun run(action: (ContentModel) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val model = ContentModel(dir, LoaderKind.FABRIC, "1.21.1", scope, { scope.cancel() }, CatalogSource.MODRINTH)
            try { action(model); withTimeout(5000) { while (model.working.isNotEmpty()) delay(10) } }
            finally { scope.cancel() }
        }
        run { it.toggleMany(listOf(normal, second), false) }
        assertTrue(Files.exists(dir.resolve("mods/normal.jar.disabled")))
        assertTrue(Files.exists(dir.resolve("mods/second.jar.disabled")))
        val disabled = normal.copy(file = dir.resolve("mods/normal.jar.disabled"), enabled = false)
        val secondDisabled = second.copy(file = dir.resolve("mods/second.jar.disabled"), enabled = false)
        run { it.removeMany(listOf(disabled, secondDisabled)) }
        assertFalse(Files.exists(disabled.file))
        assertFalse(Files.exists(secondDisabled.file))
    }

    @Test
    fun `batch file import preflights collisions instead of partially copying`() = runBlocking {
        val incoming = Files.createDirectories(dir.resolve("incoming"))
        val first = Files.writeString(incoming.resolve("first.jar"), "first")
        val second = Files.writeString(incoming.resolve("second.jar"), "second")
        Files.createDirectories(dir.resolve("mods"))
        Files.writeString(dir.resolve("mods/second.jar"), "existing")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = ContentModel(dir, LoaderKind.FABRIC, "1.21.1", scope, { scope.cancel() }, CatalogSource.MODRINTH)
        try {
            model.addFiles(listOf(first, second), ContentKind.MOD)
            withTimeout(5000) { while (model.working.isNotEmpty()) delay(10) }
            assertFalse(Files.exists(dir.resolve("mods/first.jar")))
            assertEquals("existing", Files.readString(dir.resolve("mods/second.jar")))
            assertNotNull(model.error)
        } finally { scope.cancel() }
    }
}
