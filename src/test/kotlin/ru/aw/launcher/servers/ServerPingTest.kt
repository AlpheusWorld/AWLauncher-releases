package ru.aw.launcher.servers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServerPingTest {

    @Test
    fun `var ints round-trip, the negative protocol number included`() {
        for (value in listOf(0, 1, 127, 128, 255, 25565, 2_097_151, Int.MAX_VALUE, -1)) {
            val bytes = ByteArrayOutputStream().also { with(ServerPing) { it.writeVarInt(value) } }.toByteArray()
            assertEquals(value, with(ServerPing) { ByteArrayInputStream(bytes).readVarInt() })
        }
        val minusOne = ByteArrayOutputStream().also { with(ServerPing) { it.writeVarInt(-1) } }.toByteArray()
        assertEquals(5, minusOne.size)
    }

    @Test
    fun `addresses are read the way players type them`() {
        assertEquals(ServerAddress("play.example.net", null), ServerAddress.parse("play.example.net"))
        assertEquals(ServerAddress("play.example.net", 25566), ServerAddress.parse(" play.example.net:25566 "))
        assertEquals(ServerAddress("::1", 25565), ServerAddress.parse("[::1]:25565"))
        assertEquals(ServerAddress("2001:db8::1", null), ServerAddress.parse("2001:db8::1"))
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("host:70000"))
        assertNull(ServerAddress.parse("host:abc"))
        assertNull(ServerAddress.parse("two words"))
        assertNull(ServerAddress.parse(":25565"))
    }

    @Test
    fun `status is read with formatting codes stripped`() {
        val status = ServerPing.parseStatus(
            """{"version":{"name":"§6Paper 1.21.4","protocol":769},"players":{"max":100,"online":7},
               "description":{"text":"§aHello ","extra":[{"text":"world"},"!"]},
               "favicon":"data:image/png;base64,AAEC"}""",
            latencyMs = 42,
        )
        assertEquals(7, status.playersOnline)
        assertEquals(100, status.playersMax)
        assertEquals("Paper 1.21.4", status.versionName)
        assertEquals("Hello world!", status.motd)
        assertArrayEquals(byteArrayOf(0, 1, 2), status.favicon)
        assertEquals(42, status.latencyMs)
    }

    @Test
    fun `a whole exchange with a server speaking the protocol`() {
        val handshake = ByteArray(0).let { arrayOf(it) }
        val port = fakeServer("""{"version":{"name":"1.20.1","protocol":763},"players":{"max":20,"online":3},"description":"Друзья"}""") {
            handshake[0] = it
        }

        val status = runBlocking { ServerPing.ping("127.0.0.1:$port") }

        assertNotNull(status)
        assertEquals(3, status!!.playersOnline)
        assertEquals("Друзья", status.motd)
        val input = DataInputStream(ByteArrayInputStream(handshake[0]))
        with(ServerPing) {
            assertEquals(0, input.readVarInt())
            assertEquals(-1, input.readVarInt())
            val host = ByteArray(input.readVarInt()).also { input.readFully(it) }
            assertEquals("127.0.0.1", String(host))
            assertEquals(port, input.readUnsignedShort())
            assertEquals(1, input.readVarInt())
        }
    }

    @Test
    fun `nothing listening means offline, not an exception`() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(runBlocking { ServerPing.ping("127.0.0.1:$port", timeoutMs = 1000) })
    }

    private fun fakeServer(json: String, onHandshake: (ByteArray) -> Unit): Int {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            socket.use { server ->
                server.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val output = client.getOutputStream()
                    fun readVarInt(): Int = with(ServerPing) { input.readVarInt() }
                    fun varInt(value: Int) = ByteArrayOutputStream().also { with(ServerPing) { it.writeVarInt(value) } }.toByteArray()
                    fun frame(payload: ByteArray) = varInt(payload.size) + payload

                    onHandshake(ByteArray(readVarInt()).also { input.readFully(it) })
                    input.readFully(ByteArray(readVarInt()))
                    val text = json.toByteArray(Charsets.UTF_8)
                    output.write(frame(byteArrayOf(0) + varInt(text.size) + text))
                    output.flush()
                    val ping = ByteArray(readVarInt()).also { input.readFully(it) }
                    output.write(frame(ping))
                    output.flush()
                }
            }
        }
        return socket.localPort
    }
}
