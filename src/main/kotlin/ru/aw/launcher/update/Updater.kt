package ru.aw.launcher.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import ru.aw.launcher.core.Json
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.Shell
import ru.aw.launcher.core.Shortcuts
import ru.aw.launcher.core.toHex
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.net.Http
import ru.aw.launcher.net.Pieces
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
data class UpdateManifest(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long = 0,
    val signatureUrl: String? = null,
    @Transient val signedDocument: String? = null,
    @Transient val signature: String? = null,
)

sealed interface UpdateState {
    data object NotConfigured : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: UpdateManifest) : UpdateState
    data class Downloading(val update: UpdateManifest, val fraction: Float, val bytesPerSecond: Long = 0) : UpdateState
    data class Installing(val update: UpdateManifest) : UpdateState
    data class Failed(val message: String, val update: UpdateManifest?) : UpdateState
}

object UpdateConfig {

    val feedUrl: String? by lazy {
        sequenceOf(System.getenv("AW_UPDATE_URL"), readFromFile(), readBundled())
            .mapNotNull { it?.trim() }
            .firstOrNull { it.startsWith("https://") }
    }

    private fun readFromFile(): String? = runCatching {
        val file = Paths.root.resolve("update_url.txt")
        if (file.exists()) file.readText() else null
    }.getOrNull()

    private fun readBundled(): String? = runCatching {
        UpdateConfig::class.java.getResourceAsStream("/update-url.txt")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText().trim() }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

object Updater {

    private val _state = MutableStateFlow<UpdateState>(
        if (UpdateConfig.feedUrl == null) UpdateState.NotConfigured else UpdateState.Idle
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = ArgumentBuilder.LAUNCHER_VERSION

    private val VERSION = Regex("""^[0-9A-Za-z.+-]{1,32}$""")
    private val SHA256 = Regex("""^[0-9a-fA-F]{64}$""")
    private val publicKey: String? by lazy { bundled("/update/public-key.txt")?.takeIf { it.isNotBlank() } }
    private val signerThumbprint: String by lazy { bundled("/signer-thumbprint.txt").orEmpty().replace(" ", "").uppercase() }
    private val metadataClient by lazy { Http.client.newBuilder().cache(null).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }
    private var installJob: Job? = null
    private val installLock = Mutex()
    private val networkCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()

    private fun bundled(resource: String): String? = Updater::class.java.getResourceAsStream(resource)
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().trim() }

    suspend fun check() {
        val url = UpdateConfig.feedUrl ?: return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing || _state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val manifest = withContext(Dispatchers.IO) {
                val document = metadata(url, 64 * 1024)
                val parsed = Json.decodeFromString<UpdateManifest>(document)
                if (publicKey != null) {
                    val feed = url.toHttpUrlOrNull()
                    val signatureUrl = parsed.signatureUrl ?: feed?.let { it.newBuilder().encodedPath(it.encodedPath + ".sig").build().toString() }
                        ?: throw IOException("Некорректный адрес обновлений")
                    val signatureAddress = signatureUrl.toHttpUrlOrNull()
                    if (signatureUrl.length > 2048 || signatureAddress == null || !signatureAddress.isHttps || signatureAddress.host != feed?.host ||
                        signatureAddress.username.isNotEmpty() || signatureAddress.password.isNotEmpty()) throw IOException("Некорректный адрес подписи обновления")
                    parsed.copy(signedDocument = document, signature = metadata(signatureUrl, 1024).trim())
                } else parsed
            }
            validate(manifest)
            verifyProof(manifest)
            if (isNewer(manifest.version, currentVersion)) UpdateState.Available(manifest) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.warn("update check failed: ${e.message}")
            UpdateState.Failed("Не удалось проверить обновления: ${e.message.orEmpty()}", null)
        }
    }

    suspend fun install(update: UpdateManifest): Boolean {
        if (!installLock.tryLock()) return false
        installJob = currentCoroutineContext()[Job]
        try {
            if (!isNewer(update.version, currentVersion)) throw IOException("Эта версия лаунчера уже установлена или устарела")
            validate(update)
            verifyProof(update)
            val app = Shell.appExecutable
                ?: throw IOException("Обновление ставится только в установленный лаунчер, не при запуске из IDE")
            _state.value = UpdateState.Downloading(update, 0f)
            val file = download(update) { fraction, speed -> _state.value = UpdateState.Downloading(update, fraction, speed) }
            if (signerThumbprint.isNotBlank()) withContext(Dispatchers.IO) { verifyInstallerSignature(file) }
            currentCoroutineContext().ensureActive()
            _state.value = UpdateState.Installing(update)
            startInstaller(file, app, update.version)
            return true
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(update)
            throw e
        } catch (e: Exception) {
            Log.warn("update to ${update.version} failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Обновление не удалось", update)
            throw e
        } finally {
            installJob = null
            networkCalls.clear()
            installLock.unlock()
        }
    }

    fun cancelDownload() {
        if (_state.value is UpdateState.Downloading) {
            installJob?.cancel()
            networkCalls.forEach { it.cancel() }
        }
    }

    private fun metadata(url: String, limit: Int): String = metadataClient.newCall(Http.request(url).newBuilder().header("Cache-Control", "no-cache").build())
        .execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = response.body?.byteStream()?.use { it.readNBytes(limit + 1) } ?: throw IOException("Пустой ответ сервера обновлений")
            if (bytes.size > limit) throw IOException("Описание обновления слишком большое")
            bytes.toString(Charsets.UTF_8)
        }

    private fun verifyProof(update: UpdateManifest) {
        val key = publicKey
        if (key != null) verifySignedManifest(update, key)
        else if (!Regex("^[0-9A-F]{40}$").matches(signerThumbprint)) throw IOException("Проверка подписи обновлений не настроена")
    }

    internal fun verifySignedManifest(update: UpdateManifest, encodedKey: String) {
        try {
            val document = update.signedDocument ?: throw IOException("У обновления нет цифровой подписи")
            val signed = Json.decodeFromString<UpdateManifest>(document)
            if (signed != update.copy(signedDocument = null, signature = null)) throw IOException("Описание обновления изменилось")
            val signature = Base64.getDecoder().decode(update.signature ?: throw IOException("У обновления нет цифровой подписи"))
            if (signature.size != 64) throw IOException("Некорректная подпись обновления")
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(document.toByteArray(Charsets.UTF_8))
            if (!verifier.verify(signature)) throw IOException("Подпись обновления недействительна")
        } catch (failure: IOException) { throw failure }
        catch (failure: Exception) { throw IOException("Не удалось проверить подпись обновления", failure) }
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        fun split(version: String): Pair<List<Int>, String?> {
            val (numbers, suffix) = version.trim().removePrefix("v").split('-', limit = 2)
                .let { it[0] to it.getOrNull(1) }
            return numbers.split('.').map { it.toIntOrNull() ?: 0 } to suffix
        }
        val (remoteParts, remoteSuffix) = split(remote)
        val (localParts, localSuffix) = split(local)
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val a = remoteParts.getOrElse(i) { 0 }
            val b = localParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return localSuffix != null && remoteSuffix == null
    }

    internal fun validate(manifest: UpdateManifest) {
        if (!VERSION.matches(manifest.version)) throw IOException("в фиде некорректная версия")
        val url = manifest.url.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || manifest.url.length > 2048) throw IOException("установщик должен раздаваться по https")
        if (!SHA256.matches(manifest.sha256)) throw IOException("в фиде нет корректного sha256 установщика")
        if (manifest.size !in 0..2_147_483_648L) throw IOException("Некорректный размер обновления")
        if (manifest.signedDocument != null && manifest.size == 0L) throw IOException("В подписанном обновлении не указан размер установщика")
    }

    private fun verifyInstallerSignature(file: Path) {
        val expected = signerThumbprint
        if (!Regex("^[0-9A-F]{40}$").matches(expected)) {
            throw IOException("Проверка подписи обновления не настроена в этой сборке")
        }

        val path = Shell.psLiteral(file.toAbsolutePath().toString())
        val script = """
            ${'$'}signature = Get-AuthenticodeSignature -LiteralPath $path
            if (${'$'}signature.Status.ToString() -ne 'Valid') { exit 10 }
            ${'$'}thumbprint = ${'$'}signature.SignerCertificate.Thumbprint.Replace(' ', '').ToUpperInvariant()
            if (${'$'}thumbprint -ne '$expected') { exit 11 }
        """.trimIndent()
        when (Shell.runHidden(Shell.powershell(script))) {
            0 -> Unit
            10 -> throw IOException("Подпись установщика обновления недействительна")
            11 -> throw IOException("Установщик обновления подписан другим сертификатом")
            else -> throw IOException("Не удалось проверить подпись установщика обновления")
        }
    }

    internal suspend fun download(
        update: UpdateManifest,
        streams: Int = Pieces.STREAMS,
        pieceBytes: Long = Pieces.PIECE_BYTES,
        onProgress: (fraction: Float, bytesPerSecond: Long) -> Unit = { _, _ -> },
    ): Path = withContext(Dispatchers.IO) {
        val extension = if (update.url.substringBefore('?').endsWith(".exe", ignoreCase = true)) "exe" else "msi"
        val dir = Paths.cache.resolve("updates").also { it.createDirectories() }
        val target = dir.resolve("AWLauncher-${update.version}.$extension")
        val part = dir.resolve("${target.fileName}.part")

        try {
            val context = currentCoroutineContext()
            val source = Pieces.probe(update.url) { networkCalls.add(it) }
            context.ensureActive()
            val total = update.size.takeIf { it > 0 } ?: source.length
            val meter = Meter(total, onProgress)
            if (source.ranges && total > pieceBytes) {
                Pieces.fetch(source.url, part, total, { context.ensureActive(); meter.add(it) }, streams, pieceBytes) { networkCalls.add(it) }
            } else {
                fetchWhole(source.url, part, meter) { context.ensureActive() }
            }
            if (!sha256Of(part).equals(update.sha256, ignoreCase = true)) {
                throw IOException("контрольная сумма обновления не сошлась — файл повреждён или подменён")
            }
            context.ensureActive()
            if (update.size > 0 && Files.size(part) != update.size) throw IOException("Размер загруженного обновления не совпадает")
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (e: Throwable) {
            part.deleteIfExists()
            throw e
        }
    }

    private fun fetchWhole(url: String, part: Path, meter: Meter, checkActive: () -> Unit) {
        val call = Pieces.client.newCall(Http.request(url)).also { networkCalls.add(it) }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} при загрузке обновления")
            val body = response.body ?: throw IOException("пустой ответ сервера обновлений")
            body.byteStream().use { input ->
                Files.newOutputStream(part).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        checkActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        meter.add(n)
                    }
                }
            }
        }
    }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private class Meter(private val total: Long, private val report: (Float, Long) -> Unit) {
        private val done = AtomicLong()
        private var sampledAt = System.nanoTime()
        private var sampledBytes = 0L
        private var speed = 0L

        fun add(bytes: Int) {
            val now = done.addAndGet(bytes.toLong())
            val time = System.nanoTime()
            synchronized(this) {
                val elapsed = time - sampledAt
                if (elapsed < SAMPLE_NANOS && now < total) return
                val instant = (now - sampledBytes) * 1_000_000_000L / elapsed.coerceAtLeast(1)
                speed = if (speed == 0L) instant else (speed * 2 + instant) / 3
                sampledAt = time
                sampledBytes = now
            }
            if (total > 0) report((now.toDouble() / total).toFloat().coerceIn(0f, 1f), speed)
        }
    }

    internal fun msiArguments(installer: Path, desktopShortcut: Boolean, log: Path, installDirectory: Path? = null): String {
        val shortcut = if (desktopShortcut) "" else " JP_INSTALL_DESKTOP_SHORTCUT=\"\""
        val directory = installDirectory?.toAbsolutePath()?.normalize()?.toString()?.also {
            if ('"' in it || '\n' in it || '\r' in it) throw IOException("Некорректная папка лаунчера")
        }?.let { " INSTALLDIR=\"$it\"" }.orEmpty()
        return "/i \"$installer\" /qn /norestart$directory$shortcut /l*v \"$log\""
    }

    private suspend fun startInstaller(installer: Path, app: Path, version: String) {
        val dir = Paths.cache.resolve("update")
        val ready = dir.resolve("ready")
        val script = withContext(Dispatchers.IO) {
            UpdateSplash.prepare(dir)
            ready.deleteIfExists()
            val msi = installer.toString().endsWith(".msi")
            UpdateSplash.script(
                launcher = ProcessHandle.current().pid(),
                installer = if (msi) "msiexec.exe" else installer.toString(),
                arguments = if (msi) msiArguments(installer, Shortcuts.appOnDesktop(app).exists(), dir.resolve("install.log"), app.parent) else "",
                app = app,
                assets = dir,
                version = version,
                ready = ready,
            )
        }
        Shell.runHidden(Shell.powershell(script), waitMs = 0)
            ?: throw IOException("Не удалось запустить установщик обновления")
        Log.info("update installer handed over: $installer")
        withTimeoutOrNull(READY_MILLIS) { while (!ready.exists()) delay(50) }
            ?: Log.warn("update splash did not show up in $READY_MILLIS ms")
    }

    private const val READY_MILLIS = 10_000L
    private const val SAMPLE_NANOS = 250_000_000L
}
