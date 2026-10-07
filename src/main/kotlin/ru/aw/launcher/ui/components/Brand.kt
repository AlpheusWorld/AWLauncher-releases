package ru.aw.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.Image as SkiaImage
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.core.Language
import ru.aw.launcher.ui.theme.AWColors
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object Brand {
    private val flags = ConcurrentHashMap<String, ImageBitmap>()
    val icon: ImageBitmap by lazy { decode("brand/icon-64.png") }

    val wordmarkDark: ImageBitmap by lazy { decode("brand/wordmark.png") }
    val wordmarkLight: ImageBitmap by lazy { decode("brand/wordmark-light.png") }
    val homeHero: ImageBitmap by lazy { decode("brand/aw-home-hero.png") }

    internal fun flagId(language: Language): String = when(language) {
        Language.RU -> "ru"
        Language.CA -> "es-ct"
        else -> language.locale.country.lowercase(Locale.ROOT)
    }

    fun languageFlag(language: Language): ImageBitmap = flags.computeIfAbsent(flagId(language)) { id ->
        decode("flags/$id.png")
    }

    val windowIcons: List<BufferedImage> by lazy {
        listOf(16, 20, 24, 32, 40, 48, 64).map { decode("brand/icon-$it.png").toAwtImage() }
    }

    private fun decode(path: String): ImageBitmap {
        val bytes = Brand::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("$path is missing from the launcher's resources")
        return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }
}

@Composable
fun Wordmark(width: Dp, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.width(width), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(width / 24)) {
            Image(Brand.icon, ArgumentBuilder.LAUNCHER_NAME, Modifier.size(width / 7), filterQuality = FilterQuality.High)
            Row {
                LocalizedText("AW", color = AWColors.Accent, fontSize = (width.value / 8.4f).sp, fontWeight = FontWeight.Bold, translate = false)
                LocalizedText("Launcher", color = AWColors.Text, fontSize = (width.value / 8.4f).sp, fontWeight = FontWeight.Bold, translate = false)
            }
        }
    }
}
