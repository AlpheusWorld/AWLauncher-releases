package ru.aw.launcher.discord

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.aw.launcher.core.Shell
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class GameConnectionTest {
    private val peer = GameConnection.Peer(InetAddress.getByName("127.0.0.1"), 25565)

    @Test
    fun `closed game socket resets status only after two confirmed samples`() {
        var active: Set<GameConnection.Peer>? = setOf(peer)
        val monitor = GameConnection(123, { setOf(peer) }, { assertEquals(123L, it); active })
        assertFalse(monitor.disconnected("localhost"))
        active = emptySet()
        assertFalse(monitor.disconnected("localhost"))
        assertTrue(monitor.disconnected("localhost"))
    }

    @Test
    fun `unknown snapshots never mean disconnected and break a missing streak`() {
        var active: Set<GameConnection.Peer>? = setOf(peer)
        val monitor = GameConnection(1, { setOf(peer) }, { active })
        assertFalse(monitor.disconnected("localhost"))
        active = emptySet()
        assertFalse(monitor.disconnected("localhost"))
        active = null
        repeat(3) { assertFalse(monitor.disconnected("localhost")) }
        active = emptySet()
        assertFalse(monitor.disconnected("localhost"))
        assertTrue(monitor.disconnected("localhost"))
    }

    @Test
    fun `server changes and menu clear previous connection observations`() {
        var active = setOf(peer)
        val monitor = GameConnection(1, { setOf(peer) }, { active })
        assertFalse(monitor.disconnected("first"))
        active = emptySet()
        repeat(3) { assertFalse(monitor.disconnected("second")) }
        active = setOf(peer)
        assertFalse(monitor.disconnected("second"))
        assertFalse(monitor.disconnected(null))
        active = emptySet()
        repeat(3) { assertFalse(monitor.disconnected("second")) }
    }

    @Test
    fun `quick reconnect and unrelated sockets do not give false results`() {
        var active = setOf(peer)
        val monitor = GameConnection(1, { setOf(peer) }, { active })
        assertFalse(monitor.disconnected("localhost"))
        active = setOf(peer.copy(port = 443))
        assertFalse(monitor.disconnected("localhost"))
        active = setOf(peer)
        assertFalse(monitor.disconnected("localhost"))
        active = emptySet()
        assertFalse(monitor.disconnected("localhost"))
        assertTrue(monitor.disconnected("localhost"))
    }

    @Test
    fun `unresolved addresses do not clear a live game`() {
        val monitor = GameConnection(1, { emptySet() }, { error("No snapshot needed without a resolved peer") })
        repeat(4) { assertFalse(monitor.disconnected("unresolved")) }
    }

    @Test
    fun `native snapshot observes current process established socket and its closure`() {
        assumeTrue(Shell.isWindows || Shell.isLinux)
        checkSocket(InetAddress.getByName("127.0.0.1"))
    }

    @Test
    fun `native snapshot also handles IPv6 sockets`() {
        assumeTrue(Shell.isWindows || Shell.isLinux)
        val address = InetAddress.getByName("::1")
        assumeTrue(runCatching { ServerSocket(0, 1, address).use { } }.isSuccess)
        checkSocket(address)
    }

    private fun checkSocket(address: InetAddress) {
        ServerSocket(0, 1, address).use { server ->
            val remote = GameConnection.Peer(address, server.localPort)
            Socket(address, server.localPort).use { client ->
                server.accept().use {
                    assertTrue(GameConnection.readConnections(ProcessHandle.current().pid())?.contains(remote) == true)
                    client.close()
                    assertFalse(GameConnection.readConnections(ProcessHandle.current().pid())?.contains(remote) ?: true)
                }
            }
        }
    }

    @Test
    fun `proc snapshot filters by socket ownership and established state`(@TempDir process: Path) {
        assumeTrue(Shell.isLinux)
        Files.createDirectories(process.resolve("fd"))
        Files.createDirectories(process.resolve("net"))
        Files.createSymbolicLink(process.resolve("fd/1"), Path.of("socket:[42]"))
        process.resolve("net/tcp").writeText("header\n0: 0100007F:1111 0100007F:63DD 01 0 0 0 0 0 42\n" +
            "1: 0100007F:1111 0100007F:01BB 01 0 0 0 0 0 43\n" +
            "2: 0100007F:1111 0100007F:0050 08 0 0 0 0 0 42\n")
        assertEquals(setOf(peer), GameConnection.linuxConnections(process))
        assertEquals(InetAddress.getByName("::1"), GameConnection.procPeer("00000000000000000000000001000000:63DD")?.address)
    }
}
