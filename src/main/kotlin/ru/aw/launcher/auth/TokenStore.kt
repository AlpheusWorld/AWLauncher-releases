package ru.aw.launcher.auth

import com.sun.jna.platform.win32.Crypt32Util
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Shell
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TokenStore {

    private const val DPAPI_PREFIX = "dpapi:"
    private const val SECRET_PREFIX = "secret-service:"
    private const val UNAVAILABLE_PREFIX = "unavailable:"
    private val random = SecureRandom()
    @Volatile private var linuxKey: SecretKeySpec? = null

    fun protect(secret: String): String {
        if (secret.isEmpty()) return ""
        if (Shell.isLinux) return runCatching {
            SECRET_PREFIX + encrypt(secret, keyFromSecretService())
        }.getOrElse {
            Log.warn("Secret Service token storage unavailable; sign-in will be required after restart: ${it.message}")
            UNAVAILABLE_PREFIX
        }
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

            stored.startsWith(SECRET_PREFIX) -> if (Shell.isLinux) runCatching {
                decrypt(stored.removePrefix(SECRET_PREFIX), keyFromSecretService(create = false))
            }.getOrElse {
                Log.warn("could not decrypt Secret Service token; sign-in required: ${it.message}")
                ""
            } else ""

            stored.startsWith(DPAPI_PREFIX) -> if (Shell.isWindows) runCatching {
                val blob = Base64.getDecoder().decode(stored.removePrefix(DPAPI_PREFIX))
                String(Crypt32Util.cryptUnprotectData(blob), Charsets.UTF_8)
            }.getOrElse {
                Log.warn("could not decrypt stored token, sign-in required: ${it.message}")
                ""
            } else ""

            stored.startsWith("plain:") -> runCatching {
                String(Base64.getDecoder().decode(stored.removePrefix("plain:")), Charsets.UTF_8)
            }.getOrDefault("")

            else -> stored
        }
    }

    internal fun encrypt(secret: String, key: SecretKeySpec): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(SECRET_PREFIX.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
    }

    internal fun decrypt(encoded: String, key: SecretKeySpec): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size >= 28) { "Invalid encrypted token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(SECRET_PREFIX.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    internal fun protectPreserving(secret: String, previous: String?): String {
        val sealed = previous?.takeIf { it.startsWith(DPAPI_PREFIX) || it.startsWith(SECRET_PREFIX) }
        if (secret.isEmpty() && sealed != null) return sealed
        return protect(secret).let { if (it == UNAVAILABLE_PREFIX && sealed != null) sealed else it }
    }

    @Synchronized
    private fun keyFromSecretService(create: Boolean = true): SecretKeySpec {
        linuxKey?.let { return it }
        val tool = listOf("/usr/bin/secret-tool", "/bin/secret-tool").map { Path.of(it) }
            .firstOrNull { Files.isExecutable(it) } ?: throw IOException("Install libsecret-tools and a Secret Service keyring")
        val attributes = listOf("application", "awlauncher", "purpose", "token-encryption-v1")
        var encoded = secretTool(tool, listOf("lookup") + attributes).orEmpty()
        if (encoded.isBlank()) {
            if (!create) throw IOException("The token encryption key is unavailable")
            encoded = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))
            secretTool(tool, listOf("store", "--label=AWLauncher token encryption") + attributes, encoded)
                ?: throw IOException("The keyring could not store the encryption key")
            encoded = secretTool(tool, listOf("lookup") + attributes)
                ?: throw IOException("The keyring could not read the encryption key")
        }
        val key = Base64.getDecoder().decode(encoded)
        require(key.size == 32) { "Invalid token encryption key" }
        return SecretKeySpec(key, "AES").also { linuxKey = it }
    }

    private fun secretTool(tool: Path, arguments: List<String>, input: String? = null): String? {
        val process = ProcessBuilder(listOf(tool.toString()) + arguments).start()
        try {
            process.outputStream.use { output -> if (input != null) output.write(input.toByteArray(Charsets.UTF_8)) }
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw IOException("Secret Service request timed out")
            val errors = process.errorStream.use { it.readNBytes(4097) }
            if (process.exitValue() != 0) {
                if (errors.isNotEmpty()) throw IOException("Secret Service is unavailable or locked")
                return null
            }
            return process.inputStream.use { stream ->
                val bytes = stream.readNBytes(257)
                if (bytes.size > 256) throw IOException("Invalid Secret Service response")
                String(bytes, Charsets.UTF_8).trim()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Secret Service request interrupted", error)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}
