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
    private static FontRenderContext CONTEXT;
    private record Faces(java.awt.Font regular,java.awt.Font bold,FontRenderContext context) {}
    private static java.util.concurrent.CompletableFuture<Faces> prepared;
    private record Key(String text,boolean bold) {}
    private record Line(Identifier texture,int width,int height,float left,float top,int bytes) {}
    private static final LinkedHashMap<Key,Line> lines=new LinkedHashMap<>(64,.75f,true);
    private static final LinkedHashMap<Key,Float> measures=new LinkedHashMap<>(128,.75f,true);
    private static java.awt.Font regular,bold;
    private static int bytes,serial;

    static synchronized void preload() {
        if(prepared!=null)return;
        prepared=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try(var input=Objects.requireNonNull(SmoothFont.class.getResourceAsStream("/assets/awassistant/font/onest.ttf"));
                var emphasis=Objects.requireNonNull(SmoothFont.class.getResourceAsStream("/assets/awassistant/font/onest-semibold.ttf"))) {
                var regular=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,input).deriveFont(java.awt.Font.PLAIN,15f*SAMPLE);
                var bold=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,emphasis).deriveFont(java.awt.Font.PLAIN,15f*SAMPLE);
                var image=new BufferedImage(4096,72,BufferedImage.TYPE_INT_ARGB);
                var graphics=image.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    for(var face:List.of(regular,bold)) {
                        graphics.setFont(face);
                        graphics.drawString("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя",0,54);
                    }
                } finally {graphics.dispose();}
                return new Faces(regular,bold,new FontRenderContext(null,true,true));
            } catch(Exception failure) {throw new IllegalStateException("Could not load bundled Onest",failure);}
        });
    }
    static void prepare() {
        if(regular!=null)return;
        preload();
        if(!prepared.isDone())return;
        var faces=prepared.join();regular=faces.regular;bold=faces.bold;CONTEXT=faces.context;
    }
    static boolean ready() {prepare();return regular!=null;}
    static int width(String text,boolean heavy,float size) {
        prepare();
        if(regular==null)return (int)Math.ceil(Minecraft.getInstance().font.width(text)*size*15/9);
        var key=new Key(text,heavy);Float advance=measures.get(key);
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
            int[] colors=image.getRGB(0,0,w,h,null,0,w);
            var target=ClientBridge.pixelBuffer(pixels);
            for(int color:colors)target.put(color&0xFF00FF00|(color&0xFF0000)>>>16|(color&0xFF)<<16);
            upload=ClientBridge.smoothTexture(pixels);
            Minecraft.getInstance().getTextureManager().register(id,upload);
        } catch(RuntimeException failure) {if(upload!=null)upload.close();else pixels.close();throw failure;}
        float ascent=face.getLineMetrics(key.text,CONTEXT).getAscent();
        return new Line(id,w,h,(bounds.x-PAD)/(float)SAMPLE,(ascent+bounds.y-PAD)/SAMPLE,w*h*4);
    }
    static void draw(GuiGraphicsExtractor g,String text,int x,int y,int color,boolean heavy,float size) {
        if(text.isBlank()||(color>>>24)==0)return;
        prepare();
        if(regular==null) {
            g.pose().pushMatrix();g.pose().translate(x,y);g.pose().scale(size*15/9,size*15/9);
            g.text(Minecraft.getInstance().font,text,0,0,color,false);g.pose().popMatrix();return;
        }
        var key=new Key(text,heavy);var line=lines.get(key);
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
                String candidate=line.isEmpty()?word:line+" "+word;
                if(width(candidate,heavy,size)<=width){if(!line.isEmpty())line.append(' ');line.append(word);continue;}
                if(!line.isEmpty()){result.add(line.toString());line.setLength(0);}
                if(width(word,heavy,size)<=width){line.append(word);continue;}
                for(int code:word.codePoints().toArray()) {
                    String character=Character.toString(code);
                    if(width(line+character,heavy,size)>width&&!line.isEmpty()){result.add(line.toString());line.setLength(0);}
                    line.append(character);
                }
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
