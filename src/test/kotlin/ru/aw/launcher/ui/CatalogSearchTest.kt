package ru.aw.launcher.ui

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.aw.launcher.mods.Modrinth
import java.util.concurrent.atomic.AtomicInteger

class CatalogSearchTest {
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
