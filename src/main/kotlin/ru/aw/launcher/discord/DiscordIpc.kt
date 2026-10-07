package ru.aw.launcher.discord

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import kotlinx.serialization.json.*
import ru.aw.launcher.core.Json
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal interface PresenceIpc : Closeable {
    fun setActivity(activity: JsonObject?)
    fun ping()
}

internal class DiscordIpc internal constructor(private val transport: Transport) : PresenceIpc {
    internal interface Transport : Closeable {
        fun readFully(bytes: ByteArray, deadline: Long)
        fun write(bytes: ByteArray)
    }

    override fun setActivity(activity: JsonObject?) {
        val nonce = UUID.randomUUID().toString()
        write(OP_FRAME, buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                put("activity", activity ?: JsonNull)
            }
            put("nonce", nonce)
        })
        val deadline = deadline()
        repeat(MAX_EVENTS) {
            val (op, reply) = read(deadline)
            if (op != OP_FRAME || reply.text("nonce") != nonce) return@repeat
            if (reply.text("evt") == "ERROR") throw IOException("Discord rejected SET_ACTIVITY")
            if (reply.text("cmd") == "SET_ACTIVITY") return
        }
        throw IOException("Discord did not acknowledge SET_ACTIVITY")
    }

    override fun ping() {
        val nonce = UUID.randomUUID().toString()
        write(OP_PING, buildJsonObject { put("nonce", nonce) })
        val deadline = deadline()
        repeat(MAX_EVENTS) {
            val (op, payload) = read(deadline)
            if (op == OP_PONG && payload.text("nonce") == nonce) return
        }
        throw IOException("Discord heartbeat failed")
    }

    internal fun handshake(appId: String) {
        write(OP_HANDSHAKE, buildJsonObject { put("v", 1); put("client_id", appId) })
        val deadline = deadline()
        repeat(MAX_EVENTS) {
            val (op, payload) = read(deadline)
            if (op == OP_FRAME && payload.text("evt") == "READY") return
            if (payload.text("evt") == "ERROR") throw IOException("Discord rejected the application")
        }
        throw IOException("Discord handshake failed")
    }

    private fun write(op: Int, payload: JsonObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        if (body.size > MAX_FRAME) throw IOException("Discord activity is too large")
        transport.write(ByteBuffer.allocate(HEADER + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op).putInt(body.size).put(body).array())
    }

    private fun read(deadline: Long): Pair<Int, JsonObject> {
        repeat(MAX_EVENTS) {
            val header = ByteArray(HEADER)
            transport.readFully(header, deadline)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val op = buffer.int
            val length = buffer.int
            if (length !in 1..MAX_FRAME || op !in OP_HANDSHAKE..OP_PONG) throw IOException("Invalid Discord frame")
            val body = ByteArray(length)
            transport.readFully(body, deadline)
            val payload = try { Json.parseToJsonElement(body.decodeToString()).jsonObject }
                catch (e: IllegalArgumentException) { throw IOException("Invalid Discord JSON", e) }
            if (op == OP_CLOSE) throw IOException("Discord disconnected")
            if (op == OP_PING) write(OP_PONG, payload) else return op to payload
        }
        throw IOException("Too many Discord events")
    }

    override fun close() = transport.close()

    companion object {
        private const val OP_HANDSHAKE = 0
        private const val OP_FRAME = 1
        private const val OP_CLOSE = 2
        private const val OP_PING = 3
        private const val OP_PONG = 4
        private const val HEADER = 8
        private const val MAX_FRAME = 256 * 1024
        private const val MAX_EVENTS = 64
        private fun deadline() = System.nanoTime() + 5_000_000_000L
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        fun connect(appId: String): DiscordIpc {
            if (!System.getProperty("os.name").startsWith("Windows")) throw IOException("Discord IPC requires Windows")
            for (index in 0..9) {
                val handle = Kernel32.INSTANCE.CreateFile("\\\\.\\pipe\\discord-ipc-$index",
                    WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null)
                if (handle == WinBase.INVALID_HANDLE_VALUE) continue
                val ipc = DiscordIpc(WindowsPipe(handle))
                try { ipc.handshake(appId); return ipc }
                catch (_: IOException) { ipc.close() }
            }
            throw IOException("Discord is not available")
        }
    }

    private class WindowsPipe(private val handle: WinNT.HANDLE) : Transport {
        private val closed = AtomicBoolean(false)
        override fun readFully(bytes: ByteArray, deadline: Long) {
            var offset = 0
            while (offset < bytes.size) {
                if (closed.get()) throw IOException("Discord connection closed")
                if (System.nanoTime() >= deadline) throw IOException("Discord response timed out")
                val available = IntByReference()
                if (!Kernel32.INSTANCE.PeekNamedPipe(handle, null, 0, null, available, null)) throw IOException("Discord disconnected")
                if (available.value == 0) {
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Discord read interrupted", e) }
                    continue
                }
                val part = ByteArray(minOf(bytes.size - offset, available.value))
                val read = IntByReference()
                if (!Kernel32.INSTANCE.ReadFile(handle, part, part.size, read, null) || read.value <= 0) throw IOException("Discord read failed")
                part.copyInto(bytes, offset, 0, read.value)
                offset += read.value
            }
        }

        override fun write(bytes: ByteArray) {
            if (closed.get()) throw IOException("Discord connection closed")
            val written = IntByReference()
            if (!Kernel32.INSTANCE.WriteFile(handle, bytes, bytes.size, written, null) || written.value != bytes.size)
                throw IOException("Discord write failed")
        }

        override fun close() { if (closed.compareAndSet(false, true)) Kernel32.INSTANCE.CloseHandle(handle) }
    }
}