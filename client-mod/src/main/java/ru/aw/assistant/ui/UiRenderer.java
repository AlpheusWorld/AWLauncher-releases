package ru.aw.assistant.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

public final class UiRenderer {
    public static final int TEXT=0xFFF0F2F3, MUTED=0xFF969DA3, ACCENT=0xFF54D9A1;
    public static final int BACKGROUND=0xFC121416, SURFACE=0xFF1C1F22, HOVER=0xFF272C30, LINE=0xFF30363B;
    public enum Icon { BOOK, BOOKMARK, HISTORY, CHANGES, HUD, SETTINGS, CLOSE, SEARCH, COPY, EXTERNAL, CHEVRON, REFRESH, CHECK, TRASH, MOVE, SLIDERS, ARROW }
    public record Workspace(int x,int y,int width,int height,float scale,int virtualWidth,int virtualHeight) {}
    public static Workspace workspace(net.minecraft.client.Minecraft client,int width,int height,double px,double py){
        float pixels=Math.min(Math.clamp(client.getWindow().getHeight()/760f,1f,2f),Math.min((client.getWindow().getWidth()-36)/(float)width,(client.getWindow().getHeight()-36)/(float)height));
        float scale=pixels/client.getWindow().getGuiScale();
        int vw=Math.round(client.getWindow().getGuiScaledWidth()/scale),vh=Math.round(client.getWindow().getGuiScaledHeight()/scale);
        return new Workspace(18+(int)Math.round(Math.max(0,vw-width-36)*px),18+(int)Math.round(Math.max(0,vh-height-36)*py),width,height,scale,vw,vh);
    }
    public static int alpha(int color, float opacity) { return ((Math.round((color >>> 24) * opacity) & 255) << 24) | (color & 0xFFFFFF); }
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x,y,x+w,y+h,color); return; }
        g.fill(x+r,y,x+w-r,y+h,color);g.fill(x,y+r,x+r,y+h-r,color);g.fill(x+w-r,y+r,x+w,y+h-r,color);
        var texture=Identifier.fromNamespaceAndPath("awassistant","ui/round.png");
        g.blit(RenderPipelines.GUI_TEXTURED,texture,x,y,0f,0f,r,r,32,32,64,64,color);
        g.blit(RenderPipelines.GUI_TEXTURED,texture,x+w-r,y,32f,0f,r,r,32,32,64,64,color);
        g.blit(RenderPipelines.GUI_TEXTURED,texture,x,y+h-r,0f,32f,r,r,32,32,64,64,color);
        g.blit(RenderPipelines.GUI_TEXTURED,texture,x+w-r,y+h-r,32f,32f,r,r,32,32,64,64,color);
    }
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, float fade) {
        round(g,x-5,y+8,w+10,h+4,18,alpha(0x18000000,fade));
        round(g,x-2,y+3,w+4,h+2,16,alpha(0x30000000,fade));
        round(g,x,y,w,h,14,alpha(LINE,fade));
        round(g,x+1,y+1,w-2,h-2,13,alpha(BACKGROUND,fade));
    }
    public static void label(GuiGraphicsExtractor g,String value,int x,int y,int size,boolean bold,int color,float fade){SmoothFont.draw(g,value,x,y,alpha(color,fade),bold,size/15f);}
    public static int measure(String value,int size,boolean bold){return SmoothFont.width(value,bold,size/15f);}
    public static java.util.List<String> wrap(String value,int width,int size,boolean bold){return SmoothFont.wrap(value,width,bold,size/15f);}
    public static void button(GuiGraphicsExtractor g,String value,int x,int y,int w,int h,boolean active,boolean hovered,float fade){
        round(g,x,y,w,h,8,alpha(active?0xFF3E7358:0xFF374047,fade));
        round(g,x+1,y+1,w-2,h-2,7,alpha(active?0xFF264B3B:hovered?0xFF343D43:HOVER,fade));
        label(g,value,x+(w-measure(value,16,true))/2,y+(h-20)/2,16,true,active?ACCENT:TEXT,fade);
    }
    public static void action(GuiGraphicsExtractor g,String value,int x,int y,int w,int h,boolean hovered,float fade){
        round(g,x,y,w,h,8,alpha(hovered?0xFF73E4B5:ACCENT,fade));
        label(g,value,x+(w-measure(value,16,true))/2,y+(h-20)/2,16,true,0xFF103327,fade);
    }
    public static void outline(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean focused,float fade){
        round(g,x,y,w,h,9,alpha(focused?0xFF4D9675:LINE,fade));round(g,x+1,y+1,w-2,h-2,8,alpha(SURFACE,fade));
    }
    public static void icon(GuiGraphicsExtractor g,Icon icon,int x,int y,int size,int color,float fade){
        g.blit(RenderPipelines.GUI_TEXTURED,Identifier.fromNamespaceAndPath("awassistant","ui/icons.png"),x,y,icon.ordinal()*64f,0f,size,size,64,64,Icon.values().length*64,64,alpha(color,fade));
    }
    public static void iconButton(GuiGraphicsExtractor g,Icon icon,int x,int y,int size,boolean active,boolean hovered,float fade){
        if(active||hovered)round(g,x,y,size,size,8,alpha(active?0xFF264336:HOVER,fade));
        icon(g,icon,x+(size-20)/2,y+(size-20)/2,20,active?ACCENT:MUTED,fade);
    }
    public static String fit(String text,int width,int size,boolean bold){
        if(measure(text,size,bold)<=width)return text;
        int low=0,high=text.codePointCount(0,text.length());
        while(low<high){int middle=(low+high+1)/2,end=text.offsetByCodePoints(0,middle);if(measure(text.substring(0,end)+"…",size,bold)<=width)low=middle;else high=middle-1;}
        return text.substring(0,text.offsetByCodePoints(0,low))+"…";
    }
    public static void toggle(GuiGraphicsExtractor g,int x,int y,boolean enabled,boolean hovered,float fade){
        round(g,x,y,40,24,12,alpha(enabled?ACCENT:hovered?0xFF49535E:0xFF343D46,fade));
        round(g,x+(enabled?19:3),y+3,18,18,9,alpha(enabled?0xFF123527:0xFFE4E9ED,fade));
    }
    public static void text(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color, float fade) {
        SmoothFont.draw(g,value,x,y,alpha(color,fade),false,1);
    }
    public static void prepare() { SmoothFont.prepare(); }
    public static void preload() { SmoothFont.preload(); }
    public static boolean ready() { return SmoothFont.ready(); }
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
    public static void chevron(GuiGraphicsExtractor g,int x,int y,int color) { for(int i=0;i<4;i++){g.fill(x+i,y+i,x+i+1,y+i+1,color);g.fill(x+7-i,y+i,x+8-i,y+i+1,color);} }
    public static void cross(GuiGraphicsExtractor g,int x,int y,int color) {
        for (int i=0;i<9;i++) { g.fill(x+i,y+i,x+i+1,y+i+1,color);g.fill(x+8-i,y+i,x+9-i,y+i+1,color); }
    }
}
