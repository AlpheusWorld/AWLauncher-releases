package ru.aw.assistant;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.*;
import ru.aw.assistant.ui.*;
import java.nio.file.*;
import java.util.*;

public final class AssistantClientSmoke implements ClientModInitializer {
    private int ticks,stage,since;
    private String clipboard;
    private boolean captured;
    private String pendingCapture;
    private int captureAt;
    @Override public void onInitializeClient(){
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            try {
                ticks++;Screen screen=ClientBridge.screen(client);
                if(pendingCapture!=null&&ticks>=captureAt){String name=pendingCapture;pendingCapture=null;takeCapture(client,name);}
                if(stage==0&&screen instanceof TitleScreen&&ClientBridge.ready(client)){
                    clipboard=client.keyboardHandler.getClipboard();net.minecraft.client.KeyMapping.click(ClientBridge.keyboard().getOrCreate(InputConstants.KEY_F8));next(1);
                }else if(stage==1&&screen instanceof RulesScreen&&ticks-since>35){
                    if((Boolean)field(screen,"loading")||(Boolean)field(screen,"indexing"))return;
                    if(!UiRenderer.ready())return;
                    type(client,screen,"НЕ ВВОДИТЬ");
                    if(!input(screen).value().isEmpty())throw new IllegalStateException("Typing without focus changed search");
                    capture(client,"workspace.png");next(2);
                }else if(stage==2&&ticks-since>8&&captured){
                    focus(screen);type(client,screen,"ТЕСТ");next(3);
                }else if(stage==3&&ticks-since>10){
                    if(((List<?>)field(screen,"matches")).size()!=18)throw new IllegalStateException("Russian query must find the fixture rules");
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN,0,0));capture(client,"search.png");next(4);
                }else if(stage==4&&ticks-since>8&&captured){
                    float s=num(screen,"scale");int x=(int)num(screen,"panelX"),w=(int)num(screen,"panelW"),top=(int)num(screen,"resultTop");
                    click(screen,(x+w-47)*s,(top+29)*s);
                    if(!client.keyboardHandler.getClipboard().contains("не правила сервера"))throw new IllegalStateException("Copy failed");
                    String id=(String)field(screen,"serverId");String number=((List<ru.aw.assistant.rules.RuleSearch.Match>)field(screen,"matches")).get((int)num(screen,"selected")).rule().number();
                    if(AWAssistant.RULES.favorite(id,number))AWAssistant.RULES.toggleFavorite(id,number);
                    click(screen,(x+w-87)*s,(top+29)*s);navigate(screen,1);next(5);
                }else if(stage==5&&ticks-since>8){
                    if(((List<?>)field(screen,"matches")).isEmpty())throw new IllegalStateException("Saved rules are empty");
                    capture(client,"favorites.png");next(6);
                }else if(stage==6&&ticks-since>8&&captured){
                    navigate(screen,2);if(((List<?>)field(screen,"queries")).isEmpty())throw new IllegalStateException("Query history is empty");capture(client,"history.png");next(7);
                }else if(stage==7&&ticks-since>8&&captured){navigate(screen,3);capture(client,"changes.png");next(8);
                }else if(stage==8&&ticks-since>8&&captured){navigate(screen,4);capture(client,"settings.png");next(9);
                }else if(stage==9&&ticks-since>8&&captured){
                    int old=(int)num(screen,"selected");client.options.guiScale().set(1);client.resizeGui();
                    if((num(screen,"panelX")+num(screen,"panelW"))*num(screen,"scale")>screen.width)throw new IllegalStateException("Workspace outside viewport");
                    if((int)num(screen,"selected")!=old)throw new IllegalStateException("Resize lost selection");
                    float s=num(screen,"scale");int x=(int)num(screen,"panelX"),y=(int)num(screen,"panelY"),h=(int)num(screen,"bodyH");
                    click(screen,(x+num(screen,"panelW")-130)*s,(y+32)*s);next(10);
                }else if(stage==10&&screen.getClass().getName().contains("AssistantHud$Editor")&&ticks-since>10){
                    AWAssistant.RULES.saveHud(ru.aw.assistant.rules.RuleRepository.defaultHud());capture(client,"hud-library.png");next(11);
                }else if(stage==11&&ticks-since>8&&captured){
                    float s=num(screen,"scale");int x=(int)num(screen,"panelX"),y=(int)num(screen,"panelY"),w=(int)num(screen,"panelW");
                    click(screen,(x+20+2*194+150)*s,(y+168+77)*s);
                    if(!AWAssistant.RULES.hud().get("coordinates").enabled())throw new IllegalStateException("HUD toggle failed");
                    click(screen,(x+w-170)*s,(y+32)*s);next(12);
                }else if(stage==12&&ticks-since>8){
                    if(!(Boolean)field(screen,"arranging"))throw new IllegalStateException("HUD arrange mode did not open");
                    var bounds=(Map<?,?>)field(AWAssistant.HUD,"bounds");var box=bounds.get("coordinates");
                    if(box==null)throw new IllegalStateException("Enabled widget not drawn");int x=component(box,"x"),y=component(box,"y");
                    click(screen,x+3,y+3);var info=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
                    screen.mouseDragged(new MouseButtonEvent(180,130,info),150,100);screen.mouseReleased(new MouseButtonEvent(180,130,info));
                    double old=AWAssistant.RULES.hud().get("coordinates").scale();screen.mouseScrolled(180,130,0,1);
                    if(AWAssistant.RULES.hud().get("coordinates").scale()<=old)throw new IllegalStateException("HUD scaling failed");next(13);
                }else if(stage==13&&ticks-since>8){capture(client,"hud-placement.png");next(14);
                }else if(stage==14&&ticks-since>8&&captured){
                    screen.onClose();screen=ClientBridge.screen(client);navigate(screen,0);var select=screen.getClass().getDeclaredMethod("selectServer",String.class);select.setAccessible(true);select.invoke(screen,"holyworld");next(15);
                }else if(stage==15&&ticks-since>20){
                    if((Boolean)field(screen,"indexing"))return;focus(screen);type(client,screen,"читы");next(16);
                }else if(stage==16&&ticks-since>15){capture(client,"showcase-rules.png");next(17);
                }else if(stage==17&&ticks-since>8&&captured){
                    screen.onClose();ClientBridge.screen(client,new TitleScreen());client.keyboardHandler.setClipboard(clipboard);
                    Files.writeString(client.gameDirectory.toPath().resolve("awassistant-smoke.ok"),"Unified rules/favorites/history/changes/settings, native text focus and Russian input, copy, resize, HUD library toggles, arrangement, drag and scaling verified");client.stop();stage=99;
                }
                if(ticks>1400&&stage!=99)throw new IllegalStateException("UI check timeout at "+stage);
            }catch(Exception failure){
                if(clipboard!=null)client.keyboardHandler.setClipboard(clipboard);
                try{Files.writeString(client.gameDirectory.toPath().resolve("awassistant-smoke.fail"),failure.toString());}catch(Exception ignored){}
                client.stop();stage=99;
            }
        });
    }
    private void next(int value){stage=value;since=ticks;}
    private Object field(Object target,String key)throws Exception{var f=target.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(target);}
    private float num(Object target,String key)throws Exception{return ((Number)field(target,key)).floatValue();}
    private int component(Object target,String key)throws Exception{var m=target.getClass().getDeclaredMethod(key);m.setAccessible(true);return (int)m.invoke(target);}
    private SearchInput input(Screen screen)throws Exception{return (SearchInput)field(screen,"input");}
    private void click(Screen screen,double x,double y){screen.mouseClicked(new MouseButtonEvent(x,y,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);}
    private void navigate(Screen screen,int page)throws Exception{float s=num(screen,"scale");int x=(int)num(screen,"panelX"),y=(int)num(screen,"panelY"),w=(int)num(screen,"panelW");int nx=page==4?w-82:232+switch(page){case 0->45;case 1->160;case 2->280;default->406;};click(screen,(x+nx)*s,(y+32)*s);}
    private void focus(Screen screen)throws Exception{float s=num(screen,"scale");click(screen,(num(screen,"panelX")+240)*s,(num(screen,"panelY")+109)*s);}
    private void type(net.minecraft.client.Minecraft client,Screen screen,String value)throws Exception{
        try{client.keyboardHandler.getClass().getMethod("textInput",long.class,String.class).invoke(client.keyboardHandler,client.getWindow().handle(),value);}
        catch(NoSuchMethodException legacy){var m=client.keyboardHandler.getClass().getDeclaredMethod("charTyped",long.class,CharacterEvent.class);m.setAccessible(true);for(int cp:value.codePoints().toArray())m.invoke(client.keyboardHandler,client.getWindow().handle(),new CharacterEvent(cp));}
    }
    private void capture(net.minecraft.client.Minecraft client,String name){captured=false;pendingCapture=name;captureAt=ticks+2;}
    private void takeCapture(net.minecraft.client.Minecraft client,String name){Screenshot.takeScreenshot(ClientBridge.renderTarget(client),image->{
        try{var folder=client.gameDirectory.toPath().resolve("awassistant-previews");Files.createDirectories(folder);image.writeToFile(folder.resolve(name));client.execute(()->captured=true);}catch(Exception failure){throw new RuntimeException(failure);}finally{image.close();}
    });}
}
