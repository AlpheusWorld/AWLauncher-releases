package ru.aw.assistant.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

public final class UiRenderer {
    public static final int TEXT = 0xFFF1F2F3, MUTED = 0xFF98A2AC, ACCENT = 0xFF58CC91;
    public static final FontDescription FACE = new FontDescription.Resource(Identifier.fromNamespaceAndPath("awassistant", "ui"));
    public static Component label(String text) { return Component.literal(text).withStyle(style -> style.withFont(FACE)); }
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
        round(g,x,y,w,h,12,alpha(0xFF424B54,fade*.65f));
        round(g,x+1,y+1,w-2,h-2,11,alpha(0xF21B2027,fade));
    }
    public static void text(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color, float fade) {
        g.text(font,label(value),x,y,alpha(color,fade),false);
    }
    public static String ellipsis(Font font, String text, int width) {
        if (font.width(label(text)) <= width) return text;
        while (!text.isEmpty() && font.width(label(text+"…")) > width) text=text.substring(0,text.offsetByCodePoints(text.length(),-1));
        return text+"…";
    }
    public static void scrollBar(GuiGraphicsExtractor g,int x,int y,int height,int content,float offset,float fade) {
        if(content<=height)return;
        int thumb=Math.max(24,Math.round(height*(float)height/content));
        int top=y+Math.round((height-thumb)*Math.min(1,offset/(content-height)));
        round(g,x,top,2,thumb,1,alpha(0xFF687782,fade*.7f));
    }
    public static boolean hit(double mx,double my,int x,int y,int w,int h) { return mx>=x &&my>=y&&mx<x+w&&my<y+h; }
    public static void searchIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        round(g,x,y,10,10,5,color); round(g,x+1,y+1,8,8,4,0xFF242B33);
        for(int i=0;i<4;i++)g.fill(x+8+i,y+8+i,x+10+i,y+10+i,color);
    }
    public static void cross(GuiGraphicsExtractor g,int x,int y,int color) {
        for (int i=0;i<9;i++) { g.fill(x+i,y+i,x+i+1,y+i+1,color);g.fill(x+8-i,y+i,x+9-i,y+i+1,color); }
    }
}
