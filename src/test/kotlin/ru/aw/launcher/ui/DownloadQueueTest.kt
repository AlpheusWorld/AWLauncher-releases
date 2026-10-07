package ru.aw.launcher.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DownloadQueueTest {
    @Test
    fun `tasks run in order and duplicate pending jobs are ignored`() = runBlocking {
        val queue = DownloadQueue(CoroutineScope(coroutineContext))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        queue.enqueue("a", "A") { entered.complete(Unit); release.await(); order += "a" }
        queue.enqueue("b", "B", onFinished = { complete.complete(Unit) }) { order += "b" }
        assertFalse(queue.enqueue("a", "Duplicate") { fail("Duplicate ran") })
        entered.await()
        assertTrue(order.isEmpty())
        assertEquals(2, queue.pendingCount)
        release.complete(Unit)
        withTimeout(5000) { complete.await() }
        assertEquals(listOf("a", "b"), order)
        assertTrue(queue.items.all { it.status == DownloadStatus.COMPLETED })
    }

    @Test
    fun `cancellation waits for cleanup before starting the next task`() = runBlocking {
        val queue = DownloadQueue(CoroutineScope(coroutineContext))
        val entered = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        queue.enqueue("a", "A") {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { delay(50); order += "cleanup" } }
        }
        queue.enqueue("b", "B", onFinished = { complete.complete(Unit) }) { order += "next" }
        entered.await()
        queue.cancel(queue.items.first().id)
        assertEquals(DownloadStatus.CANCELLING, queue.items.first().status)
        assertEquals(DownloadStatus.WAITING, queue.items.last().status)
        withTimeout(5000) { complete.await() }
        assertEquals(listOf("cleanup", "next"), order)
        assertEquals(DownloadStatus.CANCELLED, queue.items.first().status)
    }

    @Test
    fun `waiting tasks can be cancelled and reordered while the queue is paused`() = runBlocking {
        val queue = DownloadQueue(CoroutineScope(coroutineContext))
        val complete = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var cancelled = 0
        queue.pauseQueue(true)
        queue.enqueue("a", "A") { order += "a" }
        queue.enqueue("b", "B", onFinished = { cancelled++ }) { fail("Cancelled task ran") }
        queue.enqueue("c", "C") { order += "c" }
        queue.cancel(queue.items[1].id)
        queue.moveFirst(queue.items.last().id)
        queue.enqueue("d", "D", onFinished = { complete.complete(Unit) }) { order += "d" }
        assertNull(queue.active)
        assertEquals(1, cancelled)
        queue.pauseQueue(false)
        withTimeout(5000) { complete.await() }
        assertEquals(listOf("c", "a", "d"), order)
    }

    @Test
    fun `a failed task does not block the queue and can be retried`() = runBlocking {
        val queue = DownloadQueue(CoroutineScope(coroutineContext))
        val next = CompletableDeferred<Unit>()
        val retried = CompletableDeferred<Unit>()
        var attempts = 0
        var queued = 0
        queue.enqueue("a", "A", onQueued = { queued++ }) {
            if (++attempts == 1) error("Network failed")
            retried.complete(Unit)
        }
        queue.enqueue("b", "B", onFinished = { next.complete(Unit) }) {}
        withTimeout(5000) { next.await() }
        val failed = queue.items.first()
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals("Network failed", failed.error)
        queue.retry(failed.id)
        withTimeout(5000) { retried.await() }
        assertEquals(2, queued)
        assertEquals(2, attempts)
        assertEquals(DownloadStatus.COMPLETED, queue.items.last().status)
        queue.clearFinished()
        assertTrue(queue.items.isEmpty())
    }
}
