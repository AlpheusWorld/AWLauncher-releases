package ru.aw.launcher.ui

import kotlinx.coroutines.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.ui.screens.RunCatalogSearch
import java.util.concurrent.atomic.AtomicInteger

class CatalogSearchTest {
    @Test fun `switching sources with the same empty query loads the new source immediately`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val modrinthRequests = AtomicInteger()
        val curseForgeRequests = AtomicInteger()
        fun search(name: String, requests: AtomicInteger) = CatalogSearch(scope) { _, _ ->
            requests.incrementAndGet()
            Modrinth.SearchPage(hits = listOf(Modrinth.SearchHit(projectId = name)), totalHits = 1)
        }
        val modrinth = search("modrinth", modrinthRequests)
        val curseForge = search("curseforge", curseForgeRequests)
        val current = mutableStateOf(modrinth)
        val scene = ImageComposeScene(width = 1, height = 1, content = { RunCatalogSearch(current.value) })
        var frame = 0L
        fun awaitResults(search: CatalogSearch) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (search.hits.isEmpty() && System.nanoTime() < deadline) {
                scene.render(frame).close()
                frame += 16_000_000L
                Thread.sleep(10)
            }
            assertEquals(1, search.hits.size)
        }
        try {
            awaitResults(modrinth)
            current.value = curseForge
            awaitResults(curseForge)
            assertEquals("curseforge", curseForge.hits.single().projectId)
            current.value = modrinth
            repeat(3) { scene.render(frame).close(); frame += 16_000_000L; Thread.sleep(10) }
            assertEquals(1, modrinthRequests.get())
            assertEquals(1, curseForgeRequests.get())
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun `page navigation replaces results and restores cached pages`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val offsets = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val search = CatalogSearch(scope) { _, offset ->
            offsets.add(offset)
            Modrinth.SearchPage(hits = listOf(Modrinth.SearchHit("item-$offset")), totalHits = 60)
        }
        suspend fun finish() { withTimeout(5000) { while (search.searching) delay(5) } }
        try {
            search.run(); finish()
            search.goToPage(1); finish()
            assertEquals(1, search.pageIndex)
            assertEquals(listOf("item-20"), search.hits.map { it.projectId })
            search.goToPage(0); finish()
            assertEquals(listOf("item-0"), search.hits.map { it.projectId })
            assertEquals(listOf(0, 20), offsets.toList())
            search.goToPage(-1)
            assertEquals(0, search.pageIndex)
        } finally { scope.cancel() }
    }

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
