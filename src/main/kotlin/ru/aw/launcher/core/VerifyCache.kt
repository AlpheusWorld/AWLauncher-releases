package ru.aw.launcher.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists

object VerifyCache {

    private const val MAGIC = 0x4A555831
    private const val FORMAT = 2

    private class Entry(val size: Long, val mtime: Long, val sha1: ByteArray)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val dirty = AtomicBoolean(false)

    fun load() {
        val file = Paths.verifyCacheFile
        if (!file.exists()) return
        runCatching {
            DataInputStream(Files.newInputStream(file).buffered(1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT) return
                val count = input.readInt()
                repeat(count) {
                    val key = input.readUTF()
                    val size = input.readLong()
                    val mtime = input.readLong()
                    val sha1 = ByteArray(20).also { input.readFully(it) }
                    entries[key] = Entry(size, mtime, sha1)
                }
            }
            Log.debug("verify cache loaded: ${entries.size} entries")
        }.onFailure {
            Log.warn("verify cache unreadable, starting fresh", it)
            entries.clear()
        }
    }

    @Synchronized
    fun save() {
        if (!dirty.getAndSet(false)) return
        runCatching {
            Paths.cache.let { Files.createDirectories(it) }
            val snapshot = entries.entries.toList()
            val bytes = java.io.ByteArrayOutputStream(snapshot.size * 64).also { bos ->
                DataOutputStream(bos).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(FORMAT)
                    out.writeInt(snapshot.size)
                    for ((key, e) in snapshot) {
                        out.writeUTF(key)
                        out.writeLong(e.size)
                        out.writeLong(e.mtime)
                        out.write(e.sha1)
                    }
                }
            }.toByteArray()
            Paths.verifyCacheFile.writeAtomically(bytes)
            Log.debug("verify cache saved: ${snapshot.size} entries")
        }.onFailure { Log.warn("could not save verify cache", it) }
    }

    fun clear() {
        entries.clear()
        dirty.set(true)
        save()
    }

    private fun key(path: Path): String =
        runCatching { Paths.root.relativize(path).toString() }.getOrElse { path.toString() }

    fun isValid(path: Path, expectedSha1: String?, expectedSize: Long?): Boolean {
        val attrs = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        }.getOrNull() ?: return false
        if (!attrs.isRegularFile) return false

        if (expectedSize != null && attrs.size() != expectedSize) return false
        if (expectedSha1 == null) return true

        val k = key(path)
        val mtime = attrs.lastModifiedTime().toMillis()
        val cached = entries[k]
        if (cached != null && cached.size == attrs.size() && cached.mtime == mtime) {
            return cached.sha1.toHex().equals(expectedSha1, ignoreCase = true)
        }

        val actual = runCatching { sha1Of(path) }.getOrElse { return false }
        remember(k, attrs.size(), mtime, actual)
        return actual.equals(expectedSha1, ignoreCase = true)
    }

    fun record(path: Path, sha1: String) {
        val attrs = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        }.getOrNull() ?: return
        remember(key(path), attrs.size(), attrs.lastModifiedTime().toMillis(), sha1)
    }

    private fun remember(key: String, size: Long, mtime: Long, sha1: String) {
        entries[key] = Entry(size, mtime, sha1.hexToBytes())
        dirty.set(true)
    }
}
