package ru.aw.launcher.auth

import ru.aw.launcher.core.Log
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal class LoopbackReceiver : AutoCloseable {

    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())

    val port: Int = server.localPort

    val redirectUri: String = "http://localhost:$port"

    fun awaitCode(expectedState: String, timeoutMillis: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AuthException("Истекло время ожидания входа (5 минут)")
            server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                throw AuthException("Истекло время ожидания входа (5 минут)")
            }

            socket.use { client ->
                val target = readRequestTarget(client) ?: return@use
                val query = target.substringAfter('?', "")
                if (query.isBlank()) {
                    respond(client, 404, "<h1>404</h1>")
                    return@use
                }

                val params = parseQuery(query)

                params["error"]?.let { error ->
                    val description = params["error_description"] ?: error
                    respond(client, 400, page("Вход отменён", description, ok = false))
                    throw AuthException("Microsoft вернул ошибку: $description")
                }

                val code = params["code"] ?: run {
                    respond(client, 404, "<h1>404</h1>")
                    return@use
                }

                if (params["state"] != expectedState) {
                    respond(client, 400, page("Ошибка безопасности", "Не совпал параметр state.", ok = false))
                    throw AuthException("Не совпал параметр state — вход отклонён")
                }

                respond(client, 200, page("Готово", "Вход выполнен. Вернитесь в AWLauncher.", ok = true))
                return code
            }
        }
    }

    private fun readRequestTarget(socket: Socket): String? = runCatching {
        val reader: BufferedReader = socket.getInputStream()
            .bufferedReader(StandardCharsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return@runCatching null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        requestLine.split(' ').getOrNull(1)
    }.onFailure { Log.debug("loopback read failed: ${it.message}") }.getOrNull()

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                runCatching {
                    URLDecoder.decode(name, StandardCharsets.UTF_8) to
                        URLDecoder.decode(value, StandardCharsets.UTF_8)
                }.getOrNull()
            }
            .toMap()

    private fun respond(socket: Socket, status: Int, html: String) {
        runCatching {
            val body = html.toByteArray(StandardCharsets.UTF_8)
            val reason = if (status == 200) "OK" else "Error"
            val header = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-store\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
        }
    }

    private fun page(title: String, message: String, ok: Boolean): String {
        val accent = if (ok) "#32c879" else "#d94b53"
        val safeTitle = ru.aw.launcher.core.I18n.text(title).escapeHtml()
        val safeMessage = ru.aw.launcher.core.I18n.text(message).escapeHtml()
        return """
            <!doctype html>
            <html lang="${ru.aw.launcher.core.Settings.current.language.tag}"><head><meta charset="utf-8">
            <title>AWLauncher</title>
            <style>
              :root { color-scheme: dark; }
              body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                     background:#0f1115; color:#e6e8ee;
                     font:16px/1.6 -apple-system,Segoe UI,Roboto,sans-serif; }
              .card { text-align:center; padding:48px 56px; background:#171a21; border-radius:16px;
                      border:1px solid #232733; max-width:420px; }
              h1 { margin:0 0 12px; font-size:22px; color:$accent; }
              p { margin:0; color:#9aa3b2; }
            </style></head>
            <body><div class="card"><h1>$safeTitle</h1><p>$safeMessage</p></div></body></html>
        """.trimIndent()
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    override fun close() {
        runCatching { server.close() }
    }
}
