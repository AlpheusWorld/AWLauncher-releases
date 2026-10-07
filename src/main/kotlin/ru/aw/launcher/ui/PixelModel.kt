package ru.aw.launcher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Matrix33
import kotlin.math.*

/** Small textured models share the existing Skia canvas; no extra window or rendering loop. */
internal data class ModelPoint(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: ModelPoint) = ModelPoint(x + other.x, y + other.y, z + other.z)
    operator fun times(factor: Float) = ModelPoint(x * factor,y * factor,z * factor)
    operator fun minus(other: ModelPoint) = ModelPoint(x - other.x, y - other.y, z - other.z)
    fun dot(other: ModelPoint) = x * other.x + y * other.y + z * other.z
    fun cross(other: ModelPoint) = ModelPoint(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun rotate(yaw: Float, pitch: Float): ModelPoint {
        val xx = x * cos(yaw) + z * sin(yaw)
        val zz = -x * sin(yaw) + z * cos(yaw)
        return ModelPoint(xx, y * cos(pitch) - zz * sin(pitch), y * sin(pitch) + zz * cos(pitch))
    }
}

internal data class ModelUV(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class ModelFace(val points: List<ModelPoint>, val uv: ModelUV, val outer: Boolean = false, val cape: Boolean = false)

/** Face order: front, back, left, right, top, bottom. Coordinates use Y up, Z toward the viewer. */
internal fun modelBox(
    center: ModelPoint, width: Float, height: Float, depth: Float, uv: List<ModelUV>,
    outer: Boolean = false, tilt: Float = 0f, pivot: ModelPoint = center,
): List<ModelFace> {
    require(uv.size == 6)
    val x0 = center.x - width / 2; val x1 = center.x + width / 2
    val y0 = center.y - height / 2; val y1 = center.y + height / 2
    val z0 = center.z - depth / 2; val z1 = center.z + depth / 2
    fun point(x: Float, y: Float, z: Float): ModelPoint {
        val dx = x - pivot.x; val dy = y - pivot.y
        return ModelPoint(pivot.x + dx * cos(tilt) - dy * sin(tilt), pivot.y + dx * sin(tilt) + dy * cos(tilt), z)
    }
    val quads = listOf(
        listOf(point(x0,y1,z1), point(x1,y1,z1), point(x1,y0,z1), point(x0,y0,z1)),
        listOf(point(x1,y1,z0), point(x0,y1,z0), point(x0,y0,z0), point(x1,y0,z0)),
        listOf(point(x0,y1,z0), point(x0,y1,z1), point(x0,y0,z1), point(x0,y0,z0)),
        listOf(point(x1,y1,z1), point(x1,y1,z0), point(x1,y0,z0), point(x1,y0,z1)),
        listOf(point(x0,y1,z0), point(x1,y1,z0), point(x1,y1,z1), point(x0,y1,z1)),
        listOf(point(x0,y0,z1), point(x1,y0,z1), point(x1,y0,z0), point(x0,y0,z0)),
    )
    return quads.mapIndexed { index, points -> ModelFace(points, uv[index], outer) }
}

internal fun boxUV(u: Int, v: Int, width: Int, height: Int, depth: Int) = listOf(
    ModelUV(u + depth, v + depth, width, height),
    ModelUV(u + depth * 2 + width, v + depth, width, height),
    ModelUV(u, v + depth, depth, height),
    ModelUV(u + depth + width, v + depth, depth, height),
    ModelUV(u + depth, v, width, depth),
    ModelUV(u + depth + width, v, width, depth),
)

private val modelLighting = Array(65) { index ->
    val light = 0.50f + index / 128f
    ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        light, 0f, 0f, 0f, 0f,
        0f, light, 0f, 0f, 0f,
        0f, 0f, light, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
}

internal data class ProjectedFace(val face: ModelFace, val points: List<ModelPoint>, val depth: Float, val light: Int)

internal fun projectModel(faces: List<ModelFace>, yaw: Float, pitch: Float, layers: Boolean = true): List<ProjectedFace> {
    val yr = yaw * PI.toFloat() / 180; val pr = pitch * PI.toFloat() / 180
    return faces.asSequence().filter { layers || !it.outer }.mapNotNull { face ->
        val points = face.points.map { it.rotate(yr, pr) }
        val normal = (points[3] - points[0]).cross(points[1] - points[0])
        val length = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
        if (length < 0.001f || normal.z <= 0.001f) return@mapNotNull null
        // Broad key light above and to the left, with enough ambient light to preserve skin colours.
        val key = ((-normal.x * 0.35f + normal.y * 0.65f + normal.z * 0.67f) / length).coerceIn(0f, 1f)
        ProjectedFace(face, points, points.sumOf { it.z.toDouble() }.toFloat() / 4, (24 + key * 40).roundToInt())
    }.sortedBy { it.depth }.toList()
}

internal fun DrawScope.drawPixelModel(
    image: ImageBitmap, faces: List<ModelFace>, yaw: Float, pitch: Float,
    scale: Float, origin: Offset = center, layers: Boolean = true,
) {
    val canvas = drawContext.canvas.nativeCanvas
    for (projected in projectModel(faces, yaw, pitch, layers)) {
        val p = projected.points
        val uv = projected.face.uv
        val a = (p[1].x - p[0].x) * scale / uv.width
        val b = (p[3].x - p[0].x) * scale / uv.height
        val c = -(p[1].y - p[0].y) * scale / uv.width
        val d = -(p[3].y - p[0].y) * scale / uv.height
        canvas.save()
        try {
            canvas.concat(Matrix33(a, b, origin.x + p[0].x * scale, c, d, origin.y - p[0].y * scale, 0f, 0f, 1f))
            drawImage(image, srcOffset = IntOffset(uv.x, uv.y), srcSize = IntSize(uv.width, uv.height),
                dstOffset = IntOffset.Zero, dstSize = IntSize(uv.width, uv.height),
                filterQuality = FilterQuality.None, colorFilter = modelLighting[projected.light])
        } finally { canvas.restore() }
    }
}
