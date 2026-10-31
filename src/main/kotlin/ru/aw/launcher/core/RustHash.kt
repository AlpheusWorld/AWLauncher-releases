package ru.aw.launcher.core

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Optional native SHA-1 implementation. The JVM implementation remains the fallback. */
internal object RustHash {
    private interface Api : Library {
        fun aw_sha1(path: ByteArray, len: Long, output: ByteArray): Int
        fun aw_sha256(path: ByteArray, len: Long, output: ByteArray): Int
    }

    private val api: Api? by lazy {
        runCatching {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
            if (arch !in setOf("amd64", "x86_64")) return@runCatching null
            val resource = when {
                os.contains("windows") -> "/native/windows-x86_64/aw_hash.dll"
                os.contains("linux") -> "/native/linux-x86_64/libaw_hash.so"
                else -> return@runCatching null
            }
            val stream = RustHash::class.java.getResourceAsStream(resource) ?: return@runCatching null
            val suffix = if (os.contains("windows")) ".dll" else ".so"
            val library = Files.createTempFile("aw-hash-", suffix)
            library.toFile().deleteOnExit()
            stream.use { Files.copy(it, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            Native.load(library.toAbsolutePath().toString(), Api::class.java)
        }.getOrNull()
    }

    internal val available: Boolean get() = api != null

    fun sha1(path: Path): String? = hash(path, 20) { native, bytes, output ->
        native.aw_sha1(bytes, bytes.size.toLong(), output)
    }

    fun sha256(path: Path): String? = hash(path, 32) { native, bytes, output ->
        native.aw_sha256(bytes, bytes.size.toLong(), output)
    }

    private inline fun hash(path: Path, size: Int, call: (Api, ByteArray, ByteArray) -> Int): String? = runCatching {
        val native = api ?: return null
        val bytes = path.toAbsolutePath().toString().toByteArray(Charsets.UTF_8)
        val result = ByteArray(size)
        if (call(native, bytes, result) != 0) return null
        result.toHex()
    }.getOrNull()
}
