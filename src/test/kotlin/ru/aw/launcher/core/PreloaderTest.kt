package ru.aw.launcher.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.aw.launcher.meta.ManifestVersion
import ru.aw.launcher.meta.VersionManifest
import java.nio.file.Files

class PreloaderTest {
    private val manifestFile = Paths.cache.resolve("version_manifest_v2.json")

    private fun withCache(text: String?, check: (PreloadResult) -> Unit) = runBlocking {
        Paths.ensureBaseDirs()
        val previous = if (Files.exists(manifestFile)) Files.readAllBytes(manifestFile) else null
        try {
            if (text == null) Files.deleteIfExists(manifestFile) else Files.writeString(manifestFile, text)
            check(Preloader.run { _, _ -> })
        } finally {
            if (previous == null) Files.deleteIfExists(manifestFile) else Files.write(manifestFile, previous)
        }
    }

    @Test
    fun `first launch returns local data and defers the manifest request`() = withCache(null) {
        assertTrue(it.manifest.versions.isEmpty())
        assertTrue(it.manifestStale)
    }

    @Test
    fun `unreadable manifest cache does not hold startup waiting for the network`() = withCache("{invalid") {
        assertTrue(it.manifest.versions.isEmpty())
        assertTrue(it.manifestStale)
    }

    @Test
    fun `a fresh saved manifest is available before the window opens`() {
        val manifest = VersionManifest(versions = listOf(ManifestVersion("1.21.1", url = "")))
        withCache(Json.encodeToString(manifest)) {
            assertEquals(manifest, it.manifest)
            assertFalse(it.manifestStale)
        }
    }
}
