package ru.aw.assistant.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import ru.aw.assistant.AWAssistant;
import ru.aw.assistant.ClientBridge;
import ru.aw.assistant.rules.RuleRepository;
import java.util.*;

/** Native HUD extraction. Game state is sampled on the client tick; drawing never performs I/O. */
public final class AssistantHud {
    private static final Map<String,String> LABELS=Map.of("fps","FPS","ping","Пинг","coordinates","Координаты","armor","Броня","effects","Эффекты");
    private record Content(List<String> lines,List<ItemStack> items) {}
    private record Box(int x,int y,int w,int h) {boolean contains(double mx,double my){return mx>=x&&my>=y&&mx<x+w&&my<y+h;}}
    private final Map<String,Content> content=new LinkedHashMap<>();
    private final Map<String,Box> bounds=new LinkedHashMap<>();
    private int ticks;
    public void register(){HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,Identifier.fromNamespaceAndPath("awassistant","hud"),(g,delta)->{
        var client=Minecraft.getInstance();if(client.player!=null&&client.level!=null&&!(ClientBridge.screen(client) instanceof Editor))draw(g,false,null);
    });}
    public void tick(Minecraft client){
        if(++ticks%5!=0||client.player==null||AWAssistant.RULES.hud().values().stream().noneMatch(RuleRepository.HudWidget::enabled)&&!(ClientBridge.screen(client) instanceof Editor))return;
        var player=client.player;content.put("fps",new Content(List.of(client.getFps()+" FPS"),List.of()));
        var info=client.getConnection()==null?null:client.getConnection().getPlayerInfo(player.getUUID());
        content.put("ping",new Content(List.of(client.hasSingleplayerServer()?"Локальная игра":info==null?"Пинг: —":info.getLatency()+" мс"),List.of()));
        var pos=player.blockPosition();content.put("coordinates",new Content(List.of("XYZ  "+pos.getX()+"  "+pos.getY()+"  "+pos.getZ()),List.of()));
        var items=new ArrayList<ItemStack>();var armor=new ArrayList<String>();
        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){
            var stack=player.getItemBySlot(slot).copy();items.add(stack);
            armor.add(stack.isEmpty()?"—":stack.isDamageableItem()?(stack.getMaxDamage()-stack.getDamageValue())+" / "+stack.getMaxDamage():stack.getHoverName().getString());
        }content.put("armor",new Content(armor,items));
        var effects=new ArrayList<String>();for(var effect:player.getActiveEffects().stream().sorted(Comparator.comparing(e->e.getEffect().value().getDescriptionId())).limit(20).toList()){
            int seconds=Math.max(0,effect.getDuration()/20);effects.add(Component.translatable(effect.getEffect().value().getDescriptionId()).getString()+" "+(effect.getAmplifier()+1)+"  "+(effect.isInfiniteDuration()?"∞":String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60)));
        }content.put("effects",new Content(effects.isEmpty()?List.of("Нет эффектов"):List.copyOf(effects),List.of()));
    }
    private Content data(String id){
        var actual=content.get(id);if(actual!=null&&Minecraft.getInstance().player!=null)return actual;
        return new Content(List.of(switch(id){case "fps"->Minecraft.getInstance().getFps()+" FPS";case "ping"->"Пинг —";case "coordinates"->"X 128   Y 64   Z −256";case "armor"->"Прочность экипировки";default->"Скорость II  1:24";}),List.of());
    }
    private void draw(GuiGraphicsExtractor g,boolean editor,String selected){
        var client=Minecraft.getInstance();bounds.clear();float gui=client.getWindow().getGuiScale();
        for(var entry:AWAssistant.RULES.hud().entrySet()){
            var widget=entry.getValue();if(widget==null||!widget.enabled()&&(!editor||!entry.getKey().equals(selected)))continue;
            var value=data(entry.getKey());int extra=value.items().isEmpty()?0:24,row=value.items().isEmpty()?24:27;
            float zoom=(float)widget.scale()*Math.clamp(client.getWindow().getHeight()/850f,.85f,2f)/gui;int maxWidth=Math.max(64,(int)(g.guiWidth()/zoom)-16);
            var text=value.lines().stream().map(line->UiRenderer.ellipsis(client.font,line,Math.round((maxWidth-20-extra)/1.0667f))).toList();
            int w=Math.max(62,text.stream().mapToInt(line->UiRenderer.measure(line,16,false)).max().orElse(50)+20+extra);
            int maxRows=Math.max(1,(int)(g.guiHeight()/zoom-12)/row);int count=Math.min(text.size(),maxRows),h=count*row+12;
            int sw=Math.round(w*zoom),sh=Math.round(h*zoom);
            int x=(int)Math.round(Math.max(0,g.guiWidth()-sw)*widget.x()),y=(int)Math.round(Math.max(0,g.guiHeight()-sh)*widget.y());
            bounds.put(entry.getKey(),new Box(x,y,sw,sh));g.pose().pushMatrix();g.pose().translate((float)x,(float)y);g.pose().scale(zoom,zoom);
            UiRenderer.round(g,0,0,w,h,5,widget.enabled()?0xCC151A1F:0x99151A1F);
            if(editor){int border=entry.getKey().equals(selected)?UiRenderer.ACCENT:0x667F8C96;g.fill(0,0,w,1,border);g.fill(0,h-1,w,h,border);g.fill(0,0,1,h,border);g.fill(w-1,0,w,h,border);}
            for(int i=0;i<count;i++){int ty=7+i*row;if(!value.items().isEmpty())g.fakeItem(value.items().get(i),7,ty);
                UiRenderer.label(g,text.get(i),10+extra,ty+2,16,false,widget.enabled()?UiRenderer.TEXT:UiRenderer.MUTED,1);}
            g.pose().popMatrix();
        }
    }
    public Screen editor(Screen parent){return new Editor(parent);}
    private final class Editor extends Screen {
        private final Screen parent;
        private String selected="fps",error="";
        private boolean dragging,arranging,scaling;
        private double dragX,dragY;
        private int panelX,panelY,panelW=1000,panelH=568;
        private float scale=1;
        private Editor(Screen parent){super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("Редактор HUD"));this.parent=parent;}
        @Override protected void init(){UiRenderer.prepare();var frame=UiRenderer.workspace(minecraft,panelW,panelH,.5,.5);panelX=frame.x();panelY=frame.y();scale=frame.scale();}
        private RuleRepository.HudWidget selected(){return AWAssistant.RULES.hud().get(selected);}
        private void preview(RuleRepository.HudWidget widget){var widgets=new LinkedHashMap<>(AWAssistant.RULES.hud());widgets.put(selected,widget);AWAssistant.RULES.previewHud(widgets);}
        private void update(RuleRepository.HudWidget widget){preview(widget);save();}
        private void save(){AWAssistant.RULES.saveHud(AWAssistant.RULES.hud()).exceptionally(f->{minecraft.execute(()->error="Не удалось сохранить HUD");return null;});}
        private void toggle(String id){selected=id;var v=selected();update(new RuleRepository.HudWidget(!v.enabled(),v.x(),v.y(),v.scale()));}
        private int cardX(int index){return panelX+20+index*194;}
        private String hint(String id){return switch(id){case "fps"->"Частота кадров";case "ping"->"Связь с сервером";case "coordinates"->"Положение в мире";case "armor"->"Прочность брони";default->"Активные эффекты";};}
        private void button(GuiGraphicsExtractor g,String text,int x,int y,int w,boolean active,double mx,double my){UiRenderer.button(g,text,x,y,w,40,active,UiRenderer.hit(mx,my,x,y,w,40),1);}
        private void selectedPreview(GuiGraphicsExtractor g,int x,int y,int w,int h){
            var value=data(selected);int extra=value.items().isEmpty()?0:28;
            int pw=Math.max(92,value.lines().stream().mapToInt(line->UiRenderer.measure(line,18,false)).max().orElse(100)+32+extra),ph=Math.min(4,value.lines().size())*28+18;
            if(selected.equals("armor")&&(minecraft.player==null||value.items().isEmpty())){pw=230;ph=60;}
            pw=Math.min(pw,w-32);int px=x+(w-pw)/2,py=y+(h-ph)/2;
            UiRenderer.round(g,px,py,pw,ph,7,0xFF23292D);
            if(selected.equals("armor")&&(minecraft.player==null||value.items().isEmpty())){
                var textures=List.of("diamond_helmet","diamond_chestplate","diamond_leggings","diamond_boots");
                for(int j=0;j<textures.size();j++)g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,Identifier.fromNamespaceAndPath("minecraft","textures/item/"+textures.get(j)+".png"),px+18+j*52,py+16,0f,0f,28,28,16,16,16,16,0xFFFFFFFF);
            }else for(int i=0;i<Math.min(4,value.lines().size());i++){
                if(!value.items().isEmpty())g.fakeItem(value.items().get(i),px+14,py+12+i*28);
                UiRenderer.label(g,UiRenderer.fit(value.lines().get(i),pw-32-extra,18,false),px+16+extra,py+12+i*28,18,false,UiRenderer.TEXT,1);
            }
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float partial){
            if(minecraft.level==null)extractPanorama(g,partial);
            if(!arranging&&AWAssistant.blur)extractBlurredBackground(g);g.fill(0,0,width,height,arranging?0x28060708:0x78060708);
            if(arranging){draw(g,true,selected);if(dragging){g.fill(width/2,0,width/2+1,height,0x4461D9A6);g.fill(0,height/2,width,height/2+1,0x4461D9A6);}}
            double px=mx/scale,py=my/scale;g.pose().pushMatrix();g.pose().scale(scale,scale);
            if(arranging){
                int x=panelX+panelW/2-264,y=panelY+8;UiRenderer.panel(g,x,y,528,64,1);
                UiRenderer.icon(g,UiRenderer.Icon.MOVE,x+18,y+21,22,UiRenderer.ACCENT,1);UiRenderer.label(g,"Размещение HUD",x+52,y+22,17,true,UiRenderer.TEXT,1);
                button(g,"Элементы",x+264,y+12,132,false,px,py);UiRenderer.action(g,"Готово",x+406,y+12,106,40,UiRenderer.hit(px,py,x+406,y+12,106,40),1);
                String tip="Перетаскивай элементы · колесо меняет размер · Tab — к элементам";int tw=UiRenderer.measure(tip,14,false)+36,tx=panelX+(panelW-tw)/2,ty=panelY+panelH-48;
                UiRenderer.round(g,tx,ty,tw,36,8,0xE8121416);UiRenderer.label(g,tip,tx+18,ty+10,14,false,UiRenderer.TEXT,1);
            }else{
                UiRenderer.panel(g,panelX,panelY,panelW,panelH,1);UiRenderer.brand(g,panelX+22,panelY+20,1);UiRenderer.label(g,"AWAssistant",panelX+58,panelY+21,21,true,UiRenderer.TEXT,1);
                UiRenderer.label(g,"Редактор HUD",panelX+252,panelY+23,18,true,UiRenderer.TEXT,1);
                UiRenderer.action(g,"Разместить на экране",panelX+panelW-284,panelY+12,220,40,UiRenderer.hit(px,py,panelX+panelW-284,panelY+12,220,40),1);
                UiRenderer.iconButton(g,UiRenderer.Icon.CLOSE,panelX+panelW-52,panelY+12,40,false,UiRenderer.hit(px,py,panelX+panelW-52,panelY+12,40,40),1);
                g.fill(panelX+1,panelY+64,panelX+panelW-1,panelY+65,UiRenderer.LINE);
                UiRenderer.label(g,"Твой HUD",panelX+20,panelY+88,26,true,UiRenderer.TEXT,1);UiRenderer.label(g,"Включи нужное и размести в удобном месте",panelX+20,panelY+128,15,false,UiRenderer.MUTED,1);
                int i=0;for(var entry:AWAssistant.RULES.hud().entrySet()){
                    String id=entry.getKey();var value=entry.getValue();int cx=cardX(i++),cy=panelY+168;boolean active=id.equals(selected),hover=UiRenderer.hit(px,py,cx,cy,184,100);
                    UiRenderer.round(g,cx,cy,184,100,10,active?0xFF477D62:hover?0xFF485158:UiRenderer.LINE);UiRenderer.round(g,cx+1,cy+1,182,98,9,active?0xFF20352B:UiRenderer.SURFACE);
                    UiRenderer.label(g,LABELS.get(id),cx+14,cy+18,16,true,active?UiRenderer.ACCENT:UiRenderer.TEXT,1);
                    UiRenderer.label(g,hint(id),cx+14,cy+48,12,false,UiRenderer.MUTED,1);UiRenderer.toggle(g,cx+130,cy+65,value.enabled(),UiRenderer.hit(px,py,cx+120,cy+59,60,36),1);
                }
                int cx=panelX+20,cy=panelY+288,cw=960,ch=226;UiRenderer.round(g,cx,cy,cw,ch,10,UiRenderer.SURFACE);
                UiRenderer.label(g,"ПРЕДПРОСМОТР",cx+20,cy+18,11,true,UiRenderer.MUTED,1);
                selectedPreview(g,cx+20,cy+46,596,154);g.fill(cx+640,cy+20,cx+641,cy+ch-20,UiRenderer.LINE);
                int ix=cx+664;UiRenderer.label(g,LABELS.get(selected),ix,cy+22,21,true,UiRenderer.TEXT,1);
                UiRenderer.label(g,"Масштаб",ix,cy+68,14,false,UiRenderer.MUTED,1);UiRenderer.label(g,Math.round(selected().scale()*100)+"%",cx+cw-76,cy+68,14,true,UiRenderer.ACCENT,1);
                button(g,"−",ix,cy+98,38,false,px,py);button(g,"+",cx+cw-58,cy+98,38,false,px,py);
                int tx=ix+52,tw=166,amount=(int)Math.round((selected().scale()-.5)/1.5*tw);UiRenderer.round(g,tx,cy+116,tw,4,2,UiRenderer.LINE);UiRenderer.round(g,tx,cy+116,Math.max(1,amount),4,2,UiRenderer.ACCENT);UiRenderer.round(g,tx+amount-5,cy+111,10,14,5,UiRenderer.TEXT);
                button(g,"Сбросить положение и размер",ix,cy+158,276,false,px,py);
                UiRenderer.label(g,"F9 · Закрыть",panelX+20,panelY+panelH-30,12,false,UiRenderer.MUTED,1);UiRenderer.label(g,"Сохраняется автоматически",panelX+panelW-224,panelY+panelH-30,12,false,UiRenderer.MUTED,1);
            }
            if(!error.isBlank())UiRenderer.label(g,error,panelX+24,panelY+panelH-20,13,false,0xFFFF9DA9,1);g.pose().popMatrix();
        }
        @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
            if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return true;double x=event.x()/scale,y=event.y()/scale;
            if(arranging){
                int tx=panelX+panelW/2-264,ty=panelY+8;
                if(UiRenderer.hit(x,y,tx+264,ty+12,132,40)){arranging=false;return true;}
                if(UiRenderer.hit(x,y,tx+406,ty+12,106,40)){onClose();return true;}
                for(var entry:bounds.entrySet())if(entry.getValue().contains(event.x(),event.y())){selected=entry.getKey();dragging=true;dragX=event.x()-entry.getValue().x();dragY=event.y()-entry.getValue().y();return true;}return true;
            }
            if(UiRenderer.hit(x,y,panelX+panelW-52,panelY+12,40,40)){onClose();return true;}
            if(UiRenderer.hit(x,y,panelX+panelW-284,panelY+12,220,40)){arranging=true;return true;}
            int i=0;for(var id:AWAssistant.RULES.hud().keySet()){int cx=cardX(i++),cy=panelY+168;if(UiRenderer.hit(x,y,cx,cy,184,100)){selected=id;if(UiRenderer.hit(x,y,cx+120,cy+59,60,36))toggle(id);return true;}}
            int ix=panelX+684,cy=panelY+288;var value=selected();
            if(UiRenderer.hit(x,y,ix,cy+98,38,40)){update(new RuleRepository.HudWidget(value.enabled(),value.x(),value.y(),value.scale()-.05));return true;}
            if(UiRenderer.hit(x,y,panelX+panelW-78,cy+98,38,40)){update(new RuleRepository.HudWidget(value.enabled(),value.x(),value.y(),value.scale()+.05));return true;}
            if(UiRenderer.hit(x,y,ix+46,cy+98,178,40)){scaling=true;changeScale(x);return true;}
            if(UiRenderer.hit(x,y,ix,cy+158,276,40)){var initial=RuleRepository.defaultHud().get(selected);update(new RuleRepository.HudWidget(value.enabled(),initial.x(),initial.y(),1));}return true;
        }
        private void changeScale(double x){var value=selected();preview(new RuleRepository.HudWidget(value.enabled(),value.x(),value.y(),.5+Math.clamp((x-panelX-736)/166,0,1)*1.5));}
        @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
            if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return false;if(scaling){changeScale(event.x()/scale);return true;}if(!dragging)return false;
            var box=bounds.get(selected);if(box==null)return false;var value=selected();double x=Math.clamp(event.x()-dragX,0,Math.max(0,width-box.w())),y=Math.clamp(event.y()-dragY,0,Math.max(0,height-box.h()));double snap=6/minecraft.getWindow().getGuiScale();
            if(Math.abs(x+box.w()/2.0-width/2.0)<snap)x=(width-box.w())/2.0;if(Math.abs(y+box.h()/2.0-height/2.0)<snap)y=(height-box.h())/2.0;
            preview(new RuleRepository.HudWidget(value.enabled(),x/Math.max(1,width-box.w()),y/Math.max(1,height-box.h()),value.scale()));bounds.put(selected,new Box((int)Math.round(x),(int)Math.round(y),box.w(),box.h()));return true;
        }
        @Override public boolean mouseReleased(MouseButtonEvent event){if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&(dragging||scaling)){dragging=scaling=false;save();return true;}return false;}
        @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
            if(!arranging)return true;String hovered=bounds.entrySet().stream().filter(e->e.getValue().contains(x,y)).map(Map.Entry::getKey).findFirst().orElse(null);if(hovered==null)return true;selected=hovered;var value=selected();update(new RuleRepository.HudWidget(value.enabled(),value.x(),value.y(),value.scale()+Math.signum(vertical)*.05));return true;
        }
        @Override public boolean keyPressed(KeyEvent event){if(event.key()==InputConstants.KEY_TAB){arranging=!arranging;return true;}if(AWAssistant.EDIT_HUD.matches(event)){onClose();return true;}return super.keyPressed(event);}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void onClose(){if(dragging||scaling){dragging=scaling=false;save();}ClientBridge.screen(minecraft,parent);}
        @Override public void removed(){if(dragging||scaling){dragging=scaling=false;save();}super.removed();}
        @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float tick){}
    }
}
