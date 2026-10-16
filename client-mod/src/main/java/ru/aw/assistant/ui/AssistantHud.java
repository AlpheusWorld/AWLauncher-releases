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
    private Content data(String id,boolean editor){var actual=content.get(id);if(actual!=null)return actual;return new Content(List.of(switch(id){case "fps"->"144 FPS";case "ping"->"32 мс";case "coordinates"->"XYZ  128  64  -256";case "armor"->"Броня · прочность";default->"Скорость II  1:24";}),List.of());}
    private void draw(GuiGraphicsExtractor g,boolean editor,String selected){
        var client=Minecraft.getInstance();bounds.clear();
        for(var entry:AWAssistant.RULES.hud().entrySet()){
            var widget=entry.getValue();if(widget==null||!editor&&!widget.enabled())continue;
            var value=data(entry.getKey(),editor);int extra=value.items().isEmpty()?0:22;
            int w=Math.max(48,value.lines().stream().mapToInt(client.font::width).max().orElse(40)+14+extra);
            int row=value.items().isEmpty()?12:20,h=value.lines().size()*row+10;
            float scale=(float)widget.scale();int sw=Math.round(w*scale),sh=Math.round(h*scale);
            int x=(int)Math.round(Math.max(0,g.guiWidth()-sw)*widget.x()),y=(int)Math.round(Math.max(0,g.guiHeight()-sh)*widget.y());
            bounds.put(entry.getKey(),new Box(x,y,sw,sh));g.pose().pushMatrix();g.pose().translate((float)x,(float)y);g.pose().scale(scale,scale);
            UiRenderer.round(g,0,0,w,h,3,widget.enabled()?0xB812171C:0x7712171C);
            if(editor){int border=entry.getKey().equals(selected)?UiRenderer.ACCENT:0xAA82919C;g.fill(0,0,w,1,border);g.fill(0,h-1,w,h,border);g.fill(0,0,1,h,border);g.fill(w-1,0,w,h,border);}
            for(int i=0;i<value.lines().size();i++){int ty=6+i*row;if(!value.items().isEmpty()){g.fakeItem(value.items().get(i),5,ty-2);}
                g.text(client.font,value.lines().get(i),7+extra,ty,widget.enabled()?UiRenderer.TEXT:UiRenderer.MUTED,false);}
            g.pose().popMatrix();
        }
    }
    public Screen editor(Screen parent){return new Editor(parent);}
    private final class Editor extends Screen {
        private final Screen parent;
        private String selected="fps",error="";
        private boolean dragging;
        private boolean controls=true;
        private double dragX,dragY;
        private int panel;
        private Editor(Screen parent){super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("Редактор HUD"));this.parent=parent;}
        private RuleRepository.HudWidget selected(){return AWAssistant.RULES.hud().get(selected);}
        private void update(RuleRepository.HudWidget widget){var widgets=new LinkedHashMap<>(AWAssistant.RULES.hud());widgets.put(selected,widget);save(widgets);}
        private void save(Map<String,RuleRepository.HudWidget> widgets){AWAssistant.RULES.saveHud(widgets).exceptionally(f->{minecraft.execute(()->error="Не удалось сохранить HUD");return null;});}
        private void button(GuiGraphicsExtractor g,String text,int y,boolean active){UiRenderer.round(g,panel+8,y,126,18,3,active?0xFF28513B:0xFF29323A);g.text(font,text,panel+13,y+5,active?UiRenderer.ACCENT:UiRenderer.TEXT,false);}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float partial){
            if(minecraft.level==null)extractPanorama(g,partial);g.fill(0,0,width,height,0x55000000);draw(g,true,selected);
            panel=Math.max(4,width-146);
            if(controls){
            UiRenderer.round(g,panel,6,142,Math.min(height-12,226),5,0xF0181F25);
            g.text(font,"Настройка HUD",panel+10,16,UiRenderer.TEXT,false);
            int i=0;for(var entry:AWAssistant.RULES.hud().entrySet())button(g,LABELS.get(entry.getKey())+(entry.getValue().enabled()?" · вкл":" · выкл"),34+i++*22,entry.getKey().equals(selected));
            var widget=selected();button(g,widget.enabled()?"Выключить элемент":"Включить элемент",148,false);
            button(g,"−       "+Math.round(widget.scale()*100)+"%       +",170,false);button(g,"Сбросить позиции",192,false);
            button(g,"Готово · Esc",214,false);
            }
            if(!error.isBlank())g.text(font,error,8,height-14,0xFFFF8996,false);
            else {g.text(font,"Перетаскивай элементы",8,height-26,UiRenderer.TEXT,false);g.text(font,"Tab: настройки · колесо: размер",8,height-14,UiRenderer.TEXT,false);}
        }
        @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
            if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return true;double x=event.x(),y=event.y();
            if(controls&&x>=panel&&x<panel+142){int i=0;for(var id:AWAssistant.RULES.hud().keySet()){if(y>=34+i*22&&y<52+i*22){selected=id;return true;}i++;}
                var value=selected();if(y>=148&&y<166)update(new RuleRepository.HudWidget(!value.enabled(),value.x(),value.y(),value.scale()));
                if(y>=170&&y<188)update(new RuleRepository.HudWidget(value.enabled(),value.x(),value.y(),value.scale()+(x>panel+71?.1:-.1)));
                if(y>=192&&y<210){var widgets=new LinkedHashMap<String,RuleRepository.HudWidget>();AWAssistant.RULES.hud().forEach((id,v)->{var initial=RuleRepository.defaultHud().get(id);widgets.put(id,new RuleRepository.HudWidget(v.enabled(),initial.x(),initial.y(),1));});save(widgets);}
                if(y>=214&&y<232)onClose();return true;
            }
            for(var entry:bounds.entrySet())if(entry.getValue().contains(x,y)){selected=entry.getKey();dragging=true;dragX=x-entry.getValue().x();dragY=y-entry.getValue().y();return true;}return true;
        }
        @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){if(!dragging)return false;var box=bounds.get(selected);if(box==null)return false;var value=selected();
            var widget=new RuleRepository.HudWidget(value.enabled(),(event.x()-dragX)/Math.max(1,width-box.w()),(event.y()-dragY)/Math.max(1,height-box.h()),value.scale());
            // Update the in-memory preview; only release writes the final position to disk.
            preview(widget);return true;
        }
        private void preview(RuleRepository.HudWidget widget){var widgets=new LinkedHashMap<>(AWAssistant.RULES.hud());widgets.put(selected,widget);AWAssistant.RULES.previewHud(widgets);}
        @Override public boolean mouseReleased(MouseButtonEvent event){if(dragging&&event.button()==InputConstants.MOUSE_BUTTON_LEFT){dragging=false;save(AWAssistant.RULES.hud());return true;}return false;}
        @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){var value=selected();update(new RuleRepository.HudWidget(value.enabled(),value.x(),value.y(),value.scale()+Math.signum(vertical)*.1));return true;}
        @Override public boolean keyPressed(KeyEvent event){if(event.key()==InputConstants.KEY_TAB){controls=!controls;return true;}if(AWAssistant.EDIT_HUD.matches(event)){onClose();return true;}return super.keyPressed(event);}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void onClose(){if(dragging){dragging=false;save(AWAssistant.RULES.hud());}ClientBridge.screen(minecraft,parent);}
        @Override public void removed(){if(dragging){dragging=false;save(AWAssistant.RULES.hud());}super.removed();}
        @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float tick){}
    }
}
