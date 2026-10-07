package ru.aw.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.aw.launcher.auth.Account
import ru.aw.launcher.auth.MinecraftCape
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Paths
import ru.aw.launcher.core.sha1Of
import ru.aw.launcher.core.writeAtomically
import ru.aw.launcher.net.Http
import ru.aw.launcher.ui.theme.AWColors
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

object SkinHeads {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    private val DEFAULT_NAMES = listOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")

    fun cached(account: Account?): ImageBitmap? = account?.let { cache[key(it)] }

    suspend fun texture(account: Account): BufferedImage? = withContext(Dispatchers.IO) {
        runCatching { account.skinUrl?.let(::downloadSkin) ?: defaultSkin(account) }.getOrNull()
    }

    suspend fun capeTexture(cape: MinecraftCape): BufferedImage? = withContext(Dispatchers.IO) {
        val uri = runCatching { java.net.URI(cape.url) }.getOrNull() ?: return@withContext null
        if (uri.scheme !in listOf("http", "https") || uri.host != "textures.minecraft.net" || uri.userInfo != null) return@withContext null
        runCatching { downloadSkin(cape.url) }.getOrNull()?.takeIf { it.width == 64 && it.height == 32 }
    }

    suspend fun load(account: Account): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = key(account)
        cache[key]?.let { return@withContext it }
        val skin = runCatching { account.skinUrl?.let(::downloadSkin) ?: defaultSkin(account) }
            .onFailure { Log.debug("skin for ${account.name} unavailable: ${it.message}") }
            .getOrNull()
        skin?.let(::face)?.toComposeImageBitmap()?.also { cache[key] = it }
    }

    private fun key(account: Account): String = account.skinUrl ?: "default:${account.uuid}"

    private fun downloadSkin(url: String): BufferedImage? {
        val file = Paths.cache.resolve("skins").resolve(sha1Of(url.byteInputStream()) + ".png")
        if (!file.exists()) {
            val bytes = Http.client.newCall(Http.request(url.replaceFirst("http://", "https://"))).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.use { it.readNBytes(1024 * 1024 + 1) }?.takeIf { it.size <= 1024 * 1024 } ?: return null
            }
            file.writeAtomically(bytes)
        }
        val bytes = Files.newInputStream(file).use { it.readNBytes(1024 * 1024 + 1) }
        if (bytes.size > 1024 * 1024) return null
        return ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = input
                if (reader.getWidth(0) !in 1..1024 || reader.getHeight(0) !in 1..1024) return@use null
                reader.read(0)
            } finally { reader.dispose() }
        }
    }

    private fun defaultSkin(account: Account): BufferedImage? {
        val uuid = runCatching { UUID.fromString(account.dashedUuid) }.getOrNull() ?: return null
        val index = Math.floorMod(uuid.hashCode(), DEFAULT_NAMES.size * 2)
        val model = if (index < DEFAULT_NAMES.size) "slim" else "wide"
        val modern = "assets/minecraft/textures/entity/player/$model/${DEFAULT_NAMES[index % DEFAULT_NAMES.size]}.png"
        val legacy = "assets/minecraft/textures/entity/${if (uuid.hashCode() and 1 == 1) "alex" else "steve"}.png"

        for (jar in clientJars()) {
            val image = runCatching {
                ZipFile(jar.toFile()).use { zip ->
                    val entry = zip.getEntry(modern) ?: zip.getEntry(legacy) ?: return@use null
                    zip.getInputStream(entry).use { ImageIO.read(it) }
                }
            }.getOrNull()
            if (image != null) return image
        }
        return null
    }

    private fun clientJars() = runCatching {
        Paths.versions.listDirectoryEntries()
            .filter { it.isDirectory() }
            .map { it.resolve("${it.name}.jar") }
            .filter { it.exists() }
            .sortedByDescending { it.getLastModifiedTime().toMillis() }
    }.getOrDefault(emptyList())

    internal fun face(skin: BufferedImage): BufferedImage? {
        if (skin.width < 64 || skin.height < skin.width / 2) return null
        val scale = skin.width / 64
        val size = 8 * scale
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = out.createGraphics()
        graphics.drawImage(skin.getSubimage(8 * scale, 8 * scale, size, size), 0, 0, null)
        val hat = skin.getSubimage(40 * scale, 8 * scale, size, size)
        val legacy = skin.height == skin.width / 2
        if (!legacy || hasTransparency(hat)) graphics.drawImage(hat, 0, 0, null)
        graphics.dispose()
        return out
    }

    private fun hasTransparency(image: BufferedImage): Boolean {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) ushr 24) < 128) return true
        }
        return false
    }
}

@Composable
fun SkinHead(account: Account?, size: Dp, modifier: Modifier = Modifier) {
    val head by produceState(SkinHeads.cached(account), account?.uuid, account?.skinUrl) {
        value = account?.let { SkinHeads.load(it) }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(AWColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = head
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = account?.name,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
            account == null -> Icon(Icons.Default.Add, null, tint = AWColors.TextMuted, modifier = Modifier.size(size * 0.6f))
            else -> Text(
                account.name.take(1).uppercase(),
                color = AWColors.Accent,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}
