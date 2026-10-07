package ru.aw.launcher.servers

import ru.aw.launcher.core.Log
import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.InitialDirContext

data class ServerAddress(val host: String, val port: Int?) {

    val effectivePort: Int get() = port ?: DEFAULT_PORT

    override fun toString(): String {
        val shownHost = if (':' in host) "[$host]" else host
        return if (port == null) shownHost else "$shownHost:$port"
    }

    companion object {
        const val DEFAULT_PORT = 25565

        fun parse(raw: String): ServerAddress? {
            val text = raw.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null

            if (text.startsWith("[")) {
                val end = text.indexOf(']')
                if (end <= 1) return null
                val rest = text.substring(end + 1)
                val port = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.drop(1).toPortOrNull() ?: return null
                    else -> return null
                }
                return ServerAddress(text.substring(1, end), port)
            }

            return when (text.count { it == ':' }) {
                0 -> ServerAddress(text, null)
                1 -> {
                    val host = text.substringBefore(':').ifEmpty { return null }
                    ServerAddress(host, text.substringAfter(':').toPortOrNull() ?: return null)
                }
                else -> ServerAddress(text, null)
            }
        }

        private fun String.toPortOrNull(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }
    }
}

object SrvResolver {

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun resolve(host: String): ServerAddress? {
        if (IPV4.matches(host) || ':' in host) return null
        return runCatching {
            val env = Hashtable<String, String>().apply {
                put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
                put("com.sun.jndi.dns.timeout.initial", "1500")
                put("com.sun.jndi.dns.timeout.retries", "1")
            }
            val context = InitialDirContext(env)
            try {
                val records = context.getAttributes("_minecraft._tcp.$host", arrayOf("SRV")).get("SRV")
                    ?: return@runCatching null
                (0 until records.size())
                    .map { (records.get(it) as String).trim().split(' ') }
                    .filter { it.size >= 4 }
                    .minByOrNull { it[0].toIntOrNull() ?: Int.MAX_VALUE }
                    ?.let { ServerAddress(it[3].removeSuffix("."), it[2].toInt()) }
            } finally {
                context.close()
            }
        }.onFailure { Log.debug("no SRV record for $host: ${it.message}") }.getOrNull()
    }
}
