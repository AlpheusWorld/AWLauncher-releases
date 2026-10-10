package ru.aw.assistant.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

public final class UiRenderer {
    public static final int TEXT = 0xFFF1F2F3, MUTED = 0xFF98A2AC, ACCENT = 0xFF58CC91;
    public static int alpha(int color, float opacity) { return ((Math.round((color >>> 24) * opacity) & 255) << 24) | (color & 0xFFFFFF); }
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x,y,x+w,y+h,color); return; }
        g.fill(x,y+r,x+w,y+h-r,color);
        for (int line=0; line<r; line++) {
            double dy = r-line-0.5;
            int inset = (int)Math.ceil(r-Math.sqrt(r*r-dy*dy));
            g.fill(x+inset,y+line,x+w-inset,y+line+1,color);
            g.fill(x+inset,y+h-line-1,x+w-inset,y+h-line,color);
        }
    }
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, float fade) {
        for (int i=4; i>0; i--) round(g,x-i*2,y+i,w+i*4,h+i*2,12+i,alpha(0x13000000,fade));
        round(g,x,y,w,h,12,alpha(0xFF56616A,fade*.25f));
        round(g,x+1,y+1,w-2,h-2,11,alpha(0xCE14181D,fade));
    }
    public static void text(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color, float fade) {
        SmoothFont.draw(g,value,x,y,alpha(color,fade),false,1);
    }
    public static void prepare() { SmoothFont.prepare(); }
    public static void close() { SmoothFont.close(); }
    public static int width(String text) { return SmoothFont.width(text,false,1); }
    public static java.util.List<String> wrap(String text,int width,boolean bold) { return SmoothFont.wrap(text,width,bold,bold?1.12f:1); }
    public static void small(GuiGraphicsExtractor g,String text,int x,int y,int color,float fade) { SmoothFont.draw(g,text,x,y,alpha(color,fade),false,.85f); }
    public static void bold(GuiGraphicsExtractor g,String text,int x,int y,int color,float fade) { SmoothFont.draw(g,text,x,y,alpha(color,fade),true,1.12f); }
    public static void brand(GuiGraphicsExtractor g,int x,int y,float fade) {
        g.blit(RenderPipelines.GUI_TEXTURED,Identifier.fromNamespaceAndPath("awassistant","icon.png"),x,y,0f,0f,24,24,1254,1254,1254,1254,alpha(0xFFFFFFFF,fade));
    }
    public static void copyIcon(GuiGraphicsExtractor g,int x,int y,int color) {
        g.fill(x,y,x+9,y+1,color);g.fill(x,y,x+1,y+11,color);
        g.fill(x+3,y+3,x+13,y+4,color);g.fill(x+3,y+3,x+4,y+15,color);
        g.fill(x+12,y+3,x+13,y+15,color);g.fill(x+3,y+14,x+13,y+15,color);
    }
    public static String ellipsis(Font font, String text, int width) {
        if (width(text) <= width) return text;
        int low=0,high=text.codePointCount(0,text.length());
        while(low<high) {
            int middle=(low+high+1)/2,end=text.offsetByCodePoints(0,middle);
            if(width(text.substring(0,end)+"…")<=width)low=middle;else high=middle-1;
        }
        return text.substring(0,text.offsetByCodePoints(0,low))+"…";
    }
    public static void scrollBar(GuiGraphicsExtractor g,int x,int y,int height,int content,float offset,float fade) {
        if(content<=height)return;
        int thumb=Math.max(24,Math.round(height*(float)height/content));
        int top=y+Math.round((height-thumb)*Math.min(1,offset/(content-height)));
        round(g,x,top,2,thumb,1,alpha(0xFF687782,fade*.7f));
    }
    public static boolean hit(double mx,double my,int x,int y,int w,int h) { return mx>=x &&my>=y&&mx<x+w&&my<y+h; }
    public static void searchIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        round(g,x,y,10,10,5,color); round(g,x+1,y+1,8,8,4,alpha(0xFF242B33,(color>>>24)/255f));
        for(int i=0;i<4;i++)g.fill(x+8+i,y+8+i,x+10+i,y+10+i,color);
    }
    public static void cross(GuiGraphicsExtractor g,int x,int y,int color) {
        for (int i=0;i<9;i++) { g.fill(x+i,y+i,x+i+1,y+i+1,color);g.fill(x+8-i,y+i,x+9-i,y+i+1,color); }
    }
}
