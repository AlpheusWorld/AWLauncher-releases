package ru.aw.launcher.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText

class GameEventsTest {

    @Test
    fun `joining a server shows its address, default port hidden`() {
        assertEquals(
            GameEvent.JoinedServer("play.example.org"),
            GameEvents.parse("[20:41:50] [Render thread/INFO]: Connecting to play.example.org, 25565"),
        )
        assertEquals(
            GameEvent.JoinedServer("play.example.org:25570"),
            GameEvents.parse("""    <log4j:Message><![CDATA[Connecting to Play.Example.org, 25570]]></log4j:Message>"""),
        )
    }

    @Test
    fun `voice chat and other noise are ignored, singleplayer is noticed`() {
        assertNull(GameEvents.parse("[20:15:15] [Render thread/INFO]: [voicechat] Connecting to voice chat server: '95.182.102.54:24450'"))
        assertNull(GameEvents.parse("[20:36:01] [Render thread/INFO]: Loaded 10 advancements"))
        assertEquals(GameEvent.Singleplayer, GameEvents.parse("[21:00:00] [Server thread/INFO]: Starting integrated minecraft server version 1.21.11"))
    }

    @Test
    fun `launcher button and in-game join read the same`() {
        assertEquals("play.example.org", GameEvents.display("play.example.org"))
        assertEquals("play.example.org", GameEvents.display("PLAY.EXAMPLE.ORG:25565"))
        assertEquals("mc.example.org:25570", GameEvents.display("mc.example.org:25570"))
    }

    @Test
    fun `leaving a world returns to the menu but chat cannot spoof the current server`() {
        assertEquals(GameEvent.Menu, GameEvents.parse("[20:15:15] [Server thread/INFO]: Stopping server"))
        assertEquals(GameEvent.Menu, GameEvents.parse("[20:15:15] [Render thread/INFO]: Disconnected from server"))
        assertNull(GameEvents.parse("[20:15:15] [Render thread/INFO]: [CHAT] Connecting to evil.example.org, 25565"))
        assertNull(GameEvents.parse("[20:15:15] [Render thread/INFO]: [voicechat] Disconnected from server"))
    }

    @Test
    fun `log truncation drops an unfinished old line`(@TempDir dir: Path) {
        val log = dir.resolve("game.log").apply { writeText("old unfinished log line") }
        val tail = GameEvents.Tail(log)
        assertEquals(emptyList<String>(), tail.lines())
        log.writeText("new\n")
        assertEquals(listOf("new"), tail.lines())
    }

    @Test
    fun `the tail reads only new complete lines`(@TempDir dir: Path) {
        val log = dir.resolve("latest-game.log").apply { writeText("first\nsecond") }
        val tail = GameEvents.Tail(log)
        assertEquals(listOf("first"), tail.lines())
        log.appendText(" half\nthird\n")
        assertEquals(listOf("second half", "third"), tail.lines())
        assertEquals(emptyList<String>(), tail.lines())
    }
}
