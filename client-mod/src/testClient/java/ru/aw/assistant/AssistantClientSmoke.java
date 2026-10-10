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
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                ticks++;
                var screen=ClientBridge.screen(client);
                if(stage==0 && screen instanceof TitleScreen && ClientBridge.ready(client)) {
                    if(!AWAssistant.OPEN.getTranslatedKeyMessage().getString().equals("F8"))throw new IllegalStateException("The registered binding is not the native F8 key");
                    baselineFps=client.getFps();originalClipboard=client.keyboardHandler.getClipboard();
                    net.minecraft.client.KeyMapping.click(ClientBridge.keyboard().getOrCreate(InputConstants.KEY_F8));stage=1;since=ticks;
                } else if(stage==1 &&screen instanceof RulesScreen &&ticks-since>40) {
                    capture(client,"compact.png");
                    stage=11;since=ticks;
                } else if(stage==11 &&screen instanceof RulesScreen &&ticks-since>8 &&captured) {
                    for(int code:"ТЕСТ".codePoints().toArray())screen.charTyped(new CharacterEvent(code));stage=2;since=ticks;
                } else if(stage==2 && screen instanceof RulesScreen && ticks-since>35) {
                    var results=RulesScreen.class.getDeclaredField("matches");results.setAccessible(true);
                    if(((List<?>)results.get(screen)).size()!=18)throw new IllegalStateException("Russian input/search did not find all fixture rules");
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_A,0,InputConstants.MOD_CONTROL));
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_C,0,InputConstants.MOD_CONTROL));
                    if(!client.keyboardHandler.getClipboard().equals("ТЕСТ"))throw new IllegalStateException("Native Ctrl+A/C did not copy the query");
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
                    screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent((x+w-35)*scale,(top+12)*scale,
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
                    client.options.guiScale().set(3);client.resizeGui();
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE,0,0));
                    if(!((List<?>)field(screen,"matches")).isEmpty())throw new IllegalStateException("Escape did not clear the search");
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_F8,0,0));stage=5;since=ticks;
                } else if(stage==5 &&screen instanceof TitleScreen &&ticks-since>15) {
                    client.keyboardHandler.setClipboard(originalClipboard);
                    Files.writeString(client.gameDirectory.toPath().resolve("awassistant-smoke.ok"),"Hotkey, Russian input, search, arrow/Enter/Escape, separate scrolling, copy, servers, categories, GUI scaling and native rendering verified; FPS title="+baselineFps+", assistant="+expandedFps+", no blur="+clearFps);client.stop();stage=6;
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
        click(screen,(x+w-110)*scale,(y+30)*scale);
        click(screen,(x+w-110)*scale,(y+64+36+15)*scale);
        if(!field(screen,"serverId").equals("aresmine")||!((List<?>)field(screen,"matches")).isEmpty())throw new IllegalStateException("Server selector did not isolate rules");
        click(screen,(x+w-110)*scale,(y+30)*scale);
        click(screen,(x+w-110)*scale,(y+64+15)*scale);
        if(((List<?>)field(screen,"matches")).size()!=18)throw new IllegalStateException("Returning to the fixture server lost results");
        int left=((Number)field(screen,"leftW")).intValue();
        click(screen,(x+left-80)*scale,(top+10)*scale);
        click(screen,(x+left-80)*scale,(top+38+36+15)*scale);
        if(!field(screen,"category").equals("Проверка"))throw new IllegalStateException("Category selector did not apply the filter");
    }
    private void click(net.minecraft.client.gui.screens.Screen screen,double x,double y) {
        screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);
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
