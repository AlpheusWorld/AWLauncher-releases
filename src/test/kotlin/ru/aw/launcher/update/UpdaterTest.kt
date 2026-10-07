package ru.aw.launcher.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.aw.launcher.core.toHex
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

class UpdaterTest {

    private val sha = "a".repeat(64)

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("1.0.1", "1.0"))
        assertTrue(Updater.isNewer("v2.0.0", "1.9.0"))
        assertTrue(Updater.isNewer("1.1.0", "1.1.0-beta"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.1.0-beta", "1.1.0"))
        assertFalse(Updater.isNewer("0.9", "1.0.0"))
    }

    @Test
    fun `an update does not bring back a removed desktop shortcut`() {
        val msi = Path.of("AWLauncher-1.5.5.msi")
        val log = Path.of("install.log")
        assertEquals("/i \"AWLauncher-1.5.5.msi\" /qn /norestart /l*v \"install.log\"", Updater.msiArguments(msi, desktopShortcut = true, log))
        assertEquals(
            "/i \"AWLauncher-1.5.5.msi\" /qn /norestart JP_INSTALL_DESKTOP_SHORTCUT=\"\" /l*v \"install.log\"",
            Updater.msiArguments(msi, desktopShortcut = false, log),
        )
    }

    @Test
    fun `a feed has to name an https installer and its hash`() {
        Updater.validate(UpdateManifest("1.2.0", "https://example.com/AWLauncher-1.2.0.msi", sha))
        assertThrows<IOException> { Updater.validate(UpdateManifest("1.2.0", "http://example.com/a.msi", sha)) }
        assertThrows<IOException> { Updater.validate(UpdateManifest("1.2.0", "https://example.com/a.msi", "abc")) }
        assertThrows<IOException> { Updater.validate(UpdateManifest("../1.2.0", "https://example.com/a.msi", sha)) }
    }

    @Test
    fun `signed feed verification rejects changed URLs hashes sizes and signing keys`() {
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val update = UpdateManifest("1.0.3", "https://github.com/AlpheusWorld/AWLauncher-releases/releases/download/v1.0.3/AWLauncher.msi", sha, 100,
            signatureUrl = "https://github.com/AlpheusWorld/AWLauncher-releases/releases/download/v1.0.3/update.json.sig")
        val document = ru.aw.launcher.core.Json.encodeToString(UpdateManifest.serializer(), update)
        val algorithm = java.security.Signature.getInstance("Ed25519")
        algorithm.initSign(pair.private)
        algorithm.update(document.toByteArray(Charsets.UTF_8))
        val signature = java.util.Base64.getEncoder().encodeToString(algorithm.sign())
        val encodedKey = java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
        val signed = update.copy(signedDocument = document, signature = signature)
        Updater.validate(signed)
        Updater.verifySignedManifest(signed, encodedKey)
        for (changed in listOf(signed.copy(url = "https://example.com/malware.msi"), signed.copy(sha256 = "b".repeat(64)),
            signed.copy(size = 101), signed.copy(version = "9.0.0"), signed.copy(signatureUrl = "https://example.com/fake.sig"),
            signed.copy(signedDocument = document + " "), signed.copy(signature = java.util.Base64.getEncoder().encodeToString(ByteArray(64))))) {
            assertThrows<IOException> { Updater.verifySignedManifest(changed, encodedKey) }
        }
        val other = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertThrows<IOException> { Updater.verifySignedManifest(signed, java.util.Base64.getEncoder().encodeToString(other.public.encoded)) }
        assertThrows<IOException> { Updater.verifySignedManifest(update, encodedKey) }
    }

    @Test
    fun `updates keep the actual launcher folder and preserve shortcut preferences`() {
        val dir = Path.of("C:\\Users\\Player Name\\AWLauncher")
        val args = Updater.msiArguments(Path.of("update.msi"), false, Path.of("update.log"), dir)
        assertTrue(args.contains("INSTALLDIR=\"${dir.toAbsolutePath().normalize()}\""))
        assertTrue(args.contains("JP_INSTALL_DESKTOP_SHORTCUT=\"\""))
        assertTrue(args.contains("/qn /norestart"))
    }

    @Test
    fun `already installed updates are rejected before downloading or handing off an installer`() = runBlocking {
        val update = UpdateManifest(Updater.currentVersion, "https://example.com/never-download.msi", sha, 100)
        val failure = runCatching { Updater.install(update) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("уже установлена"))
    }

    @Test
    fun `the installer is kept only when its hash matches`() {
        val payload = ByteArray(300_000) { (it * 31).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/AWLauncher-9.9.9.msi") { exchange ->
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/AWLauncher-9.9.9.msi"
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).toHex()

            val file = runBlocking { Updater.download(UpdateManifest("9.9.9", url, hash)) }
            assertArrayEquals(payload, file.readBytes())

            val wrong = UpdateManifest("9.9.8", url, sha)
            assertThrows<IOException> { runBlocking { Updater.download(wrong) } }
            assertTrue(file.parent.listDirectoryEntries().none { it.fileName.toString().startsWith("AWLauncher-9.9.8") })
            assertTrue(file.exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a server with ranges is downloaded in parallel pieces`() {
        val payload = ByteArray(3 * 1024 * 1024 + 123) { (it * 7 + it / 1000).toByte() }
        val ranges = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/AWLauncher-9.9.7.msi") { exchange ->
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                val range = exchange.requestHeaders.getFirst("Range")
                when {
                    exchange.requestMethod == "HEAD" -> {
                        exchange.responseHeaders.add("Content-Length", payload.size.toString())
                        exchange.sendResponseHeaders(200, -1)
                    }
                    range != null -> {
                        ranges.incrementAndGet()
                        val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
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
            val url = "http://127.0.0.1:${server.address.port}/AWLauncher-9.9.7.msi"
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
            var last = 0f

            val file = runBlocking { Updater.download(UpdateManifest("9.9.7", url, hash), streams = 3, pieceBytes = 1L shl 20) { fraction, _ -> last = fraction } }

            assertArrayEquals(payload, file.readBytes())
            assertEquals(4, ranges.get())
            assertEquals(1f, last)
        } finally {
            server.stop(0)
        }
    }
}
