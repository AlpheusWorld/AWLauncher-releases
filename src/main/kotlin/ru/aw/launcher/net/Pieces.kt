package ru.aw.launcher.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Call
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object Pieces {

    const val STREAMS = 6
    const val PIECE_BYTES = 4L shl 20
    private const val ATTEMPTS = 4
    private const val RETRY_MILLIS = 500L

    val client: OkHttpClient by lazy { Http.client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build() }

    class Source(val url: String, val length: Long, val ranges: Boolean)

    fun probe(url: String, onCall: (Call) -> Unit = {}): Source = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).also(onCall).execute().use { response ->
            if (!response.isSuccessful) return@use null
            Source(
                url = response.request.url.toString(),
                length = response.header("Content-Length")?.toLongOrNull() ?: -1,
                ranges = response.header("Accept-Ranges").equals("bytes", ignoreCase = true),
            )
        }
    }.getOrNull() ?: Source(url, -1, ranges = false)

    suspend fun fetch(
        url: String,
        dest: Path,
        total: Long,
        onBytes: (Int) -> Unit,
        streams: Int = STREAMS,
        pieceBytes: Long = PIECE_BYTES,
        onCall: (Call) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        RandomAccessFile(dest.toFile(), "rw").use { file ->
            file.setLength(total)
            val starts = (0 until (total + pieceBytes - 1) / pieceBytes).map { it * pieceBytes }
            val next = AtomicInteger()
            coroutineScope {
                repeat(minOf(streams, starts.size)) {
                    launch {
                        while (true) {
                            val start = starts.getOrNull(next.getAndIncrement()) ?: break
                            range(url, file.channel, start, minOf(total, start + pieceBytes) - 1, onBytes, onCall)
                        }
                    }
                }
            }
        }
    }

    private fun range(url: String, channel: FileChannel, start: Long, end: Long, onBytes: (Int) -> Unit, onCall: (Call) -> Unit) {
        var position = start
        var attempt = 0
        while (position <= end) {
            try {
                onBytes(0)
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").header("Connection", "close").build()
                client.newCall(request).also(onCall).execute().use { response ->
                    if (response.code != 206) throw IOException("сервер отдал HTTP ${response.code} вместо части файла")
                    val input = response.body?.byteStream() ?: throw IOException("пустой ответ сервера")
                    val buffer = ByteArray(1 shl 16)
                    while (position <= end) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), end - position + 1).toInt())
                        if (n <= 0) break
                        channel.write(ByteBuffer.wrap(buffer, 0, n), position)
                        position += n
                        onBytes(n)
                    }
                }
                if (position <= end) throw IOException("соединение оборвалось посреди загрузки")
            } catch (e: IOException) {
                onBytes(0)
                if (++attempt >= ATTEMPTS) throw e
                Thread.sleep(RETRY_MILLIS * attempt)
            }
        }
    }
}

class ProgressMerger(private val out: (DownloadProgress) -> Unit) {

    private val parts = ConcurrentHashMap<String, DownloadProgress>()

    fun sink(key: String): (DownloadProgress) -> Unit = { progress ->
        parts[key] = progress
        val all = parts.values.toList()
        out(
            DownloadProgress(
                completedFiles = all.sumOf { it.completedFiles },
                totalFiles = all.sumOf { it.totalFiles },
                completedBytes = all.sumOf { it.completedBytes },
                totalBytes = all.sumOf { it.totalBytes },
                bytesPerSecond = all.sumOf { it.bytesPerSecond },
                currentFile = progress.currentFile,
            )
        )
    }
}
