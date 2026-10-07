package ru.aw.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.toHex
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.net.Http
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import java.security.MessageDigest
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readBytes

object ModIcons {

    private const val SIZE = 64
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val KEEP = 160

    private val dir = Paths.cache.resolve("mod-icons")
    private val imageClient by lazy { Http.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build() }

    private val memory = object : LinkedHashMap<String, ImageBitmap>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > KEEP
    }

    private fun key(url: String, maxSize: Int): String = if (maxSize == SIZE) url else "$maxSize:$url"

    fun cached(url: String, maxSize: Int = SIZE): ImageBitmap? = synchronized(memory) { memory[key(url, maxSize)] }

    suspend fun loadLocal(path: Path, maxSize: Int = 256, stamp: String? = null,
                          maxBytes: Int = ru.aw.launcher.instance.InstanceImages.MAX_BYTES): ImageBitmap? = withContext(Dispatchers.IO) {
        require(maxSize in 1..1280 && maxBytes in 1..32 * 1024 * 1024)
        val id = path.toString() + (stamp?.let { "#$it" } ?: "")
        cached(id, maxSize)?.let { return@withContext it }
        val bitmap = runCatching {
            java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
                .takeIf { it.size <= maxBytes }?.let { shrink(it, maxSize) }
        }.getOrNull() ?: return@withContext null
        rememberBitmap(id, maxSize, bitmap)
        bitmap
    }

    suspend fun load(url: String, maxSize: Int = SIZE): ImageBitmap? = withContext(Dispatchers.IO) {
        require(maxSize in SIZE..1280)
        cached(url, maxSize)?.let { return@withContext it }
        val file = dir.resolve(MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).toHex())
        val bytes = if (file.exists()) {
            file.readBytes()
        } else {
            val fetched = runCatching { fetch(url, if (maxSize > SIZE) 8 * MAX_BYTES else MAX_BYTES) }.getOrNull() ?: return@withContext null
            runCatching { file.writeAtomically(fetched) }
            fetched
        }
        val bitmap = runCatching {
            if (bytes.take(512).toByteArray().toString(Charsets.UTF_8).contains("<svg")) shrinkSvg(bytes, maxSize)
            else shrink(bytes, maxSize)
        }.getOrNull() ?: return@withContext null
        rememberBitmap(url, maxSize, bitmap)
        bitmap
    }

    private fun rememberBitmap(id: String, maxSize: Int, bitmap: ImageBitmap) {
        synchronized(memory) {
            memory[key(id, maxSize)] = bitmap
            while (memory.values.sumOf { it.width.toLong() * it.height } > 16_000_000 && memory.size > 1) {
                memory.entries.iterator().run { next(); remove() }
            }
        }
    }

    private fun fetch(url: String, maxBytes: Int): ByteArray? =
        imageClient.newCall(Http.request(url)).execute().use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || body.contentLength() > maxBytes) return null
            body.byteStream().use { it.readNBytes(maxBytes + 1).takeIf { bytes -> bytes.size <= maxBytes } }
        }

    private fun shrink(bytes: ByteArray, maxSize: Int): ImageBitmap = SkiaImage.makeFromEncoded(bytes).use { source ->
        require(source.width.toLong() * source.height <= 40_000_000)
        val ratio = if (maxSize == SIZE) 1f else minOf(1f, maxSize.toFloat() / maxOf(source.width, source.height))
        val width = if (maxSize == SIZE) SIZE else (source.width * ratio).toInt().coerceAtLeast(1)
        val height = if (maxSize == SIZE) SIZE else (source.height * ratio).toInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(
                source,
                Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                SamplingMode.LINEAR,
                null,
                true,
            )
            surface.makeImageSnapshot().toComposeImageBitmap()
        }
    }

    private fun shrinkSvg(bytes: ByteArray, maxSize: Int): ImageBitmap = Data.makeFromBytes(bytes).use { data ->
        SVGDOM(data).use { svg ->
            val root = svg.root ?: error("SVG has no root")
            val width = root.width.value.takeIf { it > 0 } ?: root.viewBox?.width ?: maxSize.toFloat()
            val height = root.height.value.takeIf { it > 0 } ?: root.viewBox?.height ?: maxSize.toFloat()
            val factor = minOf(1f, maxSize.toFloat() / maxOf(width, height))
            val outWidth = if (maxSize == SIZE) SIZE else (width * factor).toInt().coerceAtLeast(1)
            val outHeight = if (maxSize == SIZE) SIZE else (height * factor).toInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(outWidth, outHeight).use { surface ->
                svg.setContainerSize(outWidth.toFloat(), outHeight.toFloat())
                svg.render(surface.canvas)
                surface.makeImageSnapshot().toComposeImageBitmap()
            }
        }
    }
}

@Composable
fun ModIcon(url: String?, size: Dp = 44.dp) {
    val bitmap by produceState(url?.let { ModIcons.cached(it) }, url) {
        if (value == null && url != null) value = ModIcons.load(url)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(AWDimens.CornerSmall)).background(AWColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(AWIcons.Extension, null, tint = AWColors.TextMuted, modifier = Modifier.size(size / 2))
        }
    }
}
