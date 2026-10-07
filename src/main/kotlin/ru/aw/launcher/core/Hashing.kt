package ru.aw.launcher.core

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
    return out
}

fun sha1Of(stream: InputStream, buffer: ByteArray = ByteArray(64 * 1024)): String {
    val md = MessageDigest.getInstance("SHA-1")
    while (true) {
        val n = stream.read(buffer)
        if (n <= 0) break
        md.update(buffer, 0, n)
    }
    return md.digest().toHex()
}

fun sha1Of(path: Path): String = Files.newInputStream(path).buffered(64 * 1024).use { sha1Of(it) }
