package ru.aw.assistant.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import ru.aw.assistant.ClientBridge;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** Cached supersampled lines: one GUI operation per line, with a bounded texture budget. */
final class SmoothFont {
    private static final int SAMPLE=3, PAD=2, LIMIT=8*1024*1024;
    private static final FontRenderContext CONTEXT=new FontRenderContext(null,true,true);
    private record Key(String text,boolean bold) {}
    private record Line(Identifier texture,int width,int height,float left,float top,int bytes) {}
    private static final LinkedHashMap<Key,Line> lines=new LinkedHashMap<>(64,.75f,true);
    private static final LinkedHashMap<Key,Float> measures=new LinkedHashMap<>(128,.75f,true);
    private static java.awt.Font regular,bold;
    private static int bytes,serial;

    static void prepare() {
        if(regular!=null)return;
        try(var input=Objects.requireNonNull(SmoothFont.class.getResourceAsStream("/assets/awassistant/font/onest.ttf"))) {
            regular=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,input).deriveFont(java.awt.Font.PLAIN,15f*SAMPLE);
            bold=regular.deriveFont(java.awt.Font.BOLD);
        } catch(Exception failure) {throw new IllegalStateException("Could not load bundled Onest",failure);}
    }
    static int width(String text,boolean heavy,float size) {
        prepare();var key=new Key(text,heavy);Float advance=measures.get(key);
        if(advance==null) {
            advance=(float)(heavy?bold:regular).getStringBounds(text,CONTEXT).getWidth()/SAMPLE;
            measures.put(key,advance);
            if(measures.size()>512)measures.remove(measures.keySet().iterator().next());
        }
        return (int)Math.ceil(advance*size);
    }
    private static Line raster(Key key) {
        var face=key.bold?bold:regular;
        var bounds=face.createGlyphVector(CONTEXT,key.text).getPixelBounds(CONTEXT,0,0);
        int w=Math.max(1,bounds.width+PAD*2),h=Math.max(1,bounds.height+PAD*2);
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        var paint=image.createGraphics();
        try {
            paint.setFont(face);paint.setColor(Color.WHITE);
            paint.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paint.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            paint.drawString(key.text,PAD-bounds.x,PAD-bounds.y);
        } finally {paint.dispose();}
        var pixels=new NativeImage(w,h,false);
        net.minecraft.client.renderer.texture.DynamicTexture upload=null;
        var id=Identifier.fromNamespaceAndPath("awassistant","text_"+(serial++));
        try {
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels.setPixel(x,y,image.getRGB(x,y));
            upload=ClientBridge.smoothTexture(pixels);
            Minecraft.getInstance().getTextureManager().register(id,upload);
        } catch(RuntimeException failure) {if(upload!=null)upload.close();else pixels.close();throw failure;}
        float ascent=face.getLineMetrics(key.text,CONTEXT).getAscent();
        return new Line(id,w,h,(bounds.x-PAD)/(float)SAMPLE,(ascent+bounds.y-PAD)/SAMPLE,w*h*4);
    }
    static void draw(GuiGraphicsExtractor g,String text,int x,int y,int color,boolean heavy,float size) {
        if(text.isBlank()||(color>>>24)==0)return;
        prepare();var key=new Key(text,heavy);var line=lines.get(key);
        if(line==null) {
            line=raster(key);lines.put(key,line);bytes+=line.bytes;
            var oldest=lines.entrySet().iterator();
            while((bytes>LIMIT||lines.size()>128)&&lines.size()>1&&oldest.hasNext()) {
                var remove=oldest.next().getValue();oldest.remove();bytes-=remove.bytes;
                Minecraft.getInstance().getTextureManager().release(remove.texture);
            }
        }
        g.pose().pushMatrix();g.pose().translate(x+line.left*size,y+line.top*size);g.pose().scale(size/SAMPLE,size/SAMPLE);
        g.blit(RenderPipelines.GUI_TEXTURED,line.texture,0,0,0f,0f,line.width,line.height,line.width,line.height,line.width,line.height,color);
        g.pose().popMatrix();
    }
    static List<String> wrap(String value,int width,boolean heavy,float size) {
        var result=new ArrayList<String>();var line=new StringBuilder();
        for(String paragraph:value.split("\n",-1)) {
            if(paragraph.isBlank()){result.add("");continue;}
            for(String word:paragraph.split("\\s+")) {
                if(word.isEmpty())continue;
                if(line.length()>0&&width(line+word,heavy,size)>width){result.add(line.toString().stripTrailing());line.setLength(0);}
                for(int code:word.codePoints().toArray()) {
                    String character=Character.toString(code);
                    if(width(line+character,heavy,size)>width&&!line.isEmpty()){result.add(line.toString());line.setLength(0);}
                    line.append(character);
                }
                line.append(' ');
            }
            if(!line.isEmpty()){result.add(line.toString().stripTrailing());line.setLength(0);}
        }
        return List.copyOf(result);
    }
    static void close() {
        for(var line:lines.values())Minecraft.getInstance().getTextureManager().release(line.texture);
        lines.clear();measures.clear();bytes=0;
    }
}
