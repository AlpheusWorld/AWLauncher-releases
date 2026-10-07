package ru.aw.launcher.logs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.launch.GameLauncher
import ru.aw.launcher.net.Http
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

enum class LogSource(val label: String) {
    GAME("Игра"),
    CRASH("Крэш-репорт"),
    LAUNCHER("Лаунчер"),
}

object GameLogs {

    private const val VIEW_LINES = 3000
    private const val VIEW_BYTES = 1L shl 20

    private const val SHARE_LINES = 25_000
    private const val SHARE_BYTES = 8L shl 20

    private const val MCLOGS = "https://api.mclo.gs/1/log"

    fun locate(source: LogSource, gameDir: Path?): Path? = runCatching {
        when (source) {
            LogSource.GAME -> gameDir?.let { dir ->
                dir.resolve(GameLauncher.GAME_LOG).takeIf { it.exists() }
                    ?: dir.resolve("logs").resolve("latest.log").takeIf { it.exists() }
            }
            LogSource.CRASH -> gameDir?.resolve("crash-reports")
                ?.takeIf { it.isDirectory() }
                ?.listDirectoryEntries("*.txt")
                ?.maxByOrNull { it.getLastModifiedTime().toMillis() }
            LogSource.LAUNCHER -> Log.currentFile?.takeIf { it.exists() }
        }
    }.getOrNull()

    fun readForView(file: Path): List<String> =
        readable(tail(file, VIEW_BYTES, Int.MAX_VALUE).joinToString("\n")).lines().takeLast(VIEW_LINES)

    fun readForShare(file: Path): String =
        redact(readable(tail(file, SHARE_BYTES, Int.MAX_VALUE).joinToString("\n")).lines().takeLast(SHARE_LINES).joinToString("\n"))

    private val EVENT = Regex("""[ \t]*<log4j:Event\s([^>]*)>(.*?)</log4j:Event>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("""(\w+)="([^"]*)"""")
    private val MESSAGE = Regex("""<log4j:Message><!\[CDATA\[(.*?)]]></log4j:Message>""", RegexOption.DOT_MATCHES_ALL)
    private val THROWABLE = Regex("""<log4j:Throwable><!\[CDATA\[(.*?)]]></log4j:Throwable>""", RegexOption.DOT_MATCHES_ALL)
    private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    internal fun readable(text: String): String {
        if (!text.contains("<log4j:Event")) return text
        val firstOpen = text.indexOf("<log4j:Event")
        val firstClose = text.indexOf("</log4j:Event>")
        val start = if (firstClose in 0 until firstOpen) firstClose + "</log4j:Event>".length else 0

        return EVENT.replace(text.substring(start)) { event ->
            val attributes = ATTRIBUTE.findAll(event.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val time = attributes["timestamp"]?.toLongOrNull()?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(TIME)
            }.orEmpty()
            val body = event.groupValues[2].replace("]]><![CDATA[", "")
            buildString {
                append('[').append(time).append("] [")
                append(attributes["thread"].orEmpty()).append('/').append(attributes["level"].orEmpty()).append("]: ")
                append(MESSAGE.find(body)?.groupValues?.get(1).orEmpty())
                THROWABLE.find(body)?.groupValues?.get(1)?.trimEnd()?.let { append('\n').append(it) }
            }
        }.replace(Regex("""\n\s*\n"""), "\n")
    }

    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
    private val TOKEN_ARGUMENT = Regex("""(--accessToken\s+)\S+""")

    fun redact(text: String, home: String = System.getProperty("user.home").orEmpty()): String {
        var result = text.replace(JWT, "<token>").replace(TOKEN_ARGUMENT, "$1<token>")
        if (home.length > 3) {
            result = result
                .replace(home, "~", ignoreCase = true)
                .replace(home.replace('\\', '/'), "~", ignoreCase = true)
        }
        return result
    }

    suspend fun share(text: String): String = withContext(Dispatchers.IO) {
        parseShareAnswer(Http.postForm(MCLOGS, mapOf("content" to text)))
    }

    internal fun parseShareAnswer(json: String): String {
        val answer = Json.parseToJsonElement(json).jsonObject
        val url = answer["url"]?.jsonPrimitive?.contentOrNull
        if (answer["success"]?.jsonPrimitive?.booleanOrNull != true || url.isNullOrBlank()) {
            throw IOException(answer["error"]?.jsonPrimitive?.contentOrNull ?: "mclo.gs не принял лог")
        }
        return url
    }

    internal fun tail(file: Path, maxBytes: Long, maxLines: Int): List<String> {
        val bytes = RandomAccessFile(file.toFile(), "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt()).also { raf.readFully(it) }
            if (start == 0L) buffer
            else buffer.indexOf('\n'.code.toByte()).let { cut -> if (cut < 0) buffer else buffer.copyOfRange(cut + 1, buffer.size) }
        }
        val lines = decode(bytes).lines()
        return (if (lines.lastOrNull()?.isEmpty() == true) lines.dropLast(1) else lines).takeLast(maxLines)
    }

    private fun decode(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() }.getOrElse {
            val native = runCatching { Charset.forName(System.getProperty("native.encoding")) }
                .getOrDefault(Charset.defaultCharset())
            String(bytes, native)
        }
    }
}
