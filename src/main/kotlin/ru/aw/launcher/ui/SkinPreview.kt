package ru.aw.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.aw.launcher.auth.SkinModel
import ru.aw.launcher.core.I18n
import ru.aw.launcher.ui.components.AWIcons
import ru.aw.launcher.ui.components.WithTooltip
import ru.aw.launcher.ui.components.LocalizedText as Text
import ru.aw.launcher.ui.theme.AWColors
import java.awt.image.BufferedImage

/** Expands legacy limbs using Minecraft's mirrored face layout, leaving unused overlay pixels transparent. */
internal fun skinPreviewTexture(source: BufferedImage): BufferedImage {
    require(source.width == 64 && source.height in listOf(32,64))
    val out = BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB)
    val graphics = out.createGraphics()
    try {
        graphics.drawImage(source,0,0,null)
        if (source.height == 32) {
            for ((from,to) in listOf((0 to 16) to (16 to 48), (40 to 16) to (32 to 48))) {
                val src = boxUV(from.first,from.second,4,12,4)
                val dst = boxUV(to.first,to.second,4,12,4)
                for (face in 0..5) {
                    val a = src[when(face) { 2 -> 3; 3 -> 2; else -> face }]; val b = dst[face]
                    graphics.drawImage(source,b.x,b.y,b.x+b.width,b.y+b.height,a.x+a.width,a.y,a.x,a.y+a.height,null)
                }
            }
            val opaqueHat = (0 until 16).all { y -> (32 until 64).all { x -> source.getRGB(x,y) ushr 24 >= 128 } }
            if (opaqueHat) for (y in 0 until 16) for (x in 32 until 64) out.setRGB(x,y,0)
        }
        // The game treats the inner skin as opaque; PNG transparency belongs to the outer layer.
        for ((x,y,w,h) in listOf(intArrayOf(0,0,32,16), intArrayOf(0,16,64,16), intArrayOf(16,48,32,16))) {
            for (yy in y until y+h) for (xx in x until x+w) out.setRGB(xx,yy,out.getRGB(xx,yy) or 0xFF000000.toInt())
        }
    } finally { graphics.dispose() }
    return out
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SkinPreview(skin: BufferedImage?, model: SkinModel, modifier: Modifier = Modifier, loading: Boolean = false, cape: BufferedImage? = null, showBack: Boolean = false) {
    val renderer = remember(skin, cape) { skin?.let { SkinRenderer(it, cape) } }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    var yaw by remember { mutableFloatStateOf(-22.5f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(showBack) {
        val target = if (showBack) 157.5f else -22.5f
        if (yaw != target) Animatable(yaw).animateTo(target, tween(240)) { yaw = value }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var layers by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    var time by remember { mutableFloatStateOf(0f) }
    var waveCount by remember { mutableIntStateOf(0) }
    val wave = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(renderer,playing,focused) {
        if(renderer == null || !playing || !focused) return@LaunchedEffect
        var last=0L
        while(true) withFrameNanos { now ->
            if(last == 0L) last=now
            if(now-last >= 33_000_000) { time += ((now-last)/1_000_000_000f).coerceAtMost(0.1f);last=now }
        }
    }
    LaunchedEffect(waveCount) {
        if(waveCount == 0) return@LaunchedEffect
        wave.snapTo(0f);wave.animateTo(1f,tween(1600));wave.snapTo(0f)
    }
    fun resetView() { yaw=-22.5f;pitch=0f;zoom=1f }
    Box(modifier) {
            Canvas(Modifier.fillMaxSize()
                .semantics { contentDescription = I18n.text("3D-предпросмотр скина") }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when(event.key) {
                        Key.DirectionLeft -> { yaw = (yaw - 12) % 360; true }
                        Key.DirectionRight -> { yaw = (yaw + 12) % 360; true }
                        Key.DirectionUp -> { pitch = (pitch + 6).coerceIn(-35f,35f); true }
                        Key.DirectionDown -> { pitch = (pitch - 6).coerceIn(-35f,35f); true }
                        Key.Equals, Key.NumPadAdd -> { zoom = (zoom + 0.1f).coerceAtMost(1.5f); true }
                        Key.Minus, Key.NumPadSubtract -> { zoom = (zoom - 0.1f).coerceAtLeast(0.7f); true }
                        Key.Home -> { resetView(); true }
                        else -> false
                    }
                }.focusRequester(focus).focusable()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    zoom = (zoom - event.changes.first().scrollDelta.y * 0.07f).coerceIn(0.7f,1.5f)
                    event.changes.forEach { it.consume() }
                }
                .pointerInput(Unit) { detectDragGestures { change, drag ->
                    change.consume()
                    yaw = (yaw + drag.x / density * 0.65f) % 360
                    pitch = (pitch + drag.y / density * 0.35f).coerceIn(-35f,35f)
                } }
                .pointerInput(renderer) { detectTapGestures(onPress={ focus.requestFocus() },onTap={ if(renderer != null) waveCount++ },onDoubleTap={resetView()}) }) {
                val focal = size.height/2/kotlin.math.tan(35f*kotlin.math.PI.toFloat()/360f)
                val distance = skinCameraDistance(size.width,size.height,zoom)
                val scale = focal/distance
                val origin = Offset(size.width/2,size.height * 0.47f)
                val ground = ModelPoint(0f,-16.7f,0f).rotate(yaw * kotlin.math.PI.toFloat()/180,pitch * kotlin.math.PI.toFloat()/180)
                val floorY = origin.y - (ground.y+1.1f)*focal/(distance-ground.z)
                drawOval(Brush.radialGradient(listOf(Color(0xFF83CDB0).copy(alpha=0.15f),Color.Transparent),
                    center=Offset(origin.x,floorY),radius=10f*scale),Offset(origin.x-10f*scale,floorY-2.4f*scale),Size(20f*scale,4.8f*scale))
                renderer?.draw(drawContext.canvas.nativeCanvas,size.width,size.height,model,yaw,pitch,zoom,layers,time,wave.value)
            }
            if (renderer == null) Text(if (loading) "Загружаю скин…" else "Выбери PNG", color=AWColors.TextMuted,
                style=MaterialTheme.typography.bodySmall,modifier=Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopEnd),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            WithTooltip("Слой одежды") { IconButton({layers=!layers},Modifier.size(32.dp)) {
                Icon(AWIcons.Layers,I18n.text("Слой одежды"),tint=if(layers) AWColors.Accent else AWColors.TextMuted,modifier=Modifier.size(18.dp))
            } }
            WithTooltip("Анимации") { IconButton({playing=!playing},Modifier.size(32.dp)) {
                Icon(if(playing) AWIcons.Pause else Icons.Default.PlayArrow,I18n.text("Анимации"),tint=AWColors.TextSoft,modifier=Modifier.size(18.dp))
            } }
            WithTooltip("Сбросить вид") { IconButton({resetView()},Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh,I18n.text("Сбросить вид"),tint=AWColors.TextSoft,modifier=Modifier.size(18.dp))
            } }
        }
        Text("Тяни мышью для вращения · колёсико для масштаба",color=AWColors.TextMuted,
            style=MaterialTheme.typography.bodySmall,modifier=Modifier.align(Alignment.BottomCenter).padding(horizontal=10.dp))
    }
}
