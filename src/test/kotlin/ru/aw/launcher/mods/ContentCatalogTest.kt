package ru.aw.launcher.mods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ContentCatalogTest {
    @Test fun `migration batches hashes and deduplicates CurseForge projects`() = runBlocking {
        val current = (0..500).associate { "$it" to Modrinth.Version("old", "mr-$it") } +
            mapOf("cf-first" to Modrinth.Version("old", "cf:51"), "cf-second" to Modrinth.Version("old", "cf:51"))
        val batches = ConcurrentHashMap.newKeySet<Int>()
        val calls = AtomicInteger()
        val result = ContentCatalog.updates(current, listOf("fabric"), "26.3",
            modrinth = { hashes, loaders, game ->
                assertEquals(listOf("fabric"), loaders); assertEquals("26.3", game)
                assertTrue(hashes.none { it.startsWith("cf-") }); batches += hashes.size
                hashes.associateWith { Modrinth.Version("new", "mr-$it") }
            }, curseforge = { id, _, _ -> calls.incrementAndGet(); Modrinth.Version("cf-new", id) })
        assertEquals(setOf(500, 1), batches)
        assertEquals(1, calls.get())
        assertEquals(503, result.versions.size)
        assertEquals("cf:51", result.versions.getValue("cf-first").projectId)
        assertEquals(result.versions["cf-first"], result.versions["cf-second"])
        assertTrue(result.failures.isEmpty())
    }

    @Test fun `one unavailable provider does not discard compatible files from the other`() = runBlocking {
        val current = mapOf("mr" to Modrinth.Version("old", "mod"), "cf" to Modrinth.Version("old", "cf:51"))
        val result = ContentCatalog.updates(current, listOf("fabric"), "26.3",
            modrinth = { _, _, _ -> throw IOException("offline") },
            curseforge = { id, _, _ -> Modrinth.Version("new", id) })
        assertEquals(setOf("cf"), result.versions.keys)
        assertEquals(setOf("mr"), result.failures.keys)
        assertTrue(result.failures.getValue("mr").contains("Modrinth"))
    }

    @Test fun `cancellation is propagated instead of reported as incompatible mods`() {
        assertThrows<CancellationException> { runBlocking {
            ContentCatalog.updates(mapOf("cf" to Modrinth.Version("old", "cf:51")), listOf("fabric"), "26.3",
                curseforge = { _, _, _ -> throw CancellationException("cancel") })
        } }
    }
}
