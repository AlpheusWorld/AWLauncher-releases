package ru.aw.launcher.core

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.channels.Channel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.concurrent.thread
import kotlin.io.path.readText

private interface ForegroundApi : StdCallLibrary {
    fun AllowSetForegroundWindow(processId: Int): Boolean
}

class SingleInstance(dir: Path) {

    enum class Role {
        PRIMARY,

        HANDED_OVER,

        UNREACHABLE,
    }

    private val lockFile = dir.resolve("launcher.lock")
    private val portFile = dir.resolve("launcher.port")

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    val requests = Channel<List<String>>(Channel.UNLIMITED)

    fun start(args: List<String>): Role {
        repeat(30) {
            if (claim()) return Role.PRIMARY
            if (runCatching { send(args) }.getOrDefault(false)) return Role.HANDED_OVER
            Thread.sleep(100)
        }
        return Role.UNREACHABLE
    }

    fun close() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }

    private fun claim(): Boolean {
        val channel = runCatching {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }.getOrElse {
            Log.warn("launcher lock unavailable, running without it: ${it.message}")
            return true
        }
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            channel.close()
            return false
        }
        this.channel = channel
        this.lock = lock
        runCatching { listen() }.onFailure { Log.warn("launcher handover unavailable: ${it.message}") }
        return true
    }

    private fun listen() {
        val token = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        this.server = server
        portFile.writeAtomically("${server.localPort} $token ${ProcessHandle.current().pid()}")
        thread(isDaemon = true, name = "launcher-handover") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                socket.use { receive(it, token) }
            }
        }
    }

    private fun receive(socket: Socket, token: String) {
        runCatching {
            socket.soTimeout = 2000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            if (reader.readLine() != token) return
            requests.trySend(generateSequence { reader.readLine() }.toList())
            socket.getOutputStream().apply {
                write("ok\n".toByteArray())
                flush()
            }
        }.onFailure { Log.debug("handover from another start failed: ${it.message}") }
    }

    private fun send(args: List<String>): Boolean {
        val (port, token, pid) = portFile.readText().trim().split(' ')
        allowForeground(pid.toInt())
        Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().apply {
                write((listOf(token) + args).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.shutdownOutput()
            return socket.getInputStream().bufferedReader().readLine() == "ok"
        }
    }

    private fun allowForeground(pid: Int) {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        runCatching { Native.load("user32", ForegroundApi::class.java).AllowSetForegroundWindow(pid) }
    }
}
