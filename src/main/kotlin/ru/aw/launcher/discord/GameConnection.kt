package ru.aw.launcher.discord

import com.sun.jna.Memory
import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.ptr.IntByReference
import ru.aw.launcher.core.Shell
import ru.aw.launcher.servers.ServerAddress
import ru.aw.launcher.servers.SrvResolver
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Log messages are not guaranteed on disconnect. Observe only sockets owned by this game process. */
internal class GameConnection(
    private val pid: Long,
    private val resolve: (String) -> Set<Peer> = ::resolvePeers,
    private val connections: (Long) -> Set<Peer>? = ::readConnections,
) {
    internal data class Peer(val address: InetAddress, val port: Int)

    private var server: String? = null
    private var peers = emptySet<Peer>()
    private var observed = false
    private var missing = 0

    fun disconnected(address: String?): Boolean {
        if (address != server) {
            server = address
            peers = address?.let(resolve).orEmpty()
            observed = false
            missing = 0
        }
        if (address == null || peers.isEmpty()) return false
        val active = connections(pid) ?: run { missing = 0; return false }
        if (active.any { it in peers }) {
            observed = true
            missing = 0
        } else if (observed) {
            // Two successful samples avoid clearing the card for a transient snapshot or a fast reconnect.
            missing++
            return missing >= 2
        }
        return false
    }

    companion object {
        private const val OWNER_PID_CONNECTIONS = 4
        private const val ESTABLISHED = 5
        private const val INSUFFICIENT_BUFFER = 122

        private fun resolvePeers(raw: String): Set<Peer> = runCatching {
            val address = ServerAddress.parse(raw) ?: return emptySet()
            val target = if (address.port == null) SrvResolver.resolve(address.host) ?: address else address
            InetAddress.getAllByName(target.host).map { Peer(it, target.effectivePort) }.toSet()
        }.getOrDefault(emptySet())

        internal fun readConnections(pid: Long): Set<Peer>? = try {
            when {
                Shell.isWindows -> windowsConnections(pid)
                Shell.isLinux -> linuxConnections(Path.of("/proc", pid.toString()))
                else -> null
            }
        } catch (_: Exception) { null }
        catch (_: LinkageError) { null }

        private fun windowsConnections(pid: Long): Set<Peer> = buildSet {
            for (family in listOf(IPHlpAPI.AF_INET, IPHlpAPI.AF_INET6)) {
                val size = IntByReference()
                var result = IPHlpAPI.INSTANCE.GetExtendedTcpTable(null, size, false, family, OWNER_PID_CONNECTIONS, 0)
                if (result != 0 && result != INSUFFICIENT_BUFFER) throw IOException("Could not read game TCP connections: $result")
                var complete = false
                repeat(3) {
                    if (complete) return@repeat
                    if (size.value !in 4..16_777_216) throw IOException("Invalid TCP table size")
                    Memory(size.value.toLong()).use { memory ->
                        result = IPHlpAPI.INSTANCE.GetExtendedTcpTable(memory, size, false, family, OWNER_PID_CONNECTIONS, 0)
                        if (result == INSUFFICIENT_BUFFER) return@use
                        if (result != 0) throw IOException("Could not read game TCP connections: $result")
                        if (family == IPHlpAPI.AF_INET) {
                            IPHlpAPI.MIB_TCPTABLE_OWNER_PID(memory).table
                                .filter { (it.dwOwningPid.toLong() and 0xffffffffL) == pid && it.dwState == ESTABLISHED }
                                .forEach { row ->
                                    val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(row.dwRemoteAddr).array()
                                    add(Peer(InetAddress.getByAddress(bytes), networkPort(row.dwRemotePort)))
                                }
                        } else {
                            IPHlpAPI.MIB_TCP6TABLE_OWNER_PID(memory).table
                                .filter { (it.dwOwningPid.toLong() and 0xffffffffL) == pid && it.State == ESTABLISHED }
                                .forEach { add(Peer(InetAddress.getByAddress(it.RemoteAddr), networkPort(it.dwRemotePort))) }
                        }
                        complete = true
                    }
                }
                if (!complete) throw IOException("TCP table changed while reading")
            }
        }

        private fun networkPort(port: Int) = ((port and 255) shl 8) or ((port ushr 8) and 255)

        internal fun linuxConnections(process: Path): Set<Peer> {
            val sockets = Files.list(process.resolve("fd")).use { files ->
                files.map { runCatching { Files.readSymbolicLink(it).toString() }.getOrNull().orEmpty() }
                    .filter { it.startsWith("socket:[") && it.endsWith(']') }
                    .map { it.removePrefix("socket:[").removeSuffix("]") }.toList().toSet()
            }
            return buildSet {
                for (name in listOf("tcp", "tcp6")) {
                    val table = process.resolve("net/$name")
                    if (name == "tcp6" && !Files.exists(table)) continue
                    Files.newBufferedReader(table).useLines { lines ->
                        lines.drop(1).forEach { line ->
                            val fields = line.trim().split(Regex("\\s+"))
                            if (fields.size > 9 && fields[3] == "01" && fields[9] in sockets) {
                                procPeer(fields[2])?.let(::add)
                            }
                        }
                    }
                }
            }
        }

        internal fun procPeer(value: String): Peer? = runCatching {
            val (host, port) = value.split(':', limit = 2)
            if (host.length != 8 && host.length != 32) return null
            val buffer = ByteBuffer.allocate(host.length / 2).order(ByteOrder.nativeOrder())
            host.chunked(8).forEach { buffer.putInt(it.toLong(16).toInt()) }
            Peer(InetAddress.getByAddress(buffer.array()), port.toInt(16).also { require(it in 1..65535) })
        }.getOrNull()
    }
}
