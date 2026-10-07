package ru.aw.launcher.ui

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.auth.SkinModel
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import ru.aw.launcher.ui.theme.AWTheme

class SkinRendererTest {
    private fun fixture(): BufferedImage = BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB).apply {
        val g=createGraphics()
        g.color=java.awt.Color(230,70,60);g.fillRect(0,0,32,16)
        g.color=java.awt.Color(60,140,210);g.fillRect(0,16,64,16);g.fillRect(16,48,32,16)
        g.color=java.awt.Color(50,210,80);g.fillRect(40,8,8,8)
        g.dispose()
    }
    private fun render(renderer: SkinRenderer,model: SkinModel=SkinModel.CLASSIC,yaw: Float=0f,layers: Boolean=false,
                       time: Float=0f,wave: Float=0f): BufferedImage = Surface.makeRasterN32Premul(256,400).use { surface ->
        renderer.draw(surface.canvas,256f,400f,model,yaw,0f,1f,layers,time,wave)
        surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
            ImageIO.read(ByteArrayInputStream(data.bytes))
        } }
    }
    private fun colored(image: BufferedImage, predicate: (Int)->Boolean): Int =
        (0 until image.height).sumOf { y -> (0 until image.width).count { x -> predicate(image.getRGB(x,y)) } }
    private fun imagePixels(image: BufferedImage) = image.getRGB(0,0,image.width,image.height,null,0,image.width)

    @Test fun `perspective renderer draws opaque skin and composites the outer layer`() {
        SkinRenderer(fixture()).use { renderer ->
            val base=render(renderer)
            assertTrue(colored(base) { it ushr 24 > 200 && (it shr 16 and 255) > 150 } > 1000)
            val outer=render(renderer,layers=true)
            assertTrue(colored(outer) { it ushr 24 > 200 && (it shr 8 and 255) > 150 && (it shr 16 and 255) < 100 } > 1000)
            assertEquals(0,outer.getRGB(0,0))
            assertFalse(imagePixels(base).contentEquals(imagePixels(outer)))
        }
    }
    @Test fun `model selection rotation idle and interaction update the rendered model`() {
        SkinRenderer(fixture()).use { renderer ->
            val base=imagePixels(render(renderer))
            assertFalse(base.contentEquals(imagePixels(render(renderer,model=SkinModel.SLIM))))
            assertFalse(base.contentEquals(imagePixels(render(renderer,yaw=90f))))
            assertFalse(base.contentEquals(imagePixels(render(renderer,time=2f))))
            assertFalse(base.contentEquals(imagePixels(render(renderer,wave=0.5f))))
            assertArrayEquals(base,imagePixels(render(renderer)))
        }
    }
    @Test fun `cape is rendered on the back and remains visible with clothing layers hidden`() {
        val cape = BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().also { g -> g.color=java.awt.Color(40,80,245);g.fillRect(0,0,64,32);g.dispose() }
        }
        SkinRenderer(fixture(), cape).use { renderer ->
            val rear = render(renderer,yaw=180f,layers=false)
            assertTrue(colored(rear) { (it and 255)>150 && (it shr 16 and 255)<80 } > 1000)
            SkinRenderer(fixture()).use { plain ->
                assertFalse(imagePixels(rear).contentEquals(imagePixels(render(plain,yaw=180f,layers=false))))
            }
            assertEquals(0, rear.getRGB(0,0))
        }
    }
    @Test fun `legacy skins mirror limbs and keep opaque invalid hats hidden`() {
        val source=BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB)
        for(y in 0..31) for(x in 0..63) source.setRGB(x,y,0xFF112233.toInt())
        for(y in 20..31) for(x in 4..7) source.setRGB(x,y,0xFFAA3300.toInt()+x)
        val converted=skinPreviewTexture(source)
        assertEquals(source.getRGB(7,20),converted.getRGB(20,52))
        assertEquals(source.getRGB(4,20),converted.getRGB(23,52))
        assertEquals(0,converted.getRGB(40,8))
        assertEquals(0,converted.getRGB(20,36))
        assertEquals(255,converted.getRGB(8,8) ushr 24)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun `preview can receive keyboard focus rotate and reset without a native window`() {
        val texture=fixture()
        val scene=ImageComposeScene(320,400,density=Density(1f)) {
            AWTheme { SkinPreview(texture,SkinModel.CLASSIC,Modifier.fillMaxSize()) }
        }
        try {
            repeat(4) { scene.render(it*16_666_667L).close() }
            scene.sendKeyEvent(KeyEvent(Key.Tab,KeyEventType.KeyDown))
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.DirectionRight,KeyEventType.KeyDown)))
            scene.render(83_333_335L).close()
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Home,KeyEventType.KeyDown)))
            scene.render(100_000_002L).close()
        } finally { scene.close() }
    }

    @Test fun `every preset has a bundled cached image including the generic instance fallback`() {
        for(id in InstanceIcons.symbols.map { it.first }+"cube") {
            val art=InstanceIcons.artwork(id)
            assertEquals(384,art.width)
            assertSame(art,InstanceIcons.artwork(id))
        }
    }
}
