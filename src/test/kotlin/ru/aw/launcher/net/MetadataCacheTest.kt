package ru.aw.launcher.net

import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetadataCacheTest {
    @Test fun `metadata expires and explicit refresh bypasses a fresh entry`() {
        var time = 0L
        val cache = Http.MetadataCache(clock = { time })
        val calls = AtomicInteger()
        fun get(refresh: Boolean = false) = cache.get("project", 100, refresh) { calls.incrementAndGet().toString() }
        assertEquals("1", get()); assertEquals("1", get())
        assertEquals("2", get(true)); assertEquals("2", get())
        time = TimeUnit.MILLISECONDS.toNanos(101)
        assertEquals("3", get())
    }

    @Test fun `concurrent identical requests perform one load and failures remain retryable`() {
        val cache = Http.MetadataCache()
        val pool = Executors.newFixedThreadPool(4)
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = pool.submit<String> { cache.get("same", 1000) {
                calls.incrementAndGet(); started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); "response"
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val others = (1..3).map { pool.submit<String> { cache.get("same", 1000) { calls.incrementAndGet(); "duplicate" } } }
            finish.countDown()
            assertEquals("response", first.get(5, TimeUnit.SECONDS))
            others.forEach { assertEquals("response", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
            assertThrows<IOException> { cache.get("failed", 1000) { throw IOException("offline") } }
            assertEquals("recovered", cache.get("failed", 1000) { "recovered" })
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun `an older request cannot overwrite a forced fresh result`() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val cache = Http.MetadataCache(clock = clock::get)
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        try {
            val old = pool.submit<String> { cache.get("project", 1000) {
                started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); "old"
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS)); clock.set(1)
            assertEquals("fresh", cache.get("project", 1000, true) { "fresh" })
            finish.countDown(); assertEquals("old", old.get(5, TimeUnit.SECONDS))
            assertEquals("fresh", cache.get("project", 1000) { "unexpected" })
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun `metadata cache evicts least recently used entries within its size bound`() {
        val cache = Http.MetadataCache(maxBytes = 8, maxEntries = 2)
        cache.get("a", 1000) { "aa" }; cache.get("b", 1000) { "bb" }
        assertEquals("aa", cache.get("a", 1000) { "wrong" })
        cache.get("c", 1000) { "cc" }
        assertEquals("new b", cache.get("b", 1000) { "new b" })
    }

    @Test fun `downloads authentication and mutating endpoints bypass metadata caching`() {
        fun ttl(url: String, auth: Boolean = false) = Http.metadataTtl(Request.Builder().url(url)
            .apply { if (auth) header("Authorization", "Bearer test") }.build())
        assertNotNull(ttl("https://api.modrinth.com/v2/project/test"))
        assertNotNull(ttl("https://awlauncher.zarplaykayt.chatgpt.site/api/curseforge/mods/51/files?index=0"))
        assertNull(ttl("https://awlauncher.zarplaykayt.chatgpt.site/api/curseforge/mods/51/files/102/download"))
        assertNull(ttl("https://api.modrinth.com/v2/project/test", auth = true))
        assertNull(ttl("https://api.minecraftservices.com/minecraft/profile"))
        assertNull(ttl("https://github.com/AlpheusWorld/AWLauncher-releases/releases/latest/download/update.json"))
    }
}
