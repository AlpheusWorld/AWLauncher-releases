package ru.aw.launcher.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.sha1Of
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readBytes

class DownloaderTest {

    private val payload = ByteArray(9 * 1024 * 1024 + 321) { (it * 13 + it / 777).toByte() }
    private val sha1 = sha1Of(payload.inputStream())

    private fun serve(ranges: Boolean, block: (url: String, pieces: AtomicInteger) -> Unit) {
        val pieces = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/client.jar") { exchange ->
                if (ranges) exchange.responseHeaders.add("Accept-Ranges", "bytes")
                val range = exchange.requestHeaders.getFirst("Range")
                when {
                    exchange.requestMethod == "HEAD" -> {
                        exchange.responseHeaders.add("Content-Length", payload.size.toString())
                        exchange.sendResponseHeaders(200, -1)
                    }
                    ranges && range != null -> {
                        pieces.incrementAndGet()
                        val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                        exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                        exchange.responseBody.use { it.write(payload, start, end - start + 1) }
                    }
                    else -> {
                        exchange.sendResponseHeaders(200, payload.size.toLong())
                        exchange.responseBody.use { it.write(payload) }
                    }
                }
                exchange.close()
            }
            start()
        }
        try {
            block("http://127.0.0.1:${server.address.port}/client.jar", pieces)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a big file comes in parallel pieces and is checked`(@TempDir dir: Path) = serve(ranges = true) { url, pieces ->
        val dest = dir.resolve("client.jar")
        var last: DownloadProgress? = null
        runBlocking { Downloader(maxAttempts = 1).run(listOf(DownloadTask(url, dest, sha1, payload.size.toLong())), onProgress = { last = it }) }

        assertArrayEquals(payload, dest.readBytes())
        assertEquals(3, pieces.get())
        assertEquals(payload.size.toLong(), last?.completedBytes)
        assertFalse(dir.resolve("client.jar.part").exists())
    }

    @Test
    fun `a server without ranges still gets the whole file`(@TempDir dir: Path) = serve(ranges = false) { url, pieces ->
        val dest = dir.resolve("client.jar")
        runBlocking { Downloader(maxAttempts = 1).run(listOf(DownloadTask(url, dest, sha1, payload.size.toLong()))) }

        assertArrayEquals(payload, dest.readBytes())
        assertEquals(0, pieces.get())
    }

    @Test
    fun `a big file with the wrong hash is thrown away`(@TempDir dir: Path) = serve(ranges = true) { url, _ ->
        val dest = dir.resolve("client.jar")
        assertThrows<IOException> {
            runBlocking { Downloader(maxAttempts = 1).run(listOf(DownloadTask(url, dest, "0".repeat(40), payload.size.toLong()))) }
        }
        assertFalse(dest.exists())
        assertFalse(dir.resolve("client.jar.part").exists())
    }

    @Test
    fun `parallel downloads add up into one progress`() {
        var merged: DownloadProgress? = null
        val progress = ProgressMerger { merged = it }
        progress.sink("java")(DownloadProgress(10, 100, 5_000, 50_000, 1_000, "modules"))
        progress.sink("game")(DownloadProgress(20, 400, 7_000, 70_000, 2_000, "client.jar"))
        progress.sink("java")(DownloadProgress(100, 100, 50_000, 50_000, 0, "done"))

        assertEquals(DownloadProgress(120, 500, 57_000, 120_000, 2_000, "done"), merged)
        assertTrue(merged!!.fraction in 0.47f..0.48f)
    }
}
