package ru.aw.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import ru.aw.launcher.ui.components.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import ru.aw.launcher.launch.ArgumentBuilder
import ru.aw.launcher.ui.components.Wordmark
import ru.aw.launcher.ui.theme.AWColors
import ru.aw.launcher.ui.theme.AWDimens
import ru.aw.launcher.ui.theme.PillShape

@Composable
fun SplashContent(fraction: Float, status: String) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "splashProgress",
    )

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(AWDimens.CornerLarge))
            .background(AWColors.Surface)
            .border(1.dp, AWColors.Outline.copy(alpha = 0.6f), RoundedCornerShape(AWDimens.CornerLarge))
            .padding(26.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row {
                Wordmark(312.dp)
                Spacer(Modifier.weight(1f))
                Text(
                    "v${ArgumentBuilder.LAUNCHER_VERSION}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AWColors.TextMuted,
                )
            }

            Column {
                AnimatedContent(
                    targetState = status,
                    transitionSpec = {
                        fadeIn(tween(180)).togetherWith(fadeOut(tween(120)))
                    },
                    label = "splashStatus",
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AWColors.Text,
                    )
                }

                Spacer(Modifier.height(10.dp))
                SplashBar(animated)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${(animated * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AWColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SplashBar(fraction: Float) {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val offset by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(PillShape)
            .background(AWColors.SurfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(PillShape)
                .background(
                    Brush.horizontalGradient(
                        0f to AWColors.AccentPressed,
                        offset.coerceIn(0.05f, 0.95f) to AWColors.Accent,
                        1f to AWColors.AccentPressed,
                    )
                )
        )
    }
}
