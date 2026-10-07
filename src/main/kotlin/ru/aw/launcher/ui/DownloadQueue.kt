package ru.aw.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.aw.launcher.core.Log
import ru.aw.launcher.net.DownloadProgress

enum class DownloadStatus { WAITING, RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED }

data class QueuedDownload(
    val id: Long,
    val key: String,
    val title: String,
    val entryKey: String?,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val stage: String = "",
    val progress: DownloadProgress? = null,
    val error: String? = null,
    val cancellable: Boolean = true,
) {
    val pending: Boolean get() = status in setOf(DownloadStatus.WAITING, DownloadStatus.RUNNING, DownloadStatus.CANCELLING)
}

/** Serializes installation operations; each downloader retains its existing file concurrency. */
class DownloadQueue(private val scope: CoroutineScope) {
    private val lock = Any()
    private var nextId = 0L
    private var runner: Job? = null
    private val tasks = mutableMapOf<Long, Task>()
    var items by mutableStateOf<List<QueuedDownload>>(emptyList())
        private set
    var paused by mutableStateOf(false)
        private set
    val active: QueuedDownload? get() = items.firstOrNull { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.CANCELLING }
    val pendingCount: Int get() = items.count { it.pending }
    fun contains(key: String): Boolean = items.any { it.key == key && it.pending }
    fun containsEntry(key: String): Boolean = items.any { it.entryKey == key && it.pending }

    private data class Task(val started: () -> Unit, val finished: () -> Unit, val block: suspend (Reporter) -> Unit)

    inner class Reporter internal constructor(private val id: Long) {
        fun stage(text: String) = update(id) { it.copy(stage = text, progress = null) }
        fun progress(value: DownloadProgress?) = update(id) { it.copy(progress = value) }
        fun cancellable(value: Boolean) = update(id) { it.copy(cancellable = value) }
    }

    fun enqueue(key: String, title: String, entryKey: String? = null, onQueued: () -> Unit = {},
                onFinished: () -> Unit = {}, block: suspend (Reporter) -> Unit): Boolean = synchronized(lock) {
        if (contains(key)) return false
        val id = ++nextId
        tasks[id] = Task(onQueued, onFinished, block)
        items = items + QueuedDownload(id, key, title, entryKey)
        onQueued()
        startNext()
        true
    }

    fun cancel(id: Long): Unit = synchronized(lock) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (!item.cancellable) return
        when (item.status) {
            DownloadStatus.WAITING -> {
                update(id) { it.copy(status = DownloadStatus.CANCELLED) }
                finishCallback(id)
            }
            DownloadStatus.RUNNING -> {
                update(id) { it.copy(status = DownloadStatus.CANCELLING) }
                runner?.cancel()
            }
            else -> Unit
        }
    }

    fun pauseQueue(value: Boolean) = synchronized(lock) { paused = value; if (!value) startNext() }

    fun moveFirst(id: Long) = synchronized(lock) {
        val item = items.firstOrNull { it.id == id && it.status == DownloadStatus.WAITING } ?: return
        val others = items.filterNot { it.id == id }
        val position = others.indexOfFirst { it.status == DownloadStatus.WAITING }.takeIf { it >= 0 } ?: others.size
        items = others.take(position) + item + others.drop(position)
    }

    fun retry(id: Long) = synchronized(lock) {
        val item = items.firstOrNull { it.id == id && it.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED) } ?: return
        if (contains(item.key)) return
        val task = tasks[id] ?: return
        items = items.filterNot { it.id == id } + item.copy(status = DownloadStatus.WAITING, stage = "", progress = null, error = null, cancellable = true)
        task.started()
        startNext()
    }

    fun clearFinished() = synchronized(lock) {
        val finished = items.filterNot { it.pending }.map { it.id }.toSet()
        items = items.filter { it.pending }
        finished.forEach(tasks::remove)
    }

    fun reportStage(text: String) { active?.id?.let { Reporter(it).stage(text) } }
    fun reportProgress(value: DownloadProgress?) { active?.id?.let { Reporter(it).progress(value) } }
    fun reportCancellable(value: Boolean) { active?.id?.let { Reporter(it).cancellable(value) } }

    private fun update(id: Long, transform: (QueuedDownload) -> QueuedDownload) = synchronized(lock) {
        items = items.map { if (it.id == id && it.pending) transform(it) else it }
    }

    private fun finishCallback(id: Long) {
        try { tasks[id]?.finished?.invoke() }
        catch (e: Exception) { Log.warn("download queue cleanup failed", e) }
        val expired = items.filterNot { it.pending }.dropLast(50).map { it.id }.toSet()
        if (expired.isNotEmpty()) {
            items = items.filterNot { it.id in expired }
            expired.forEach(tasks::remove)
        }
    }

    private fun startNext() {
        if (runner != null || paused) return
        val item = items.firstOrNull { it.status == DownloadStatus.WAITING } ?: return
        val task = tasks.getValue(item.id)
        update(item.id) { it.copy(status = DownloadStatus.RUNNING) }
        runner = scope.launch(start = CoroutineStart.LAZY) {
            try {
                task.block(Reporter(item.id))
                update(item.id) { it.copy(status = DownloadStatus.COMPLETED) }
            } catch (e: CancellationException) {
                update(item.id) { it.copy(status = DownloadStatus.CANCELLED) }
                throw e
            } catch (e: Exception) {
                update(item.id) { it.copy(status = DownloadStatus.FAILED, error = e.message ?: "Не удалось загрузить") }
            } finally {
                synchronized(lock) {
                    finishCallback(item.id)
                    runner = null
                    startNext()
                }
            }
        }
        runner?.start()
    }
}
