package ru.aw.launcher.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.*
import ru.aw.launcher.auth.SkinModel
import java.awt.image.BufferedImage
import kotlin.math.*

internal fun skinCameraDistance(width: Float,height: Float,zoom: Float): Float =
    max(17f/0.76f,11.3f/(width/height*0.80f))/tan(35f*PI.toFloat()/360f)/zoom

/** Perspective player: articulated cuboids, projective texture mapping and exact polygon depth ordering. */
internal class SkinRenderer(source: BufferedImage, cape: BufferedImage? = null) : AutoCloseable {
    private val bitmap = skinPreviewTexture(source).toComposeImageBitmap()
    private val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    private val capeImage = cape?.takeIf { it.width == 64 && it.height == 32 }
        ?.toComposeImageBitmap()?.let { Image.makeFromBitmap(it.asSkiaBitmap()) }
    private val paint = Paint().apply { isAntiAlias=true }
    private val clipping = Path()
    private val filters = Array(33) { level ->
        val light=(204+level*51/32).coerceIn(0,255)
        ColorFilter.makeLighting(0xFF000000.toInt() or (light shl 16) or (light shl 8) or light,0)
    }
    private val sampling = FilterMipmap(FilterMode.NEAREST,MipmapMode.NONE)
    private var previous: Frame? = null
    private var ordered: List<Polygon> = emptyList()

    private data class Frame(val width: Float,val height: Float,val model: SkinModel,val yaw: Float,val pitch: Float,
                             val zoom: Float,val layers: Boolean,val time: Float,val wave: Float)
    private data class Polygon(val face: ModelFace,val points: List<ModelPoint> = face.points) {
        val normal: ModelPoint = run {
            val n=(face.points[3]-face.points[0]).cross(face.points[1]-face.points[0])
            n*(1/sqrt(n.dot(n)))
        }
    }
    private class DepthNode(polygons: List<Polygon>) {
        private val splitter=polygons.first()
        private val coplanar=mutableListOf<Polygon>()
        private val front: DepthNode?
        private val back: DepthNode?
        init {
            val positive=mutableListOf<Polygon>();val negative=mutableListOf<Polygon>()
            for(polygon in polygons) {
                val distances=polygon.points.map { splitter.normal.dot(it-splitter.points[0]) }
                when {
                    distances.all { abs(it)<0.001f } -> coplanar+=polygon
                    distances.all { it >= -0.001f } -> positive+=polygon
                    distances.all { it <= 0.001f } -> negative+=polygon
                    else -> {
                        val a=mutableListOf<ModelPoint>();val b=mutableListOf<ModelPoint>()
                        polygon.points.forEachIndexed { index,point ->
                            val next=(index+1)%polygon.points.size
                            val d=distances[index];val nd=distances[next]
                            if(d>=-0.001f) a+=point
                            if(d<=0.001f) b+=point
                            if((d>0.001f&&nd< -0.001f)||(d< -0.001f&&nd>0.001f)) {
                                val cut=point+(polygon.points[next]-point)*(d/(d-nd))
                                a+=cut;b+=cut
                            }
                        }
                        if(a.size>=3) positive+=Polygon(polygon.face,a)
                        if(b.size>=3) negative+=Polygon(polygon.face,b)
                    }
                }
            }
            front=positive.takeIf { it.isNotEmpty() }?.let(::DepthNode)
            back=negative.takeIf { it.isNotEmpty() }?.let(::DepthNode)
        }
        fun ordered(eye: ModelPoint,destination: MutableList<Polygon>) {
            val nearFront=splitter.normal.dot(eye-splitter.points[0])>=0
            (if(nearFront) back else front)?.ordered(eye,destination)
            destination+=coplanar
            (if(nearFront) front else back)?.ordered(eye,destination)
        }
    }

    fun draw(canvas: Canvas,width: Float,height: Float,model: SkinModel,yaw: Float,pitch: Float,
             zoom: Float,layers: Boolean,time: Float=0f,wave: Float=0f) {
        if(width <= 0 || height <= 0) return
        val frame=Frame(width,height,model,yaw,pitch,zoom,layers,time,wave)
        val fov=35f*PI.toFloat()/180
        val focal=height/2/tan(fov/2)
        val distance=skinCameraDistance(width,height,zoom)
        val eye=ModelPoint(0f,-1.1f,distance)
        if(frame != previous) {
            val polygons=modelFaces(model,yaw,pitch,time,wave).asSequence().filter { layers || !it.outer }
                .map(::Polygon).filter { it.face.outer || it.normal.dot(eye-it.points[0])>0 }.toList()
            ordered=mutableListOf<Polygon>().also { DepthNode(polygons).ordered(eye,it) }
            previous=frame
        }
        val cx=width/2;val cy=height*0.47f
        fun project(p: ModelPoint) = Point(cx+p.x*focal/(distance-p.z),cy-(p.y-eye.y)*focal/(distance-p.z))
        for(polygon in ordered) {
            val face=polygon.face;val p=face.points;val uv=face.uv
            val origin=p[0];val right=(p[1]-origin)*(1f/uv.width);val down=(p[3]-origin)*(1f/uv.height)
            val z=distance-origin.z
            val normal=if(polygon.normal.dot(eye-origin)>0) polygon.normal else polygon.normal * -1f
            val key=((normal.x* -3+normal.y*4+normal.z*2)/sqrt(29f)).coerceIn(0f,1f)
            paint.colorFilter=filters[(key*32).roundToInt()]
            canvas.save()
            try {
                if(polygon.points != face.points) {
                    clipping.reset()
                    polygon.points.forEachIndexed { index,vertex ->
                        val point=project(vertex)
                        if(index==0) clipping.moveTo(point.x,point.y) else clipping.lineTo(point.x,point.y)
                    }
                    clipping.closePath()
                    canvas.clipPath(clipping,false)
                }
                canvas.concat(Matrix33(
                    (focal*right.x-cx*right.z)/z,(focal*down.x-cx*down.z)/z,cx+focal*origin.x/z,
                    (-focal*right.y-cy*right.z)/z,(-focal*down.y-cy*down.z)/z,cy-focal*(origin.y-eye.y)/z,
                    -right.z/z,-down.z/z,1f))
                canvas.drawImageRect(if (face.cape) capeImage ?: image else image,Rect.makeXYWH(uv.x.toFloat(),uv.y.toFloat(),uv.width.toFloat(),uv.height.toFloat()),
                    Rect.makeWH(uv.width.toFloat(),uv.height.toFloat()),sampling,paint,true)
            } finally { canvas.restore() }
        }
    }

    private fun modelFaces(model: SkinModel,yaw: Float,pitch: Float,time: Float,wave: Float): List<ModelFace> = buildList {
        val arm=if(model == SkinModel.SLIM) 3 else 4
        val bob=sin(time*1.7f)*0.12f
        val yr=yaw*PI.toFloat()/180;val pr=pitch*PI.toFloat()/180
        fun part(position: ModelPoint,pivot: ModelPoint,w: Int,h: Int,d: Int,
                 rx: Float,ry: Float,rz: Float,u: Int,v: Int,ou: Int,ov: Int) {
            fun transform(p: ModelPoint): ModelPoint {
                val relative=p-pivot
                val r=ModelPoint(relative.x,relative.y*cos(rx)-relative.z*sin(rx),relative.y*sin(rx)+relative.z*cos(rx)).rotate(ry,0f)
                return ModelPoint(r.x*cos(rz)-r.y*sin(rz)+pivot.x,r.x*sin(rz)+r.y*cos(rz)+pivot.y+bob,r.z+pivot.z).rotate(yr,pr)
            }
            val base=modelBox(position,w.toFloat(),h.toFloat(),d.toFloat(),boxUV(u,v,w,h,d))
            val inflate=if(w==8&&d==8) 0.5f else 0.25f
            val overlay=modelBox(position,w+inflate*2,h+inflate*2,d+inflate*2,boxUV(ou,ov,w,h,d),outer=true)
            addAll((base+overlay).map { face -> face.copy(points=face.points.map(::transform)) })
        }
        val breath=sin(time*1.7f);val glance=sin(time*0.52f)*0.065f
        val waveLift=if(wave>0) sin(PI.toFloat()*wave).coerceAtLeast(0f) else 0f
        part(ModelPoint(0f,12f,0f),ModelPoint(0f,8f,0f),8,8,8,-0.025f+breath*0.012f,0.05f+glance,-0.025f,0,0,32,0)
        part(ModelPoint(0f,2f,0f),ModelPoint(0f,-4f,0f),8,12,4,breath*0.008f,0f,-0.012f,16,16,16,32)
        part(ModelPoint(-4f-arm/2f,2f,0f),ModelPoint(-4f,7.8f,0f),arm,12,4,
            -0.04f+breath*0.018f,0f,-0.10f-waveLift*(2.40f+sin(wave*PI.toFloat()*10)*0.10f),40,16,40,32)
        part(ModelPoint(4f+arm/2f,2f,0f),ModelPoint(4f,7.8f,0f),arm,12,4,0.055f-breath*0.018f,0f,0.075f,32,48,48,48)
        part(ModelPoint(-1.95f,-10f,0f),ModelPoint(-1.95f,-4f,0f),4,12,4,-0.018f,0f,-0.012f,0,16,0,32)
        part(ModelPoint(1.95f,-10f,0f),ModelPoint(1.95f,-4f,0f),4,12,4,0.045f,0f,0.008f,16,48,0,48)
        if (capeImage != null) {
            val pivot = ModelPoint(0f, 7.7f, -2.6f)
            val angle = 0.14f + sin(time * 1.7f) * 0.025f
            // Minecraft cape UVs: 10x16 front/back with one-pixel edges.
            addAll(modelBox(ModelPoint(0f, -0.3f, -3.1f), 10f, 16f, 1f, boxUV(0, 0, 10, 16, 1))
                .map { face -> face.copy(cape = true, points = face.points.map { p ->
                    val r = p - pivot
                    ModelPoint(r.x + pivot.x, r.y * cos(angle) - r.z * sin(angle) + pivot.y + bob,
                        r.y * sin(angle) + r.z * cos(angle) + pivot.z).rotate(yr, pr)
                }) })
        }
    }

    override fun close() { paint.close();clipping.close();filters.forEach { it.close() };image.close();capeImage?.close() }
}
