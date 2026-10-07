package ru.aw.launcher.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.VerifyCache
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.core.toHex
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize

data class DownloadTask(
    val url: String,
    val dest: Path,
    val sha1: String? = null,
    val size: Long = 0,
    val mirrors: List<String> = emptyList(),
    val label: String = dest.fileName?.toString().orEmpty(),
)

data class DownloadProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val completedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val currentFile: String,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            totalFiles > 0 -> (completedFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
            else -> 0f
        }
}

class Downloader(
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val maxAttempts: Int = 4,
    private val bigFileBytes: Long = BIG_FILE_BYTES,
) {

    companion object {
        val DEFAULT_CONCURRENCY = (Runtime.getRuntime().availableProcessors() * 4).coerceIn(8, 24)
        private const val BUFFER = 1 shl 16
        private const val BIG_FILE_BYTES = 8L shl 20
    }

    suspend fun run(
        tasks: List<DownloadTask>,
        onProgress: (DownloadProgress) -> Unit = {},
    ) {
        if (tasks.isEmpty()) return

        @Suppress("OPT_IN_USAGE")
        val io = Dispatchers.IO.limitedParallelism(concurrency)

        val pending = withContext(io) {
            tasks.chunked(256).map { chunk ->
                async {
                    chunk.filterNot { task ->
                        VerifyCache.isValid(task.dest, task.sha1, task.size.takeIf { it > 0 })
                    }
                }
            }.awaitAll().flatten()
        }
        if (pending.isEmpty()) {
            Log.debug("nothing to download, ${tasks.size} files already valid")
            return
        }

        val totalBytes = pending.sumOf { it.size }
        val totalFiles = pending.size
        val doneBytes = AtomicLong(0)
        val doneFiles = AtomicInteger(0)
        val current = AtomicReference(pending.first().label)

        Log.info("downloading $totalFiles files (${totalBytes / 1024 / 1024} MB)")

        coroutineScope {
            val permits = Semaphore(concurrency)
            val reporter = launch {
                var lastBytes = 0L
                var lastAt = System.nanoTime()
                while (isActive) {
                    delay(100)
                    val now = System.nanoTime()
                    val bytes = doneBytes.get()
                    val elapsed = (now - lastAt) / 1_000_000_000.0
                    val speed = if (elapsed > 0) ((bytes - lastBytes) / elapsed).toLong() else 0L
                    lastBytes = bytes
                    lastAt = now
                    onProgress(
                        DownloadProgress(doneFiles.get(), totalFiles, bytes, totalBytes, speed, current.get())
                    )
                }
            }

            try {
                pending.map { task ->
                    async(io) {
                        permits.withPermit {
                        current.set(task.label)
                        fetch(task) { delta -> doneBytes.addAndGet(delta) }
                        doneFiles.incrementAndGet()
                        }
                    }
                }.awaitAll()
            } finally {
                reporter.cancel()
            }
        }

        onProgress(DownloadProgress(totalFiles, totalFiles, totalBytes, totalBytes, 0, "done"))
        VerifyCache.save()
    }

    private suspend fun fetch(task: DownloadTask, onBytes: (Long) -> Unit) {
        val urls = listOf(task.url) + task.mirrors
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            val url = urls[(attempt - 1).coerceAtMost(urls.size - 1)]
            try {
                downloadOnce(url, task, onBytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                Log.debug("attempt $attempt/$maxAttempts failed for ${task.label}: ${e.message}")
                if (attempt < maxAttempts) delay(200L * attempt * attempt)
            }
        }
        throw IOException("failed to download ${task.label} from ${task.url}", lastError)
    }

    private suspend fun downloadOnce(url: String, task: DownloadTask, onBytes: (Long) -> Unit) {
        task.dest.parent?.createDirectories()
        if (task.size >= bigFileBytes && downloadInPieces(url, task, onBytes)) return
        val part = task.dest.resolveSibling("${task.dest.fileName}.part")
        var counted = 0L

        try {
            withCalls { register ->
            val context = currentCoroutineContext()
            Http.client.newCall(Http.request(url)).also(register).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")

                val digest = if (task.sha1 != null) MessageDigest.getInstance("SHA-1") else null
                val buffer = ByteArray(BUFFER)

                body.byteStream().use { input ->
                    Files.newOutputStream(part).buffered(BUFFER).use { output ->
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            digest?.update(buffer, 0, n)
                            counted += n
                            onBytes(n.toLong())
                        }
                    }
                }

                if (digest != null) {
                    val actual = digest.digest().toHex()
                    if (!actual.equals(task.sha1, ignoreCase = true)) {
                        throw IOException("sha1 mismatch: expected ${task.sha1}, got $actual")
                    }
                }
            }
            }

            if (task.size > 0 && part.fileSize() != task.size) {
                throw IOException("size mismatch: expected ${task.size}, got ${part.fileSize()}")
            }

            currentCoroutineContext().ensureActive()
            Files.move(part, task.dest, StandardCopyOption.REPLACE_EXISTING)
            task.sha1?.let { VerifyCache.record(task.dest, it) }

            if (task.size > 0 && counted != task.size) onBytes(task.size - counted)
        } catch (e: Throwable) {
            part.deleteIfExists()
            if (counted > 0) onBytes(-counted)
            throw e
        }
    }

    private suspend fun downloadInPieces(url: String, task: DownloadTask, onBytes: (Long) -> Unit): Boolean {
        return withCalls { register ->
        val context = currentCoroutineContext()
        val source = Pieces.probe(url, register)
        context.ensureActive()
        if (!source.ranges || source.length != task.size) return@withCalls false
        val part = task.dest.resolveSibling("${task.dest.fileName}.part")
        val counted = AtomicLong()
        try {
            Pieces.fetch(source.url, part, task.size, { n -> context.ensureActive(); counted.addAndGet(n.toLong()); onBytes(n.toLong()) }, onCall = register)
            task.sha1?.let { expected ->
                val actual = sha1Of(part)
                if (!actual.equals(expected, ignoreCase = true)) throw IOException("sha1 mismatch: expected $expected, got $actual")
            }
            context.ensureActive()
            Files.move(part, task.dest, StandardCopyOption.REPLACE_EXISTING)
            task.sha1?.let { VerifyCache.record(task.dest, it) }
            true
        } catch (e: Throwable) {
            part.deleteIfExists()
            onBytes(-counted.get())
            throw e
        }
        }
    }

    private suspend fun <T> withCalls(block: suspend ((Call) -> Unit) -> T): T = coroutineScope {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val worker = async(Dispatchers.IO) {
            try { block { call -> calls.add(call); if (!isActive) call.cancel() } }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        }
        try { worker.await() }
        finally { calls.forEach { it.cancel() } }
    }
}
