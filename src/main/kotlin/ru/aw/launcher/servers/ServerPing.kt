package ru.aw.launcher.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

class ServerStatus(
    val latencyMs: Long,
    val playersOnline: Int,
    val playersMax: Int,
    val versionName: String,
    val motd: String,
    val favicon: ByteArray?,
)

object ServerPing {

    private const val MAX_PACKET = 1 shl 21

    suspend fun ping(address: String, timeoutMs: Int = 3000): ServerStatus? = withContext(Dispatchers.IO) {
        val parsed = ServerAddress.parse(address) ?: return@withContext null
        val target = if (parsed.port == null) SrvResolver.resolve(parsed.host) ?: parsed else parsed
        runCatching { query(target.host, target.effectivePort, timeoutMs) }
            .onFailure { Log.debug("ping $address: ${it.message}") }
            .getOrNull()
    }

    internal fun query(host: String, port: Int, timeoutMs: Int): ServerStatus {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val requested = System.nanoTime()
            output.write(packet(0x00) {
                writeVarInt(-1)
                writeString(host)
                writeShort(port)
                writeVarInt(1)
            })
            output.write(packet(0x00) {})
            output.flush()

            val json = readPacket(input, expectedId = 0x00).readString()
            val answered = System.nanoTime()

            val latency = runCatching {
                val sent = System.nanoTime()
                output.write(packet(0x01) { writeLong(sent) })
                output.flush()
                readPacket(input, expectedId = 0x01)
                (System.nanoTime() - sent) / 1_000_000
            }.getOrElse { (answered - requested) / 1_000_000 }

            return parseStatus(json, latency)
        }
    }

    internal fun parseStatus(json: String, latencyMs: Long): ServerStatus {
        val root = Json.parseToJsonElement(json).jsonObject
        val players = root["players"] as? JsonObject
        val version = root["version"] as? JsonObject
        val favicon = (root["favicon"] as? JsonPrimitive)?.contentOrNull
            ?.substringAfter("base64,", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }

        return ServerStatus(
            latencyMs = latencyMs,
            playersOnline = players?.get("online")?.jsonPrimitive?.intOrNull ?: 0,
            playersMax = players?.get("max")?.jsonPrimitive?.intOrNull ?: 0,
            versionName = version?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty().stripFormatting(),
            motd = root["description"]?.let(::plainText).orEmpty().stripFormatting().trim(),
            favicon = favicon,
        )
    }

    internal fun plainText(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString("") { plainText(it) }
        is JsonObject -> buildString {
            element["text"]?.let { append(plainText(it)) }
            (element["extra"] as? JsonArray)?.forEach { append(plainText(it)) }
        }
    }

    private val FORMATTING = Regex("§.")

    private fun String.stripFormatting(): String = replace(FORMATTING, "")

    private fun packet(id: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeVarInt(id)
                body()
                flush()
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { framed ->
            framed.writeVarInt(payload.size)
            framed.write(payload)
        }.toByteArray()
    }

    private fun readPacket(input: DataInputStream, expectedId: Int): DataInputStream {
        val length = input.readVarInt()
        if (length <= 0 || length > MAX_PACKET) throw IOException("bad packet length $length")
        val bytes = ByteArray(length).also { input.readFully(it) }
        val packet = DataInputStream(ByteArrayInputStream(bytes))
        val id = packet.readVarInt()
        if (id != expectedId) throw IOException("expected packet $expectedId, got $id")
        return packet
    }

    internal fun OutputStream.writeVarInt(value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                write(remaining)
                return
            }
            write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    internal fun InputStream.readVarInt(): Int {
        var result = 0
        for (shift in 0 until 35 step 7) {
            val byte = read()
            if (byte < 0) throw IOException("stream ended inside a VarInt")
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
        }
        throw IOException("VarInt longer than 5 bytes")
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readVarInt()
        if (length < 0 || length > MAX_PACKET) throw IOException("bad string length $length")
        return String(ByteArray(length).also { readFully(it) }, Charsets.UTF_8)
    }
}
