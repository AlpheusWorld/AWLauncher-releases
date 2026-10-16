package ru.aw.assistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import ru.aw.assistant.ui.RulesScreen;
import java.nio.file.Files;
import java.util.List;

/** Separate test mod, never included in the production AWAssistant jar. */
public final class AssistantClientSmoke implements ClientModInitializer {
    private int ticks, stage, since;
    private String originalClipboard;
    private boolean captured;
    private int baselineFps,expandedFps,clearFps;
    private long lastTick,firstOpenGap;
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                ticks++;
                var screen=ClientBridge.screen(client);
                long now=System.nanoTime();
                if(stage==1 &&lastTick!=0)firstOpenGap=Math.max(firstOpenGap,now-lastTick);
                lastTick=now;
                if(stage==0 && screen instanceof TitleScreen && ClientBridge.ready(client)) {
                    if(!AWAssistant.OPEN.getTranslatedKeyMessage().getString().equals("F8"))throw new IllegalStateException("The registered binding is not the native F8 key");
                    baselineFps=client.getFps();originalClipboard=client.keyboardHandler.getClipboard();
                    net.minecraft.client.KeyMapping.click(ClientBridge.keyboard().getOrCreate(InputConstants.KEY_F8));stage=1;since=ticks;
                } else if(stage==1 &&screen instanceof RulesScreen &&ticks-since>40) {
                    if(Boolean.getBoolean("awassistant.enforceTiming")&&firstOpenGap>500_000_000L)throw new IllegalStateException("First opening blocked game ticks for "+firstOpenGap/1_000_000.0+" ms");
                    verifyWrapping();
                    capture(client,"compact.png");
                    stage=11;since=ticks;
                } else if(stage==11 &&screen instanceof RulesScreen &&ticks-since>8 &&captured) {
                    typeText(client,screen,"НЕ ДОЛЖНО ВВЕСТИСЬ");
                    if(!((ru.aw.assistant.ui.SearchInput)field(screen,"input")).value().isEmpty())throw new IllegalStateException("Typing without focus changed query");
                    focusInput(screen);verifyNativeFocus(client,true);
                    typeText(client,screen,"ТЕСТ");stage=2;since=ticks;
                } else if(stage==2 && screen instanceof RulesScreen && ticks-since>35) {
                    var results=RulesScreen.class.getDeclaredField("matches");results.setAccessible(true);
                    if(((List<?>)results.get(screen)).size()!=18)throw new IllegalStateException("Russian input/search did not find all fixture rules");
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_A,0,InputConstants.MOD_CONTROL));
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_C,0,InputConstants.MOD_CONTROL));
                    if(!client.keyboardHandler.getClipboard().equals("ТЕСТ")) {
                        if(ticks-since<75)return; // Windows can temporarily lock its shared clipboard.
                        throw new IllegalStateException("Native Ctrl+A/C did not copy the query");
                    }
                    screen.charTyped(new CharacterEvent('Т'));screen.charTyped(new CharacterEvent('Е'));screen.charTyped(new CharacterEvent('С'));screen.charTyped(new CharacterEvent('Т'));
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN,0,0));screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    stage=3;since=ticks;
                } else if(stage==3 &&screen instanceof RulesScreen &&ticks-since>120) {
                    expandedFps=client.getFps();capture(client,"expanded.png");stage=12;since=ticks;
                } else if(stage==12 &&screen instanceof RulesScreen &&ticks-since>8 &&captured) {
                    AWAssistant.blur=false;stage=17;since=ticks;
                } else if(stage==17 &&screen instanceof RulesScreen &&ticks-since>65) {
                    clearFps=client.getFps();AWAssistant.blur=true;
                    float scale=((Number)field(screen,"scale")).floatValue();
                    int x=((Number)field(screen,"panelX")).intValue(),w=((Number)field(screen,"panelW")).intValue();
                    int top=((Number)field(screen,"resultTop")).intValue();
                    screen.mouseScrolled((x+80)*scale,(top+100)*scale,0,-3);
                    screen.mouseScrolled((x+w-80)*scale,(top+170)*scale,0,-3);
                    if(((Number)field(screen,"listTarget")).floatValue()<=0 ||((Number)field(screen,"detailTarget")).floatValue()<=0)
                        throw new IllegalStateException("Separate result/detail scrolling did not move");
                    screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent((x+w-35)*scale,(top+54)*scale,
                        new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);
                    if(!client.keyboardHandler.getClipboard().contains("не правила сервера"))throw new IllegalStateException("Copy button did not copy the selected rule");
                    stage=13;since=ticks;
                } else if(stage==13 &&screen instanceof RulesScreen &&ticks-since>15) {
                    capture(client,"scrolled.png");stage=14;since=ticks;
                } else if(stage==14 &&screen instanceof RulesScreen &&ticks-since>8 &&captured) {
                    client.options.guiScale().set(1);client.resizeGui();stage=4;since=ticks;
                } else if(stage==4 && screen instanceof RulesScreen && ticks-since>20) {
                    capture(client,"scaled.png");
                    float scale=((Number)field(screen,"scale")).floatValue();
                    int w=((Number)field(screen,"panelW")).intValue();
                    if(w*scale>screen.width)throw new IllegalStateException("Panel overflow after GUI scale change");
                    if(((Number)field(screen,"selected")).intValue()!=1)throw new IllegalStateException("Resizing reset the selected result");
                    verifyMenus(screen);
                    verifyDragging(screen);
                    selectBuiltin(screen);
                    stage=18;since=ticks;
                } else if(stage==18 &&screen instanceof RulesScreen &&ticks-since>12) {
                    if(((Boolean)field(screen,"indexing")))return;
                    if(((List<?>)field(screen,"matches")).isEmpty())throw new IllegalStateException("Official numbered rule could not be found");
                    capture(client,"server.png");stage=19;since=ticks;
                } else if(stage==19 &&screen instanceof RulesScreen &&ticks-since>8 &&captured) {
                    client.options.guiScale().set(3);client.resizeGui();
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE,0,0));
                    if(!((List<?>)field(screen,"matches")).isEmpty())throw new IllegalStateException("Escape did not clear the search");
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_F8,0,0));stage=5;since=ticks;
                } else if(stage==5 &&screen instanceof TitleScreen &&ticks-since>15) {
                    verifyNativeFocus(client,false);client.keyboardHandler.setClipboard(originalClipboard);
                    ClientBridge.screen(client,new RulesScreen(screen));
                    AWAssistant.RULES.saveHud(ru.aw.assistant.rules.RuleRepository.defaultHud());
                    ClientBridge.screen(client,AWAssistant.HUD.editor(ClientBridge.screen(client)));stage=49;since=ticks;
                } else if(stage==49&&ticks-since>20) {
                    screen.onClose();stage=50;since=ticks;
                } else if(stage==50 &&screen instanceof RulesScreen &&ticks-since>20) {
                    if((Boolean)field(screen,"indexing")||(Boolean)field(screen,"loading"))return;
                    ((ru.aw.assistant.ui.SearchInput)field(screen,"input")).value("1.1");
                    var update=RulesScreen.class.getDeclaredMethod("updateSearch");update.setAccessible(true);update.invoke(screen);stage=51;since=ticks;
                } else if(stage==51 &&screen instanceof RulesScreen &&ticks-since>10) {
                    float scale=((Number)field(screen,"scale")).floatValue();int x=((Number)field(screen,"panelX")).intValue(),y=((Number)field(screen,"panelY")).intValue(),w=((Number)field(screen,"panelW")).intValue(),top=((Number)field(screen,"resultTop")).intValue();
                    String id=(String)field(screen,"serverId");if(AWAssistant.RULES.favorite(id,"1.1"))AWAssistant.RULES.toggleFavorite(id,"1.1");
                    click(screen,(x+w-90)*scale,(top+10)*scale);
                    if(!AWAssistant.RULES.favorite(id,"1.1"))throw new IllegalStateException("Favorite button did not save the rule");
                    click(screen,(x+w-250)*scale,(y+20)*scale);stage=52;since=ticks;
                } else if(stage==52 &&screen instanceof ru.aw.assistant.ui.RuleLibraryScreen &&ticks-since>10) {
                    if(((List<?>)field(screen,"rows")).isEmpty())throw new IllegalStateException("Favorites library is empty");capture(client,"favorites.png");stage=53;since=ticks;
                } else if(stage==53 &&screen instanceof ru.aw.assistant.ui.RuleLibraryScreen &&ticks-since>10&&captured) {
                    float scale=((Number)field(screen,"scale")).floatValue();int x=((Number)field(screen,"x")).intValue(),y=((Number)field(screen,"y")).intValue();
                    click(screen,(x+170)*scale,(y+65)*scale);if(((List<?>)field(screen,"rows")).isEmpty())throw new IllegalStateException("Search history is empty");
                    click(screen,(x+290)*scale,(y+65)*scale);capture(client,"changes.png");stage=54;since=ticks;
                } else if(stage==54 &&screen instanceof ru.aw.assistant.ui.RuleLibraryScreen &&ticks-since>10&&captured) {
                    screen.onClose();ClientBridge.screen(client,AWAssistant.HUD.editor(ClientBridge.screen(client)));stage=55;since=ticks;
                } else if(stage==55 &&screen.getClass().getName().contains("AssistantHud$Editor")&&ticks-since>15) {
                    int panel=((Number)field(screen,"panel")).intValue();click(screen,panel+50,34+2*22+9);
                    if(!AWAssistant.RULES.hud().get("coordinates").enabled())click(screen,panel+50,157);
                    double old=AWAssistant.RULES.hud().get("coordinates").scale();screen.mouseScrolled(20,20,0,1);
                    if(AWAssistant.RULES.hud().get("coordinates").scale()<=old)throw new IllegalStateException("HUD scale did not change");
                    var bounds=(java.util.Map<?,?>)field(AWAssistant.HUD,"bounds");var box=bounds.get("coordinates");
                    var bx=box.getClass().getDeclaredMethod("x");bx.setAccessible(true);var by=box.getClass().getDeclaredMethod("y");by.setAccessible(true);
                    int hx=(int)bx.invoke(box),hy=(int)by.invoke(box);click(screen,hx+5,hy+5);
                    var button=new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
                    screen.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(180,110,button),170,90);screen.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(180,110,button));
                    if(AWAssistant.RULES.hud().get("coordinates").x()<=.02)throw new IllegalStateException("HUD drag did not change stored position");
                    capture(client,"hud-editor.png");stage=56;since=ticks;
                } else if(stage==56&&ticks-since>10&&captured) {
                    Files.writeString(client.gameDirectory.toPath().resolve("awassistant-smoke.ok"),"Hotkey, Russian input, search, navigation, scrolling, copy, servers, dragging, GUI scaling, favorites, query history, rule changes tab and HUD editor rendering/enable/scale/drag verified; FPS title="+baselineFps+", assistant="+expandedFps+", no blur="+clearFps+"; first-open tick gap ms="+firstOpenGap/1_000_000.0);client.stop();stage=6;
                }
                if(ticks>1600 &&stage!=6)throw new IllegalStateException("Client UI verification timed out at stage "+stage);
            } catch(Exception failure) {
                if(originalClipboard!=null)client.keyboardHandler.setClipboard(originalClipboard);
                try {Files.writeString(client.gameDirectory.toPath().resolve("awassistant-smoke.fail"),failure.toString());}catch(Exception ignored) {}
                client.stop();stage=6;
            }
        });
    }
    private void verifyMenus(net.minecraft.client.gui.screens.Screen screen) throws ReflectiveOperationException {
        float scale=((Number)field(screen,"scale")).floatValue();
        int x=((Number)field(screen,"panelX")).intValue(),w=((Number)field(screen,"panelW")).intValue();
        int y=((Number)field(screen,"panelY")).intValue(),top=((Number)field(screen,"resultTop")).intValue();
        click(screen,(x+w-110)*scale,(y+70)*scale);
        click(screen,(x+w-110)*scale,(y+99+34+15)*scale);
        var books=((ru.aw.assistant.rules.RuleRepository.Snapshot)field(screen,"data")).books();
        if(!field(screen,"serverId").equals(books.get(1).id())||!((List<?>)field(screen,"matches")).isEmpty())throw new IllegalStateException("Server selector did not isolate rules");
        click(screen,(x+w-110)*scale,(y+70)*scale);
        click(screen,(x+w-110)*scale,(y+99+15)*scale);
        if(((List<?>)field(screen,"matches")).size()!=18)throw new IllegalStateException("Returning to the fixture server lost results");
        int left=((Number)field(screen,"leftW")).intValue();
        click(screen,(x+left-80)*scale,(top+10)*scale);
        click(screen,(x+left-80)*scale,(top+36+34+15)*scale);
        if(!field(screen,"category").equals("Проверка"))throw new IllegalStateException("Category selector did not apply the filter");
    }
    private void verifyWrapping(){
        String word="СверхдлинноеСловоБезПробелов".repeat(15)+"😀";
        var lines=ru.aw.assistant.ui.UiRenderer.wrap(word,180,false);
        if(!String.join("",lines).equals(word))throw new IllegalStateException("Wrapping lost Unicode characters");
        for(var line:lines)if(ru.aw.assistant.ui.UiRenderer.width(line)>180)throw new IllegalStateException("Wrapped line exceeds its width");
        var paragraphs=ru.aw.assistant.ui.UiRenderer.wrap("Первый абзац\n\nВторой абзац",180,false);
        if(!paragraphs.contains(""))throw new IllegalStateException("Wrapping lost the blank paragraph");
    }
    private void verifyDragging(net.minecraft.client.gui.screens.Screen screen) throws ReflectiveOperationException {
        float scale=((Number)field(screen,"scale")).floatValue();
        int x=((Number)field(screen,"panelX")).intValue(), y=((Number)field(screen,"panelY")).intValue();
        click(screen,(x+180)*scale,(y+24)*scale);
        var button=new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
        screen.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(-1000,-1000,button),-1000,-1000);
        if(((Number)field(screen,"panelX")).intValue()!=12 || ((Number)field(screen,"panelY")).intValue()!=12)
            throw new IllegalStateException("Dragging did not clamp the window to the viewport");
        screen.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(0,0,button));
        screen.resize(screen.width,screen.height);
        if(((Number)field(screen,"panelX")).intValue()!=12 || ((Number)field(screen,"panelY")).intValue()!=12)
            throw new IllegalStateException("Resizing lost the dragged position");
        if(AWAssistant.RULES.ui().x()!=0 || AWAssistant.RULES.ui().y()!=0)
            throw new IllegalStateException("Dragging did not persist the position");
    }
    private void selectBuiltin(net.minecraft.client.gui.screens.Screen screen) throws ReflectiveOperationException {
        float scale=((Number)field(screen,"scale")).floatValue();
        int x=((Number)field(screen,"panelX")).intValue(),y=((Number)field(screen,"panelY")).intValue(),w=((Number)field(screen,"panelW")).intValue();
        var books=((ru.aw.assistant.rules.RuleRepository.Snapshot)field(screen,"data")).books();
        int index=0;while(index<books.size()&&!books.get(index).id().equals("spookytime"))index++;
        if(index>=books.size())throw new IllegalStateException("SpookyTime builtin is missing");
        click(screen,(x+w-110)*scale,(y+70)*scale);
        click(screen,(x+w-110)*scale,(y+99+34*index+15)*scale);
        focusInput(screen);
        screen.keyPressed(new KeyEvent(InputConstants.KEY_A,0,InputConstants.MOD_CONTROL));
        for(int code:"2.2".codePoints().toArray())screen.charTyped(new CharacterEvent(code));

    }
    private void click(net.minecraft.client.gui.screens.Screen screen,double x,double y) {
        screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);
    }
    private void focusInput(net.minecraft.client.gui.screens.Screen screen) throws ReflectiveOperationException {
        float scale=((Number)field(screen,"scale")).floatValue();
        int x=((Number)field(screen,"panelX")).intValue(),y=((Number)field(screen,"panelY")).intValue();
        click(screen,(x+150)*scale,(y+70)*scale);
    }
    private void typeText(net.minecraft.client.Minecraft client,net.minecraft.client.gui.screens.Screen screen,String value) throws ReflectiveOperationException {
        try {client.keyboardHandler.getClass().getMethod("textInput",long.class,String.class).invoke(client.keyboardHandler,client.getWindow().handle(),value);}
        catch(NoSuchMethodException legacy) {
            var method=client.keyboardHandler.getClass().getDeclaredMethod("charTyped",long.class,CharacterEvent.class);method.setAccessible(true);
            for(int code:value.codePoints().toArray())method.invoke(client.keyboardHandler,client.getWindow().handle(),new CharacterEvent(code));
        }
    }
    private void verifyNativeFocus(net.minecraft.client.Minecraft client,boolean expected) throws ReflectiveOperationException {
        try {
            var keyboard=Class.forName("org.lwjgl.sdl.SDLKeyboard");
            boolean active=(boolean)keyboard.getMethod("SDL_TextInputActive",long.class).invoke(null,client.getWindow().handle());
            if(active!=expected)throw new IllegalStateException("SDL text input focus is incorrect");
        }catch(ClassNotFoundException legacy) {}
    }
    private Object field(Object target,String name) throws ReflectiveOperationException {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private void capture(net.minecraft.client.Minecraft client,String name) {
        captured=false;
        Screenshot.takeScreenshot(ClientBridge.renderTarget(client),image -> {
            try {var folder=client.gameDirectory.toPath().resolve("awassistant-previews");Files.createDirectories(folder);image.writeToFile(folder.resolve(name));client.execute(()->captured=true);}
            catch(Exception failure){throw new RuntimeException(failure);}finally{image.close();}
        });
    }
}
