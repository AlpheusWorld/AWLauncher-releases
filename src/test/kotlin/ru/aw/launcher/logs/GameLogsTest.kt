package ru.aw.launcher.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class GameLogsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `tokens and the user folder never leave the machine`() {
        val home = "C:\\Users\\Ivan"
        val log = "Loading from C:\\Users\\Ivan\\.aw and C:/Users/Ivan/.aw\n" +
            "--accessToken secret123 --uuid 1234\n" +
            "token eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0NTY3.c2lnbmF0dXJlLXZhbHVl here"

        assertEquals(
            "Loading from ~\\.aw and ~/.aw\n" +
                "--accessToken <token> --uuid 1234\n" +
                "token <token> here",
            GameLogs.redact(log, home),
        )
    }

    @Test
    fun `the tail starts on a whole line and keeps the last ones`() {
        val file = dir.resolve("latest.log")
        file.writeText((1..200).joinToString("\n") { "line number $it" } + "\n")

        val lines = GameLogs.tail(file, maxBytes = 120, maxLines = 5)

        assertEquals((196..200).map { "line number $it" }, lines)
        assertEquals("line number 200", GameLogs.tail(file, maxBytes = 1_000_000, maxLines = 1).single())
    }

    @Test
    fun `a log in the system code page is read in it`() {
        val native = Charset.forName(System.getProperty("native.encoding") ?: "UTF-8")
        // An English Windows runner cannot encode Cyrillic in its native code page.
        val line = if (native.newEncoder().canEncode("Игрок Вася зашёл")) "Игрок Вася зашёл" else "Player joined from café"
        org.junit.jupiter.api.Assumptions.assumeTrue(native.newEncoder().canEncode(line))
        val file = dir.resolve("cp.log")
        file.writeBytes("$line\n".toByteArray(native))

        assertEquals(listOf(line), GameLogs.tail(file, 1_000, 10))
    }

    @Test
    fun `the vanilla console's log4j XML becomes ordinary log lines`() {
        val time = java.time.Instant.ofEpochMilli(1790097380219)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        val xml = """
            |tAdvancementTree" timestamp="1" level="INFO" thread="x">
            |  <log4j:Message><![CDATA[cut in half]]></log4j:Message>
            |</log4j:Event>
            |Error: could not open `jvm.cfg'
            |  <log4j:Event logger="net.minecraft.client.Minecraft" timestamp="1790097380219" level="ERROR" thread="Render thread">
            |    <log4j:Message><![CDATA[Crash with ]]]]><![CDATA[> in it]]></log4j:Message>
            |    <log4j:Throwable><![CDATA[java.lang.IllegalStateException: boom
            |	at net.minecraft.client.Minecraft.run(Minecraft.java:900)
            |]]></log4j:Throwable>
            |  </log4j:Event>
            |  <log4j:Event logger="a" timestamp="1790097380219" level="INFO" thread="Worker-Main-7">
            |    <log4j:Message><![CDATA[Stopping!]]></log4j:Message>
            |  </log4j:Event>
            |""".trimMargin()

        assertEquals(
            listOf(
                "Error: could not open `jvm.cfg'",
                "[$time] [Render thread/ERROR]: Crash with ]]> in it",
                "java.lang.IllegalStateException: boom",
                "\tat net.minecraft.client.Minecraft.run(Minecraft.java:900)",
                "[$time] [Worker-Main-7/INFO]: Stopping!",
            ),
            GameLogs.readable(xml).trim('\n').lines(),
        )
        assertEquals("plain\ntext", GameLogs.readable("plain\ntext"))
    }

    @Test
    fun `mclo_gs answers are read`() {
        assertEquals("https://mclo.gs/AbC12", GameLogs.parseShareAnswer("""{"success":true,"id":"AbC12","url":"https://mclo.gs/AbC12"}"""))
        val error = assertThrows<IOException> { GameLogs.parseShareAnswer("""{"success":false,"error":"Log too large"}""") }
        assertEquals("Log too large", error.message)
    }
}
