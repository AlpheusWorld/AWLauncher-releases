package ru.aw.launcher.discord

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.core.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class DiscordIpcTest {
    @TempDir lateinit var socketDirectory: Path

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Unix transport handles fragmented frames and closes resources`() {
        val path = socketDirectory.resolve("ipc")
        val expected = ByteArray(32768) { it.toByte() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(path))
                val response = worker.submit {
                    server.accept().use { peer ->
                        val request = ByteBuffer.allocate(3)
                        while (request.hasRemaining()) check(peer.read(request) > 0)
                        assertArrayEquals(byteArrayOf(1, 2, 3), request.array())
                        var offset = 0
                        while (offset < expected.size) {
                            val buffer = ByteBuffer.wrap(expected, offset, minOf(17, expected.size - offset))
                            while (buffer.hasRemaining()) peer.write(buffer)
                            offset += minOf(17, expected.size - offset)
                        }
                    }
                }
                DiscordIpc.UnixSocket(path).use { transport ->
                    transport.write(byteArrayOf(1, 2, 3))
                    val received = ByteArray(expected.size)
                    transport.readFully(received, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
                    assertArrayEquals(expected, received)
                }
                response.get(5, TimeUnit.SECONDS)
            }
        } finally { worker.shutdownNow() }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Unix transport enforces the read deadline and detects disconnected peers`() {
        val path = socketDirectory.resolve("ipc-timeout")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(path))
            DiscordIpc.UnixSocket(path).use { transport ->
                server.accept().use {
                    val timeout = assertThrows(IOException::class.java) {
                        transport.readFully(ByteArray(1), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40))
                    }
                    assertTrue(timeout.message.orEmpty().contains("timed out"))
                }
                assertThrows(IOException::class.java) { transport.readFully(ByteArray(1), System.nanoTime() + TimeUnit.SECONDS.toNanos(1)) }
            }
        }
    }

    private class Pipe : DiscordIpc.Transport {
        val writes = mutableListOf<Pair<Int,JsonObject>>()
        var incoming = byteArrayOf()
        var closed = false
        var respond: (Int,JsonObject) -> Unit = { _,_ -> }
        fun enqueue(op:Int,body:JsonObject) {
            val bytes=body.toString().toByteArray()
            incoming += ByteBuffer.allocate(8+bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(bytes.size).put(bytes).array()
        }
        override fun write(bytes:ByteArray) {
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val op=b.int
            val body=ByteArray(b.int).also(b::get)
            val json=Json.parseToJsonElement(body.decodeToString()).jsonObject
            writes += op to json
            respond(op,json)
        }
        override fun readFully(bytes:ByteArray,deadline:Long) {
            if(incoming.size<bytes.size) throw IOException("test pipe exhausted")
            incoming.copyInto(bytes,0,0,bytes.size)
            incoming=incoming.copyOfRange(bytes.size,incoming.size)
        }
        override fun close() { closed=true }
    }

    @Test
    fun `handshake and activity acknowledge the matching nonce across ping and unsolicited events`() {
        val pipe=Pipe()
        pipe.respond={ op,body -> when(op) {
            0 -> pipe.enqueue(1,buildJsonObject { put("evt","READY") })
            1 -> {
                pipe.enqueue(3,buildJsonObject { put("nonce","server-ping") })
                pipe.enqueue(1,buildJsonObject { put("cmd","DISPATCH");put("evt","CURRENT_USER_UPDATE") })
                pipe.enqueue(1,buildJsonObject { put("cmd","SET_ACTIVITY");put("nonce","unrelated") })
                pipe.enqueue(1,buildJsonObject { put("cmd","SET_ACTIVITY");put("nonce",body.getValue("nonce")) })
            }
        } }
        val ipc=DiscordIpc(pipe)
        ipc.handshake("1555994295134326945")
        ipc.setActivity(buildJsonObject { put("details","Testing") })
        assertEquals("1555994295134326945",pipe.writes.first().second.getValue("client_id").jsonPrimitive.content)
        assertEquals("server-ping",pipe.writes.last().second.getValue("nonce").jsonPrimitive.content)
        assertEquals(4,pipe.writes.last().first)
        ipc.setActivity(null)
        val clear=pipe.writes.last { it.first==1 }.second.getValue("args").jsonObject
        assertEquals(JsonNull,clear.getValue("activity"))
        ipc.close();assertTrue(pipe.closed)
    }

    @Test
    fun `heartbeat handles peer ping before its matching pong`() {
        val pipe=Pipe()
        pipe.respond={ op,body -> if(op==3) {
            pipe.enqueue(3,buildJsonObject { put("nonce","peer") })
            pipe.enqueue(4,body)
        } }
        DiscordIpc(pipe).ping()
        assertEquals(listOf(3,4),pipe.writes.map { it.first })
    }

    @Test
    fun `oversized frames malformed JSON and explicit disconnect fail safely`() {
        for(length in listOf(-1,0,256*1024+1)) {
            val pipe=Pipe()
            pipe.incoming=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(length).array()
            assertThrows(IOException::class.java) { DiscordIpc(pipe).handshake("1555994295134326945") }
        }
        val malformed=Pipe()
        malformed.incoming=ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(1).put('x'.code.toByte()).array()
        assertThrows(IOException::class.java) { DiscordIpc(malformed).ping() }
        val closed=Pipe().apply { enqueue(2,buildJsonObject { put("message","closed") }) }
        assertThrows(IOException::class.java) { DiscordIpc(closed).ping() }
    }
}
