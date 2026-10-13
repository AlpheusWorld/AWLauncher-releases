package ru.aw.launcher.ui

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.aw.launcher.mods.Modrinth
import java.util.concurrent.atomic.AtomicInteger

class CatalogSearchTest {
    @Test fun `switching back to a previous query restores its pages immediately`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val count = AtomicInteger()
        val search = CatalogSearch(scope) { query, offset ->
            count.incrementAndGet()
            Modrinth.SearchPage(hits = listOf(Modrinth.SearchHit(projectId = "$query-$offset")), totalHits = 2)
        }
        suspend fun finish() { withTimeout(5000) { while (search.searching) delay(5) } }
        try {
            search.query = "first"; search.run(); finish(); search.run(more = true); finish()
            search.query = "second"; search.run(); finish()
            search.query = "first"; search.run()
            assertEquals(listOf("first-0", "first-1"), search.hits.map { it.projectId })
            assertEquals(3, count.get())
            search.run(force = true); finish(); assertEquals(4, count.get())
        } finally { scope.cancel() }
    }

    @Test fun `returning to a cached query cancels the other pending search`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val waiting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val search = CatalogSearch(scope) { query, _ ->
            if (query == "slow") { waiting.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            Modrinth.SearchPage(hits = listOf(Modrinth.SearchHit(query)), totalHits = 1)
        }
        try {
            search.query = "cached"; search.run()
            withTimeout(5000) { while (search.searching) delay(5) }
            search.query = "slow"; search.run()
            assertEquals(true, waiting.await(5, java.util.concurrent.TimeUnit.SECONDS))
            search.query = "cached"; search.run(); release.countDown(); delay(100)
            assertEquals("cached", search.hits.single().projectId)
            assertEquals(false, search.searching)
        } finally { release.countDown(); scope.cancel() }
    }
    @Test
    fun `returning to a tab reuses search results and refresh can be forced`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val count = AtomicInteger()
        val search = CatalogSearch(scope) { query, _ ->
            count.incrementAndGet()
            Modrinth.SearchPage(hits = listOf(Modrinth.SearchHit(projectId = query, title = query)), totalHits = 1)
        }
        suspend fun finish() { withTimeout(5000) { while (search.searching) delay(5) } }
        try {
            search.query = "pack"
            search.run()
            finish()
            search.run()
            finish()
            assertEquals(1, count.get())
            search.run(force = true)
            finish()
            assertEquals(2, count.get())
            search.query = "other"
            search.run()
            finish()
            assertEquals("other", search.hits.single().projectId)
            assertEquals(3, count.get())
        } finally { scope.cancel() }
    }
}
