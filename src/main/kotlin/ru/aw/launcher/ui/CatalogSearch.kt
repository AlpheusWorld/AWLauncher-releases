package ru.aw.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.aw.launcher.mods.Modrinth
import ru.aw.launcher.net.Http

class CatalogSearch(
    private val scope: CoroutineScope,
    private val fetch: (query: String, offset: Int) -> Modrinth.SearchPage,
) {
    var query by mutableStateOf("")
    var sort by mutableStateOf("downloads")
    var sortedFetch: ((String, Int, String) -> Modrinth.SearchPage)? = null
    var hits by mutableStateOf<List<Modrinth.SearchHit>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private var completedQuery: String? = null
    private var completedAt = 0L
    private var requestToken: Any? = null
    private data class Snapshot(val hits: List<Modrinth.SearchHit>, val total: Int, val time: Long)
    private val previous = object : LinkedHashMap<String, Snapshot>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?) = size > 8
    }

    var pageIndex by mutableStateOf(0)
        private set
    val pageCount: Int get() = ((total + Modrinth.PAGE - 1) / Modrinth.PAGE).coerceAtLeast(1)
    fun goToPage(index: Int) { if (!searching && index in 0 until pageCount) run(page = index) }

    val hasMore: Boolean get() = hits.size < total

    fun run(more: Boolean = false, force: Boolean = false, page: Int = 0) {
        val requestedQuery = query.trim()
        val requestedSort = sort
        val cacheKey = "$requestedSort\u0000$requestedQuery" + if (page == 0) "" else "\u0000$page"
        if (more && (searching || completedQuery != cacheKey)) return
        if (!more && !force && !searching && !failed && completedQuery == cacheKey && System.currentTimeMillis() - completedAt < 300_000) return
        job?.cancel()
        val token = Any()
        requestToken = token
        if (!more && !force) previous[cacheKey]?.takeIf { System.currentTimeMillis() - it.time < 300_000 }?.let { cached ->
            pageIndex = page
            hits = cached.hits; total = cached.total; completedQuery = cacheKey; completedAt = cached.time
            searching = false; failed = false; error = null
            return
        }
        val offset = if (more) hits.size else page * Modrinth.PAGE
        if (!more && completedQuery != cacheKey) { hits = emptyList(); total = 0 }
        searching = true
        failed = false
        error = null
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    if (force) Http.withFreshMetadata { sortedFetch?.invoke(requestedQuery, offset, requestedSort) ?: fetch(requestedQuery, offset) }
                    else sortedFetch?.invoke(requestedQuery, offset, requestedSort) ?: fetch(requestedQuery, offset)
                }
                if (requestToken !== token) return@launch
                hits = if (more) hits + page.hits.filter { hit -> hits.none { it.projectId == hit.projectId } } else page.hits
                pageIndex = offset / Modrinth.PAGE
                total = page.totalHits
                completedQuery = cacheKey
                completedAt = System.currentTimeMillis()
                if (hits.size <= 400) previous[cacheKey] = Snapshot(hits, total, completedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestToken !== token) return@launch
                failed = true
                error = e.message ?: "Не удалось загрузить каталог"
            } finally {
                if (requestToken === token) searching = false
            }
        }
    }
}
