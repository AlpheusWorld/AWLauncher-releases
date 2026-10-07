package ru.aw.launcher.auth

import com.sun.jna.platform.win32.Crypt32Util
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Shell
import java.util.Base64

object TokenStore {

    private const val DPAPI_PREFIX = "dpapi:"
    private const val UNAVAILABLE_PREFIX = "unavailable:"

    fun protect(secret: String): String {
        if (secret.isEmpty()) return ""
        if (!Shell.isWindows) {
            Log.warn("secure token storage is unavailable on this platform; account must sign in again after restart")
            return UNAVAILABLE_PREFIX
        }

        return runCatching {
            val encrypted = Crypt32Util.cryptProtectData(secret.toByteArray(Charsets.UTF_8))
            DPAPI_PREFIX + Base64.getEncoder().encodeToString(encrypted)
        }.getOrElse {
            Log.warn("DPAPI encryption unavailable; account must sign in again after restart", it)
            UNAVAILABLE_PREFIX
        }
    }

    fun reveal(stored: String): String {
        if (stored.isEmpty()) return ""
        return when {
            stored.startsWith(UNAVAILABLE_PREFIX) -> ""

            stored.startsWith(DPAPI_PREFIX) -> runCatching {
                val blob = Base64.getDecoder().decode(stored.removePrefix(DPAPI_PREFIX))
                String(Crypt32Util.cryptUnprotectData(blob), Charsets.UTF_8)
            }.getOrElse {
                Log.warn("could not decrypt stored token, sign-in required: ${it.message}")
                ""
            }

            stored.startsWith("plain:") -> runCatching {
                String(Base64.getDecoder().decode(stored.removePrefix("plain:")), Charsets.UTF_8)
            }.getOrDefault("")

            else -> stored
        }
    }
}
