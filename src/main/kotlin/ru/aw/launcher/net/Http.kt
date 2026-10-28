package ru.aw.launcher.net

import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.mods.CurseForge
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.security.MessageDigest
import kotlin.io.path.createDirectories

object Http {
    internal class MetadataCache(
        private val maxBytes: Long = 32L * 1024 * 1024,
        private val maxEntries: Int = 256,
        private val clock: () -> Long = System::nanoTime,
    ) {
        private data class Entry(val text: String, val started: Long, val ttl: Long, val bytes: Long)
        private val values = LinkedHashMap<String, Entry>(16, .75f, true)
        private val pending = ConcurrentHashMap<Pair<String, Boolean>, CompletableFuture<String>>()
        private var bytes = 0L

        private fun cached(key: String): String? = synchronized(values) {
            val value = values[key] ?: return@synchronized null
            if (clock() - value.started < value.ttl) return@synchronized value.text
            values.remove(key); bytes -= value.bytes
            null
        }

        fun get(key: String, ttlMillis: Long, refresh: Boolean = false, load: () -> String): String {
            if (!refresh) cached(key)?.let { return it }
            val future = CompletableFuture<String>()
            val flight = key to refresh
            val existing = pending.putIfAbsent(flight, future)
            if (existing != null) {
                try { return existing.get() }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Metadata request interrupted", e) }
                catch (e: ExecutionException) { throw e.cause ?: e }
            }
            val started = clock()
            try {
                if (!refresh) cached(key)?.let { future.complete(it); return it }
                val text = load()
                val size = text.length * 2L
                if (size <= minOf(maxBytes, 8L * 1024 * 1024)) synchronized(values) {
                    if ((values[key]?.started ?: Long.MIN_VALUE) <= started) {
                        values.remove(key)?.let { bytes -= it.bytes }
                        values[key] = Entry(text, started, TimeUnit.MILLISECONDS.toNanos(ttlMillis), size)
                        bytes += size
                        while (bytes > maxBytes || values.size > maxEntries) {
                            val oldest = values.entries.iterator()
                            bytes -= oldest.next().value.bytes; oldest.remove()
                        }
                    }
                }
                future.complete(text)
                return text
            } catch (e: Throwable) { future.completeExceptionally(e); throw e }
            finally { pending.remove(flight, future) }
        }
    }

    private val metadata = MetadataCache()
    private val freshMetadata = ThreadLocal.withInitial { false }

    internal fun <T> withFreshMetadata(block: () -> T): T {
        val previous = freshMetadata.get()
        freshMetadata.set(true)
        try { return block() } finally { freshMetadata.set(previous) }
    }

    val USER_AGENT = "AlpheusWorld/AWLauncher/${ArgumentBuilder.LAUNCHER_VERSION}"

    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        val metadataCache = runCatching {
            val dir = Paths.cache.resolve("http").also { it.createDirectories() }
            Cache(dir.toFile(), 32L * 1024 * 1024)
        }.getOrNull()

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .apply { metadataCache?.let { cache(it) } }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                // Re-evaluate every redirect: never send the key to an unrelated host.
                if (request.url.isHttps && request.url.host in CurseForge.DOWNLOAD_HOSTS) {
                    CurseForge.apiKey.takeIf { it.isNotBlank() }?.let { builder.header("x-api-key", it) }
                } else if (request.url.host != "api.curseforge.com") {
                    builder.removeHeader("x-api-key")
                }
                chain.proceed(builder.build())
            }
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            .build()
    }

    private val metadataClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun request(url: String): Request = Request.Builder().url(url).build()

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(request(url).newBuilder().apply { headers.forEach { (name, value) -> header(name, value) } }.build())

    fun postJson(url: String, json: String): String =
        execute(Request.Builder().url(url).post(json.toRequestBody(JSON)).build())

    fun postForm(url: String, fields: Map<String, String>): String {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return execute(Request.Builder().url(url).post(body).build(), uploadClient)
    }

    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
    }

    private val JSON = "application/json".toMediaType()

    internal fun metadataTtl(request: Request): Long? {
        if (request.header("Authorization") != null) return null
        val path = request.url.encodedPath
        val modrinth = request.url.isHttps && request.url.host == "api.modrinth.com" && path.startsWith("/v2/")
        val curseforge = CurseForge.isMetadataUrl(request.url)
        if (!modrinth && !curseforge) return null
        if (request.method == "POST") return if (modrinth && path in setOf("/v2/version_files", "/v2/version_files/update"))
            if (path.endsWith("/update")) 30_000 else 3_600_000 else null
        if (request.method != "GET") return null
        return if (path.endsWith("/search")) 60_000 else 120_000
    }

    private fun execute(request: Request, via: OkHttpClient = metadataClient): String {
        val ttl = metadataTtl(request)
        if (ttl == null) return executeUncached(request, via)
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val body = request.body?.let { content -> Buffer().use { buffer -> content.writeTo(buffer); buffer.readUtf8() } }.orEmpty()
        val key = "${request.method} ${request.url} ${digest(request.header("x-api-key").orEmpty())} ${digest(body)}"
        val refresh = freshMetadata.get() || request.cacheControl.noCache
        return metadata.get(key, ttl, refresh) {
            executeUncached(if (refresh) request.newBuilder().header("Cache-Control", "no-cache").build() else request, via)
        }
    }

    private fun executeUncached(request: Request, via: OkHttpClient): String {
        via.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${request.url}")
            return response.body?.string() ?: throw IOException("empty body for ${request.url}")
        }
    }

    fun shutdown() {
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        }.onFailure { Log.debug("http shutdown: ${it.message}") }
    }
}
