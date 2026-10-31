package ru.aw.launcher.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RustHashTest {
    @TempDir lateinit var directory: Path

    @Test fun matchesJvmForDifferentFiles() {
        if (System.getProperty("aw.test.rustHash") == "true") assertTrue(RustHash.available, "Rust library must be loaded")
        else assertFalse(RustHash.available, "Normal builds must use the JVM fallback")
        val cases = listOf(ByteArray(0), "abc".toByteArray(), ByteArray(131_123) { (it * 31).toByte() })
        cases.forEachIndexed { index, bytes ->
            val path = directory.resolve("тест $index.bin")
            Files.write(path, bytes)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
            if (RustHash.available) {
                assertEquals(sha1, RustHash.sha1(path))
                assertEquals(sha256, RustHash.sha256(path))
            }
            assertEquals(sha1, sha1Of(path))
            assertEquals(sha256, sha256Of(path))
            assertTrue(VerifyCache.isValid(path, sha1, bytes.size.toLong()))
            assertFalse(VerifyCache.isValid(path, "0000000000000000000000000000000000000000", bytes.size.toLong()))
        }
        assertNull(RustHash.sha1(directory.resolve("missing")))
        assertNull(RustHash.sha256(directory.resolve("missing")))
        assertThrows(java.io.IOException::class.java) { sha1Of(directory.resolve("missing")) }
        assertThrows(java.io.IOException::class.java) { sha256Of(directory.resolve("missing")) }
    }

    @Test fun concurrentFileHashing() {
        val bytes = ByteArray(130_000) { (it * 17).toByte() }
        val path = directory.resolve("parallel.bin")
        Files.write(path, bytes)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..16).map { pool.submit<String> { sha256Of(path) } }
            tasks.forEach { assertEquals(expected, it.get(10, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
    }
}
