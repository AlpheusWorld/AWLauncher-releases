package ru.aw.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.Language
import ru.aw.launcher.ui.components.LocalLanguage
@Immutable
private data class AWPalette(
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val text: Color,
    val textSoft: Color,
    val textMuted: Color,
    val danger: Color,
    val warning: Color,
    val info: Color,
    val success: Color,
    val loaderVanilla: Color,
    val loaderFabric: Color,
    val loaderQuilt: Color,
    val loaderForge: Color,
    val loaderNeoForge: Color,
    val shadow: Color,
    val dark: Boolean,
)

private val DarkPalette = AWPalette(
    background = Color(0xFF101213),
    sidebar = Color(0xFF151819),
    surface = Color(0xFF1A1E20),
    surfaceHigh = Color(0xFF242A2D),
    outline = Color(0xFF30383B),
    accent = Color(0xFF54C68A),
    accentPressed = Color(0xFF3BA970),
    accentSoft = Color(0xFF54C68A).copy(alpha = 0.10f),
    onAccent = Color(0xFF092517),
    text = Color(0xFFE9EDEF),
    textSoft = Color(0xFFB8C0C3),
    textMuted = Color(0xFF8F9BA0),
    danger = Color(0xFFF18A8A),
    warning = Color(0xFFEEB16D),
    info = Color(0xFF94BDD5),
    success = Color(0xFF55D58E),
    loaderVanilla = Color(0xFFB7BFCC),
    loaderFabric = Color(0xFF94BDD5),
    loaderQuilt = Color(0xFFE6C07B),
    loaderForge = Color(0xFFEEA56E),
    loaderNeoForge = Color(0xFFF18A8A),
    shadow = Color(0xFF000000),
    dark = true,
)

private val LightPalette = AWPalette(
    background = Color(0xFFF4F5F6),
    sidebar = Color(0xFFEBEDEF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE8ECEE),
    outline = Color(0xFFD0D7DA),
    accent = Color(0xFF159A5B),
    accentPressed = Color(0xFF0D804B),
    accentSoft = Color(0xFF159A5B).copy(alpha = 0.12f),
    onAccent = Color(0xFFFFFFFF),
    text = Color(0xFF172024),
    textSoft = Color(0xFF4D5B62),
    textMuted = Color(0xFF687980),
    danger = Color(0xFFB63F42),
    warning = Color(0xFF95611E),
    info = Color(0xFF3F7399),
    success = Color(0xFF128348),
    loaderVanilla = Color(0xFF697480),
    loaderFabric = Color(0xFF3F7399),
    loaderQuilt = Color(0xFF9A6A25),
    loaderForge = Color(0xFFAA5A2A),
    loaderNeoForge = Color(0xFFB34348),
    shadow = Color(0xFF193325),
    dark = false,
)

private val OledPalette = DarkPalette.copy(
    background = Color.Black,
    sidebar = Color(0xFF0B0E0F),
    surface = Color(0xFF111618),
    surfaceHigh = Color(0xFF1C2326),
    outline = Color(0xFF2B3438),
    shadow = Color.Transparent,
)

private val LocalAWPalette = staticCompositionLocalOf { DarkPalette }

object AWColors {
    val Background: Color @Composable get() = LocalAWPalette.current.background
    val Sidebar: Color @Composable get() = LocalAWPalette.current.sidebar
    val Surface: Color @Composable get() = LocalAWPalette.current.surface
    val SurfaceHigh: Color @Composable get() = LocalAWPalette.current.surfaceHigh
    val Outline: Color @Composable get() = LocalAWPalette.current.outline
    val Accent: Color @Composable get() = LocalAWPalette.current.accent
    val AccentPressed: Color @Composable get() = LocalAWPalette.current.accentPressed
    val AccentSoft: Color @Composable get() = LocalAWPalette.current.accentSoft
    val OnAccent: Color @Composable get() = LocalAWPalette.current.onAccent
    val Text: Color @Composable get() = LocalAWPalette.current.text
    val TextSoft: Color @Composable get() = LocalAWPalette.current.textSoft
    val TextMuted: Color @Composable get() = LocalAWPalette.current.textMuted
    val Danger: Color @Composable get() = LocalAWPalette.current.danger
    val Warning: Color @Composable get() = LocalAWPalette.current.warning
    val Info: Color @Composable get() = LocalAWPalette.current.info
    val Success: Color @Composable get() = LocalAWPalette.current.success
    val LoaderVanilla: Color @Composable get() = LocalAWPalette.current.loaderVanilla
    val LoaderFabric: Color @Composable get() = LocalAWPalette.current.loaderFabric
    val LoaderQuilt: Color @Composable get() = LocalAWPalette.current.loaderQuilt
    val LoaderForge: Color @Composable get() = LocalAWPalette.current.loaderForge
    val LoaderNeoForge: Color @Composable get() = LocalAWPalette.current.loaderNeoForge
    val Shadow: Color @Composable get() = LocalAWPalette.current.shadow
    val IsDark: Boolean @Composable get() = LocalAWPalette.current.dark
}

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK, ThemeMode.OLED -> true
    ThemeMode.LIGHT -> false
}

val Onest = FontFamily(
    Font("fonts/Onest-Regular.ttf", FontWeight.Normal),
    Font("fonts/Onest-Medium.ttf", FontWeight.Medium),
    Font("fonts/Onest-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/Onest-Bold.ttf", FontWeight.Bold),
    Font("fonts/Onest-Black.ttf", FontWeight.Black),
)

private val languageFonts = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

fun languageFont(language: Language): FontFamily {
    val family = when (language) {
        Language.EL, Language.VI -> "NotoSans"
        Language.AR, Language.FA -> "NotoSansArabic"
        Language.HE -> "NotoSansHebrew"
        Language.HI -> "NotoSansDevanagari"
        Language.BN -> "NotoSansBengali"
        Language.TH -> "NotoSansThai"
        Language.KA -> "NotoSansGeorgian"
        Language.HY -> "NotoSansArmenian"
        Language.ZH_CN -> "NotoSansSC"
        Language.ZH_TW -> "NotoSansTC"
        Language.JA -> "NotoSansJP"
        Language.KO -> "NotoSansKR"
        else -> return Onest
    }
    return languageFonts.getOrPut(family) {
        FontFamily(Font("fonts/$family-Regular.ttf", FontWeight.Normal), Font("fonts/$family-SemiBold.ttf", FontWeight.SemiBold))
    }
}

private val AWTypography = Typography(
    displayMedium = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, lineHeight = 46.sp),
    displaySmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
).withFamily(Onest)

private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

object AWDimens {
    fun sidebarWidth(available: Dp): Dp? {
        val width = (available * 0.18f).coerceIn(208.dp, 238.dp)
        return width.takeIf { available - it >= 740.dp }
    }
    val CornerLarge = 10.dp
    val CornerCard = 8.dp
    val CornerMedium = 6.dp
    val CornerSmall = 4.dp
    val Gutter = 24.dp
    val RailWidth = 64.dp
    val TitleBarHeight = 44.dp
}

val PillShape = RoundedCornerShape(6.dp)

@Composable
fun Modifier.softShadow(shape: Shape, elevation: Dp = 20.dp): Modifier {
    return shadow(
        elevation = elevation.coerceAtMost(3.dp),
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.12f),
        spotColor = Color.Black.copy(alpha = 0.16f),
    )
}

fun Modifier.glow(shape: Shape, color: Color, elevation: Dp = 22.dp): Modifier =
    shadow(elevation = 2.dp, shape = shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.16f))

@Composable
fun AWTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val settings by Settings.state.collectAsState()
    val dark = mode.isDark(isSystemInDarkTheme())
    val palette = when {
        mode == ThemeMode.OLED -> OledPalette
        dark -> DarkPalette
        else -> LightPalette
    }
    val scheme = remember(palette, dark) {
        if (dark) {
            darkColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                secondary = palette.info,
                onSecondary = palette.background,
                background = palette.background,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surfaceHigh,
                onSurfaceVariant = palette.textMuted,
                outline = palette.outline,
                outlineVariant = palette.outline,
                error = palette.danger,
                onError = palette.background,
            )
        } else {
            lightColorScheme(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                secondary = palette.info,
                onSecondary = Color.White,
                background = palette.background,
                onBackground = palette.text,
                surface = palette.surface,
                onSurface = palette.text,
                surfaceVariant = palette.surfaceHigh,
                onSurfaceVariant = palette.textMuted,
                outline = palette.outline,
                outlineVariant = palette.outline,
                error = palette.danger,
                onError = Color.White,
            )
        }
    }

    val typography = remember(settings.language) { AWTypography.withFamily(languageFont(settings.language)) }

    CompositionLocalProvider(LocalAWPalette provides palette, LocalLanguage provides settings.language,
        LocalLayoutDirection provides if (settings.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
