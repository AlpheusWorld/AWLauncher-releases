package ru.aw.launcher.discord

import java.io.RandomAccessFile
import java.nio.file.Path

sealed interface GameEvent {
    data class JoinedServer(val address: String) : GameEvent
    data object Singleplayer : GameEvent
    data object Menu : GameEvent
}

object GameEvents {

    private const val DEFAULT_PORT = "25565"
    private const val MAX_CHUNK = 1 shl 20

    private val CONNECT = Regex("""Connecting to ([^\s,\]]+), (\d+)""")
    private const val INTEGRATED = "Starting integrated minecraft server"
    private val DISCONNECT = Regex("(?:\\[.*(?:INFO|WARN).*]: |<\\!\\[CDATA\\[)(?:Stopping server|Disconnecting from server|Disconnected from server)(?:$|[.<])")

    fun parse(line: String): GameEvent? {
        if ("[CHAT]" in line) return null
        CONNECT.find(line)?.let { match ->
            val (host, port) = match.destructured
            return GameEvent.JoinedServer(address(host, port))
        }
        if (INTEGRATED in line) return GameEvent.Singleplayer
        if (DISCONNECT.containsMatchIn(line)) return GameEvent.Menu
        return null
    }

    fun address(host: String, port: String = DEFAULT_PORT): String {
        val clean = host.trim().removeSuffix(".").lowercase()
        return if (port == DEFAULT_PORT || port.isBlank()) clean else "$clean:$port"
    }

    fun display(serverAddress: String): String {
        val trimmed = serverAddress.trim()
        val host = trimmed.substringBeforeLast(':', trimmed)
        val port = trimmed.substringAfterLast(':', "").takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
        return if (port == null) address(trimmed) else address(host, port)
    }

    class Tail(private val file: Path) {
        private var offset = 0L
        private val pending = StringBuilder()

        fun lines(): List<String> {
            val chunk = runCatching {
                RandomAccessFile(file.toFile(), "r").use { raf ->
                    if (raf.length() < offset) { offset = 0; pending.setLength(0) }
                    val size = (raf.length() - offset).coerceAtMost(MAX_CHUNK.toLong()).toInt()
                    if (size <= 0) return emptyList()
                    raf.seek(offset)
                    ByteArray(size).also { raf.readFully(it); offset += size }
                }
            }.getOrNull() ?: return emptyList()
            pending.append(chunk.decodeToString())
            if (pending.length > MAX_CHUNK * 2) pending.delete(0, pending.length - MAX_CHUNK)
            val complete = pending.lastIndexOf("\n")
            if (complete < 0) return emptyList()
            val text = pending.substring(0, complete)
            pending.delete(0, complete + 1)
            return text.split('\n')
        }
    }
}
