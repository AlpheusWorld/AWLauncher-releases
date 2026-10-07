package ru.aw.launcher.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset

/** Shared timings keep motion quiet and consistent; text is never scaled. */
object AWMotion {
    private val settle = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val Hover = tween<Color>(160, easing = LinearOutSlowInEasing)
    val Press = tween<Float>(100, easing = LinearOutSlowInEasing)
    val Toggle = tween<Float>(170, easing = settle)
    val Muted = tween<Float>(160, easing = LinearOutSlowInEasing)
    val PageEnter = tween<Float>(240, easing = settle)
    val PanelEnter = tween<Float>(260, easing = settle)
    val PanelExit = tween<Float>(130, easing = FastOutLinearInEasing)
    val SectionEnter = tween<Float>(150, easing = settle)
    val ToastEnter = tween<Float>(180, easing = settle)
    val ToastExit = tween<Float>(120, easing = FastOutLinearInEasing)
    val ToastOffsetEnter = tween<IntOffset>(180, easing = settle)
    val ToastOffsetExit = tween<IntOffset>(120, easing = FastOutLinearInEasing)
    val ItemFade = tween<Float>(140, easing = LinearOutSlowInEasing)
    val ItemPlacement = tween<IntOffset>(220, easing = settle)
}
