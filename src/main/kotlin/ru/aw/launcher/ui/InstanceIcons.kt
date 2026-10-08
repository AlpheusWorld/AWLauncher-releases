package ru.aw.launcher.ui

import ru.aw.launcher.ui.theme.AWDimens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

internal object InstanceIcons {
    // Stable IDs preserve icons chosen in older versions of the launcher.
    val symbols = listOf("grass" to "Трава", "crystal" to "Кристалл", "craft" to "Верстак", "fox" to "Лиса",
        "anvil" to "Наковальня", "compass" to "Компас", "flame" to "Костёр", "planet" to "Планета")
    val backgrounds = listOf(
        Triple("emerald", "Изумрудный", Color(0xFF184C39)), Triple("blue", "Синий", Color(0xFF24466B)),
        Triple("violet", "Фиолетовый", Color(0xFF51416D)), Triple("rose", "Розовый", Color(0xFF6C3C4A)),
        Triple("amber", "Янтарный", Color(0xFF70522F)), Triple("cyan", "Бирюзовый", Color(0xFF23515B)),
        Triple("slate", "Графитовый", Color(0xFF3C424E)),
    )
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    fun validSymbol(id: String?) = id == null || symbols.any { it.first == id }
    fun validBackground(id: String?) = id == null || backgrounds.any { it.first == id }
    fun color(id: String?) = backgrounds.firstOrNull { it.first == id }?.third ?: backgrounds.first().third

    fun artwork(symbol: String): ImageBitmap = cache.computeIfAbsent(symbol.takeIf { validSymbol(it) } ?: "cube") { id ->
        checkNotNull(InstanceIcons::class.java.getResourceAsStream("/instance-icons/$id.png")) {
            "Missing bundled instance icon: $id"
        }.use { ImageIO.read(it).toComposeImageBitmap() }
    }

    internal fun generateArtwork(symbol: String, size: Int = 384): ImageBitmap {
        require(size in 64..1024)
        val id=symbol.takeIf { validSymbol(it) } ?: "cube"
        val art = IconModel()
        art.build(id)
        val texture = art.atlas.toComposeImageBitmap()
        return Surface.makeRasterN32Premul(size,size).use { surface ->
            surface.canvas.scale(size / 384f, size / 384f)
            CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,surface.canvas.asComposeCanvas(),Size(384f,384f)) {
                drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha=0.30f),Color.Transparent),
                    center=Offset(192f,318f),radius=114f),Offset(78f,295f),Size(228f,46f))
                val projected = projectModel(art.faces,-32f,25f).flatMap { it.points }
                val minX=projected.minOf { it.x }; val maxX=projected.maxOf { it.x }
                val minY=projected.minOf { it.y }; val maxY=projected.maxOf { it.y }
                val scale=minOf(282f/(maxX-minX),282f/(maxY-minY))
                drawPixelModel(texture,art.faces,-32f,25f,scale,
                    Offset(192f-(maxX+minX)*scale/2,181f+(maxY+minY)*scale/2))
            }
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    ImageIO.read(ByteArrayInputStream(data.bytes)).toComposeImageBitmap()
                }
            }
        }
    }
}

@Composable
internal fun PresetInstanceIcon(symbol: String, background: String?, modifier: Modifier = Modifier) {
    val color = InstanceIcons.color(background)
    val art = remember(symbol) { InstanceIcons.artwork(symbol) }
    val shape = RoundedCornerShape(AWDimens.CornerCard)
    Box(modifier.clip(shape).background(Brush.radialGradient(listOf(
        color.copy(red=(color.red+0.10f).coerceAtMost(1f),green=(color.green+0.10f).coerceAtMost(1f),blue=(color.blue+0.10f).coerceAtMost(1f)), color)))
        .border(1.dp,Color.White.copy(alpha=0.12f),shape)) {
        Image(art,null,Modifier.fillMaxSize(),filterQuality=FilterQuality.Medium)
    }
}

/** Original pixel textures drawn into one atlas, then rendered once and shared by all library cards. */
private class IconModel {
    val atlas = BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB)
    val faces = mutableListOf<ModelFace>()
    private var slot = 0
    private fun rgb(hex: Long) = (0xFF000000L or hex).toInt()
    private fun tile(paint: (Int,Int)->Int): ModelUV {
        check(slot < 256)
        val x=(slot%16)*16; val y=(slot/16)*16; slot++
        for (yy in 0..15) for (xx in 0..15) atlas.setRGB(x+xx,y+yy,paint(xx,yy))
        return ModelUV(x,y,16,16)
    }
    private fun grain(base: Long, variation: Int = 10): ModelUV = tile { x,y ->
        val delta=((x*71+y*43+x*y*17)%17-8)*variation/8
        val r=((base shr 16).toInt()+delta).coerceIn(0,255)
        val g=((base shr 8 and 255).toInt()+delta).coerceIn(0,255)
        val b=((base and 255).toInt()+delta).coerceIn(0,255)
        rgb((r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
    }
    private fun box(x: Float,y: Float,z: Float,w: Float,h: Float,d: Float, texture: ModelUV,
                    front: ModelUV=texture,top: ModelUV=texture) {
        faces += modelBox(ModelPoint(x,y,z),w,h,d,listOf(front,texture,texture,texture,top,texture))
    }
    private fun sprite(pixels: List<String>, palette: Map<Char,Long>, depth: Float = 2f) {
        val textures=palette.mapValues { (_,colour)->grain(colour,0) }
        val h=pixels.size; val w=pixels.maxOf { it.length }
        pixels.forEachIndexed { y,row -> row.forEachIndexed { x,pixel ->
            textures[pixel]?.let { box(x-w/2f+0.5f,h/2f-y-0.5f,0f,1f,1f,depth,it) }
        } }
    }
    fun build(id: String) {
        when(id) {
            "grass", "craft", "cube" -> {
                val side = when(id) {
                    "grass" -> tile { x,y ->
                        val green=listOf(0x68A83BL,0x76B644L,0x559435L,0x84BD49L)
                        val earth=listOf(0x876044L,0x765137L,0x9B7351L,0xAB8563L)
                        rgb(if(y<2+(x*7%4)) green[(x*3+y*5)%4] else earth[(x*71+y*13+x*y)%4])
                    }
                    "craft" -> tile { x,y ->
                        rgb(when {
                            x<2||x>13 -> 0x4F3524
                            y==3||y==12 -> 0x654229
                            x in 4..11 && y in 5..10 -> if(x==4||x==11||y==5) 0x583D28 else 0xB28B57
                            else -> if((y+x/4)%4==0) 0xB58A52 else 0x997140
                        })
                    }
                    else -> grain(0xB2BEC7,3)
                }
                val top = when(id) {
                    "grass" -> tile { x,y -> rgb(listOf(0x81B947L,0x93C651L,0x6FAA3EL,0xA4CC62L)[(x*13+y*37+x*y)%4]) }
                    "craft" -> tile { x,y -> rgb(when {
                        x<2||y<2||x>13||y>13 -> 0x5C3F27
                        x==5||x==10||y==5||y==10 -> 0x69482D
                        else -> if((x+y)%3==0) 0xD7B579 else 0xC49B61
                    }) }
                    else -> grain(0xD1DADE,3)
                }
                box(0f,0f,0f,12f,12f,12f,side,top=top)
            }
            "fox" -> {
                val orange=grain(0xD47B2C,7); val cream=grain(0xEEE0C4,4); val feet=grain(0x4B3830,3)
                val face=tile { x,y -> rgb(when {
                    y>=10 -> 0xEEE0C4
                    (x in 3..4||x in 11..12)&&y in 6..8 -> 0x262D32
                    y<3 -> 0xDA8434
                    else -> 0xE39A43
                }) }
                box(0f,0f,-1f,7f,6f,11f,orange)
                box(0f,3f,6f,7f,6f,6f,orange,face)
                box(0f,1f,9.4f,4f,2.8f,1.8f,cream,front=grain(0x39302B,0))
                for(x in listOf(-2.4f,2.4f)) {
                    box(x,7.5f,5.5f,2f,3f,2f,orange,front=grain(0x4A332B,0))
                    for(z in listOf(-4f,3f)) { box(x,-4f,z,1.8f,3f,1.8f,orange);box(x,-5.7f,z,1.8f,1.2f,1.8f,feet) }
                }
                box(0f,0.5f,-10f,3f,3f,8f,orange)
                box(0f,0.5f,-14.4f,3f,3f,2.8f,cream)
            }
            "anvil" -> {
                val iron=grain(0x646A7A,4);val light=grain(0x959DAA,3);val dark=grain(0x474C58,3)
                box(0f,-5f,0f,10f,2f,7f,dark,top=iron)
                box(0f,-2f,0f,4f,4f,4f,iron)
                box(0f,1f,0f,8f,2f,6f,iron)
                box(0f,3f,0f,11f,2f,6f,iron,top=light)
                box(0f,3f,4.5f,7f,2f,3f,iron,top=light)
                box(0f,3f,7f,3f,2f,2f,iron,top=light)
            }
            "compass" -> sprite(listOf(
                "    aaaaaaaa    ","  aabbbbbbbbaa  "," abbcddddddcbba ","abcddeeeeedddcba",
                "abcddeefeedddcba","abcddeffeedddcba","abcddeffeedddcba","abcdefffegdddcba",
                "abcdegggghdddcba","abcddehhhedddcba","abcddehheedddcba","abcddehheedddcba",
                "abcddeeeeedddcba"," abbcddddddcbba ","  aabbbbbbbbaa  ","    aaaaaaaa    "),
                mapOf('a' to 0x454B59,'b' to 0xBBC6CD,'c' to 0x758793,'d' to 0x29384A,'e' to 0x374B60,
                    'f' to 0xE77B70,'g' to 0xD9C7A9,'h' to 0xE8EDF0),2.5f)
            "crystal" -> sprite(listOf(
                "       a       ","      aba      ","     abbca     ","    abbbcca    ","    abbbcca    ",
                "  d abbbcca d  "," dedabbbccaded ","deefabbbccaefed","deefabbbccaefed","deefabbbccaefed",
                " defabbbccafed ","  efgbbbcagfe  ","  efgbbbcagfe  ","   fgghcaggf   ","    gghcagg    ",
                "     ghcag     ","      hag      ","       a       "),
                mapOf('a' to 0x64ACCE,'b' to 0xCFEEF1,'c' to 0x87CCD6,'d' to 0xADDFE4,'e' to 0x78B4D1,
                    'f' to 0x5E87B8,'g' to 0x706CB1,'h' to 0xA8ADDD),3f)
            "flame" -> {
                val wood=grain(0x8D613B,9);val ends=grain(0xC69C69,4)
                box(-2f,-6f,0f,3f,3f,13f,wood,ends);box(2f,-4.8f,0f,12f,3f,3f,wood,ends)
                sprite(listOf("       a      ","      aa      ","      aba     ","   a abbba    ","  aabbbcba    ",
                    "  abbbccba a  "," aabbccccbaba "," abbbccccbbba "," abbccddccbbba"," abbccdddccba ",
                    "  abccdddccba ","   bccdddcb   ","    bccccb    ","     bbbb     "),
                    mapOf('a' to 0xBA592A,'b' to 0xE28A33,'c' to 0xF7C450,'d' to 0xFFE8A0),3f)
            }
            "planet" -> sprite(listOf(
                "       aaaa       ","     aabccbaa     ","    abcdddccba    ","   abeddddddcbba  ",
                "  abbfeeeddddcbba "," abbffeeeeddddcbba"," abbffeeeddddccbaa","abbccfeeddddccbaaa",
                "abbccddddddcchgaaa","abbccdddeeccchgaaa"," abbccdffeecchgaa "," abbcccfffechgaa  ",
                "  abbccffeccgaa   ","   abbccccggaa    ","    abbccggaa     ","     abggaa       ",
                "       aaa        "),mapOf('a' to 0x2B536E,'b' to 0x437FA0,'c' to 0x64B4CC,'d' to 0x89CBD7,
                    'e' to 0xA1B97A,'f' to 0x7C9D66,'g' to 0x28516F,'h' to 0x417D97),5f)
        }
    }
}
