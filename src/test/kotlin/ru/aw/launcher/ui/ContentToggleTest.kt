package ru.aw.launcher.ui

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.meta.LoaderKind
import ru.aw.launcher.mods.*
import java.nio.file.Files
import java.nio.file.Path

class ContentToggleTest {
    @TempDir lateinit var root: Path
    private fun model(scope: CoroutineScope, changed: () -> Unit = {}) = ContentModel(root, LoaderKind.FABRIC, "1.21.1", scope, changed, CatalogSource.MODRINTH)
    private fun mod(name: String, title: String) = InstalledItem(
        Files.writeString(Files.createDirectories(root.resolve("mods")).resolve(name), "fixture"),
        ContentKind.MOD, true, "sha-$name", "project-$name", title, "1.0", "https://example.invalid/icon.png", null, "version-$name",
    )
    private suspend fun idle(model: ContentModel) = withTimeout(5_000) { while (model.working.isNotEmpty()) delay(10) }

    @Test fun `toggle responds immediately and preserves metadata and row after rename`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = mod("zzz.jar", "Alpha")
        val other = mod("aaa.jar", "Beta")
        var changed = 0
        val model = model(scope) { changed++ }
        model.installed = mapOf(ContentKind.MOD to listOf(first, other)); model.scanned = true
        try {
            model.toggle(first, false)
            assertFalse(model.installed.getValue(ContentKind.MOD).first().enabled, "Visual state must change before waiting for I/O")
            idle(model)
            val disabled = model.installed.getValue(ContentKind.MOD).first()
            assertEquals(first.copy(file = first.file.resolveSibling("zzz.jar.disabled"), enabled = false), disabled)
            assertEquals(listOf("Alpha", "Beta"), model.installed.getValue(ContentKind.MOD).map { it.title })
            assertEquals(first.rowKey, disabled.rowKey)
            assertTrue(Files.exists(disabled.file)); assertFalse(Files.exists(first.file))
            assertFalse(model.refreshing, "A toggle must not start catalog recognition or update lookup")
            model.toggle(disabled, true); idle(model)
            assertEquals(first, model.installed.getValue(ContentKind.MOD).first())
            assertEquals(2, changed); assertNull(model.error)
        } finally { scope.cancel() }
    }

    @Test fun `failed toggle restores the previous visible state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val item = mod("gone.jar", "Missing")
        val model = model(scope)
        model.installed = mapOf(ContentKind.MOD to listOf(item)); model.scanned = true
        Files.delete(item.file)
        try {
            model.toggle(item, false); idle(model)
            assertEquals(item, model.installed.getValue(ContentKind.MOD).single())
            assertNotNull(model.error); assertFalse(model.refreshing)
        } finally { scope.cancel() }
    }

    @Test fun `bulk toggle keeps order and identities without scanning`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val items = listOf(mod("last.jar", "First"), mod("first.jar", "Second"))
        val model = model(scope)
        model.installed = mapOf(ContentKind.MOD to items); model.scanned = true
        try {
            model.toggleMany(items, false); idle(model)
            val rows = model.installed.getValue(ContentKind.MOD)
            assertEquals(items.map { it.rowKey }, rows.map { it.rowKey })
            assertEquals(items.map { it.title }, rows.map { it.title })
            assertTrue(rows.none { it.enabled }); assertTrue(rows.all { Files.exists(it.file) })
            assertFalse(model.refreshing)
        } finally { scope.cancel() }
    }

    @Test fun `enabling a shader also updates the previously active shader`() = runBlocking {
        val folder = Files.createDirectories(root.resolve("shaderpacks"))
        val first = InstalledItem(Files.writeString(folder.resolve("one.zip"), "one"), ContentKind.SHADER, true, "", null, "One", "", null, null)
        val second = first.copy(file = Files.writeString(folder.resolve("two.zip"), "two"), enabled = false, title = "Two", rowKey = "two")
        GameOptions.setShader(root, first.fileName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = model(scope)
        model.installed = mapOf(ContentKind.SHADER to listOf(first, second)); model.scanned = true
        try {
            model.toggle(second, true); idle(model)
            assertEquals(listOf(false, true), model.installed.getValue(ContentKind.SHADER).map { it.enabled })
            assertEquals(second.fileName, GameOptions.activeShader(root))
        } finally { scope.cancel() }
    }
}
