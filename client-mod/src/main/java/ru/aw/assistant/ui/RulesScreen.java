package ru.aw.assistant.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import ru.aw.assistant.ClientBridge;
import com.mojang.blaze3d.platform.InputConstants;
import ru.aw.assistant.AWAssistant;
import ru.aw.assistant.rules.*;
import java.nio.file.Path;
import java.util.*;

public final class RulesScreen extends Screen {
    private final Screen parent;
    private final SearchInput input = new SearchInput();
    private final AnimatedValue appearance = new AnimatedValue(0), expansion = new AnimatedValue(0), detailFade = new AnimatedValue(1);
    private final AnimatedValue listMotion = new AnimatedValue(0), detailMotion = new AnimatedValue(0), menuMotion = new AnimatedValue(0);
    private RuleRepository.Snapshot data = new RuleRepository.Snapshot(List.of(),List.of());
    private RuleSearch index;
    private List<RuleSearch.Match> matches = List.of();
    private List<FormattedCharSequence> titleLines = List.of(), detailLines = List.of(), sanctionLines = List.of();
    private List<String> categories = List.of();
    private String serverId = "", category;
    private int serverOffset, categoryOffset;
    private int selected, panelX, panelY, panelW=840, bodyH, leftW=300, resultTop, detailTop, detailsWidth;
    private float scale=1, listTarget, detailTarget;
    private boolean closing, loading, serverMenu, categoryMenu, settingsMenu, showSanctions;
    private String message = "", lastMenu = "";
    private long copiedUntil;
    private final Map<String,AnimatedValue> hover = new HashMap<>();

    public RulesScreen(Screen parent) { super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("AWAssistant"));this.parent=parent; }
    @Override protected void init() { layout(); if(index==null)reload(); }
    private void layout() {
        scale=Math.min(1f,Math.min(width/900f,height/500f)); int virtualWidth=Math.round(width/scale),virtualHeight=Math.round(height/scale);
        panelW=Math.min(840,virtualWidth-28);panelX=(virtualWidth-panelW)/2;panelY=24;
        bodyH=Math.max(130,Math.min(590,virtualHeight-panelY-92));leftW=Math.max(220,Math.round(panelW*.36f));
        resultTop=panelY+72;detailTop=resultTop+116;detailsWidth=panelW-leftW-52;
        wrapDetails();
    }
    private RuleBook currentBook() { return data.books().stream().filter(b->b.id().equals(serverId)).findFirst().orElse(null); }
    private void reload() {
        if (loading) return;loading=true;message="";
        AWAssistant.RULES.reload().whenComplete((snapshot,failure)->minecraft.execute(()->{
            if (ClientBridge.screen(minecraft)!=this) return;loading=false;
            if (failure!=null) { message="Не удалось загрузить правила";return; }
            data=snapshot;
            if (currentBook()==null && !data.books().isEmpty()) {
                String address=minecraft.getCurrentServer()==null ? "" : minecraft.getCurrentServer().ip.split(":",2)[0];
                serverId=data.books().stream().filter(b->b.addresses().stream().anyMatch(a->a.equalsIgnoreCase(address)))
                    .findFirst().orElse(data.books().getFirst()).id();
            }
            selectServer(serverId);
            if (!data.errors().isEmpty()) message=data.errors().getFirst();
        }));
    }
    private void selectServer(String id) {
        serverId=id;var book=currentBook();index=book==null ? null : new RuleSearch(book);
        categories=book==null ? List.of() : book.rules().stream().map(RuleBook.Rule::category).filter(s->!s.isBlank()).distinct().sorted().toList();
        category=null;serverMenu=false;updateSearch();
    }
    private void updateSearch() {
        matches=index==null ? List.of() : index.find(input.value(),category);copiedUntil=0;selected=0;listTarget=0;detailTarget=0;listMotion.snap(0);wrapDetails();
    }
    private RuleBook.Rule selectedRule() { return matches.isEmpty() ? null : matches.get(Math.max(0,Math.min(selected,matches.size()-1))).rule(); }
    private void wrapDetails() {
        var rule=selectedRule();detailTarget=0;detailMotion.snap(0);
        titleLines=rule==null ? List.of() : font.split(UiRenderer.label(rule.title()).copy().withStyle(style->style.withBold(true)),Math.max(100,detailsWidth));
        detailLines=rule==null ? List.of() : font.split(UiRenderer.label(rule.description()),Math.max(100,detailsWidth-10));
        sanctionLines=rule==null ||rule.sanction().isBlank() ? List.of() : font.split(UiRenderer.label(rule.sanction()),Math.max(100,detailsWidth-32));
    }
    private void select(int next) { if(matches.isEmpty())return;int value=Math.max(0,Math.min(next,matches.size()-1));if(selected==value)return;selected=value;copiedUntil=0;detailFade.snap(0);wrapDetails(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float tick) { }
    @Override public void onClose() { closing=true;serverMenu=false;categoryMenu=false;settingsMenu=false; }
    @Override public void tick() { if(closing && appearance.value()<.015f) ClientBridge.screen(minecraft,parent); }
    private float highlight(String key,boolean hovered) { return hover.computeIfAbsent(key,k->new AnimatedValue(0)).update(hovered?1:0,AWAssistant.animations); }
    private void button(GuiGraphicsExtractor g,String key,String label,int x,int y,int w,double mx,double my,boolean active,float fade) {
        float h=highlight(key,UiRenderer.hit(mx,my,x,y,w,30));
        UiRenderer.round(g,x,y,w,30,6,UiRenderer.alpha(active?0xFF244737:0xFF2B323A,(.75f+.2f*h)*fade));
        if(key.equals("settings")) { for(int i=0;i<3;i++)g.fill(x+8+i*6,y+14,x+10+i*6,y+16,UiRenderer.alpha(UiRenderer.TEXT,fade)); }
        else UiRenderer.text(g,font,UiRenderer.ellipsis(font,label,w-16),x+8,y+9,active?UiRenderer.ACCENT:UiRenderer.TEXT,fade);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick) {
        if(minecraft.level==null && parent!=null) parent.extractBackground(g,mouseX,mouseY,tick);
        float fade=appearance.update(closing?0:1,AWAssistant.animations);
        float open=expansion.update(input.value().isBlank()?0:1,AWAssistant.animations);
        float detailOpacity=detailFade.update(1,AWAssistant.animations);
        float dy=(1-fade)*-10;
        double mx=mouseX/scale,my=mouseY/scale-dy;
        g.pose().pushMatrix();g.pose().scale(scale,scale);g.pose().translate(0,dy);
        UiRenderer.panel(g,panelX,panelY,panelW,64+Math.round(bodyH*open),fade);
        int searchW=panelW-408;
        UiRenderer.round(g,panelX+12,panelY+13,searchW,38,7,UiRenderer.alpha(0xFF242B33,fade));
        UiRenderer.searchIcon(g,panelX+22,panelY+25,UiRenderer.alpha(UiRenderer.MUTED,fade));
        String shown=input.value().isBlank()?"Найти правило, номер или наказание…":input.value();
        shown=UiRenderer.ellipsis(font,shown,searchW-70);
        if(input.selectedAll()) UiRenderer.round(g,panelX+42,panelY+19,Math.min(searchW-72,font.width(UiRenderer.label(shown))),25,3,UiRenderer.alpha(0x8858CC91,fade));
        UiRenderer.text(g,font,shown,panelX+42,panelY+25,input.value().isBlank()?UiRenderer.MUTED:UiRenderer.TEXT,fade);
        if(!input.value().isBlank() && (System.nanoTime()/500_000_000L)%2==0) {
            int cx=panelX+42+Math.min(searchW-74,font.width(UiRenderer.label(input.value().substring(0,input.cursor()))));g.fill(cx,panelY+22,cx+1,panelY+40,UiRenderer.alpha(UiRenderer.ACCENT,fade));
        }
        UiRenderer.cross(g,panelX+searchW-8,panelY+27,UiRenderer.alpha(UiRenderer.MUTED,fade));
        button(g,"rules","Правила",panelX+panelW-376,panelY+18,92,mx,my,!showSanctions,fade);
        button(g,"sanctions","Санкции",panelX+panelW-276,panelY+18,92,mx,my,showSanctions,fade);
        var book=currentBook();
        button(g,"server",book==null?"Сервер":book.name(),panelX+panelW-176,panelY+18,118,mx,my,serverMenu,fade);
        button(g,"settings","",panelX+panelW-50,panelY+18,34,mx,my,settingsMenu,fade);
        if(open>.005f) {
            g.enableScissor(panelX+1,panelY+64,panelX+panelW-1,panelY+64+Math.round(bodyH*open)-1);
            g.fill(panelX+leftW,panelY+64,panelX+leftW+1,panelY+64+bodyH-34,UiRenderer.alpha(0xFF343D47,fade));
            UiRenderer.text(g,font,loading?"Загружаю правила…":"Найдено: "+matches.size(),panelX+18,resultTop,UiRenderer.MUTED,fade);
            button(g,"category",category==null?"Все категории":category,panelX+leftW-146,resultTop-5,132,mx,my,categoryMenu,fade);
            int listY=resultTop+40, listHeight=bodyH-86;
            float listOffset=listMotion.update(listTarget,AWAssistant.animations);
            g.enableScissor(panelX+8,listY,panelX+leftW-8,listY+listHeight);
            for(int i=Math.max(0,(int)(listOffset/78));i<matches.size();i++) {
                int y=listY+i*78-Math.round(listOffset);if(y>listY+listHeight)break;
                var rule=matches.get(i).rule();boolean hot=UiRenderer.hit(mx,my,panelX+12,y,leftW-24,70);
                float h=highlight("result:"+i,hot);
                UiRenderer.round(g,panelX+12,y,leftW-24,70,7,UiRenderer.alpha(i==selected?0xD92C4139:0xFF262D35,fade*(i==selected?1:.5f+.4f*h)));
                UiRenderer.text(g,font,rule.number(),panelX+24,y+11,UiRenderer.ACCENT,fade);
                String title=UiRenderer.ellipsis(font,rule.title(),leftW-50);
                UiRenderer.text(g,font,title,panelX+24,y+30,UiRenderer.TEXT,fade);
                UiRenderer.text(g,font,UiRenderer.ellipsis(font,rule.description().replace('\n',' '),leftW-50),panelX+24,y+51,UiRenderer.MUTED,fade);
            }
            UiRenderer.scrollBar(g,panelX+leftW-11,listY,listHeight,matches.size()*78,listOffset,fade);
            g.disableScissor();
            int dx=panelX+leftW+22;
            var rule=selectedRule();
            if(rule==null) {
                UiRenderer.text(g,font,book!=null &&book.rules().isEmpty()?"Для этого сервера правила ещё не загружены":"Ничего не найдено",dx,resultTop+60,UiRenderer.TEXT,fade);
                UiRenderer.text(g,font,"Перетащи JSON в окно или добавь файл в папку правил",dx,resultTop+84,UiRenderer.MUTED,fade);
                button(g,"folder","Папка правил",dx,resultTop+124,140,mx,my,false,fade);
                button(g,"reload","Обновить",dx+150,resultTop+124,100,mx,my,false,fade);
            } else {
                UiRenderer.text(g,font,rule.number()+" · "+(book==null?"":book.name()),dx,resultTop+4,UiRenderer.ACCENT,fade*detailOpacity);
                int titleY=resultTop+34;for(var line:titleLines){g.text(font,line,dx,titleY,UiRenderer.alpha(UiRenderer.TEXT,fade*detailOpacity),false);titleY+=18;}
                UiRenderer.text(g,font,UiRenderer.ellipsis(font,rule.category(),detailsWidth-140),dx,titleY+8,UiRenderer.MUTED,fade*detailOpacity);
                detailTop=Math.max(resultTop+116,titleY+64);
                button(g,"copy",System.currentTimeMillis()<copiedUntil?"Скопировано":"Копировать",panelX+panelW-132,copyTop(),110,mx,my,false,fade);
                int detailHeight=Math.max(40,panelY+64+bodyH-42-detailTop);
                float scroll=detailMotion.update(detailTarget,AWAssistant.animations);
                g.enableScissor(dx,detailTop,panelX+panelW-18,detailTop+detailHeight);
                int y=detailTop-Math.round(scroll);
                if(!sanctionLines.isEmpty()) {
                    int ph=30+sanctionLines.size()*16;
                    UiRenderer.round(g,dx,y,detailsWidth,ph,7,UiRenderer.alpha(0x773F242C,fade*detailOpacity));
                    UiRenderer.text(g,font,"Наказание",dx+12,y+10,0xFFE98491,fade*detailOpacity);
                    int sy=y+29;for(var line:sanctionLines) {g.text(font,line,dx+12,sy,UiRenderer.alpha(UiRenderer.TEXT,fade*detailOpacity),false);sy+=16;}
                    y+=ph+20;
                }
                if(!showSanctions) for(var line:detailLines) {g.text(font,line,dx,y,UiRenderer.alpha(UiRenderer.TEXT,fade*detailOpacity),false);y+=18;}
                else if(sanctionLines.isEmpty()) UiRenderer.text(g,font,"Наказание в этом правиле не указано",dx,y,UiRenderer.MUTED,fade);
                UiRenderer.scrollBar(g,panelX+panelW-20,detailTop,detailHeight,modelHeight(),scroll,fade);
                g.disableScissor();
            }
            UiRenderer.text(g,font,message.isBlank()?"AWAssistant · "+AWAssistant.OPEN.getTranslatedKeyMessage().getString()+" закрыть · Перетащи JSON с правилами":UiRenderer.ellipsis(font,message,panelW-34),panelX+16,panelY+64+bodyH-22,UiRenderer.MUTED,fade);
            g.disableScissor();
        }
        if(serverMenu)lastMenu="server";else if(categoryMenu)lastMenu="category";else if(settingsMenu)lastMenu="settings";
        float menu=menuMotion.update(serverMenu||categoryMenu||settingsMenu?1:0,AWAssistant.animations);
        if(menu>.01f &&lastMenu.equals("server")) {
            int x=panelX+panelW-210,y=panelY+58;UiRenderer.panel(g,x,y,190,Math.min(8,data.books().size())*36+12,fade*menu);
            for(int i=0;i<Math.min(8,data.books().size());i++) button(g,"server:"+i,data.books().get(i+serverOffset).name(),x+6,y+6+i*36,178,mx,my,data.books().get(i+serverOffset).id().equals(serverId),fade*menu);
        }
        if(menu>.01f &&lastMenu.equals("category")) {
            int x=panelX+leftW-210,y=resultTop+32;UiRenderer.panel(g,x,y,194,Math.min(8,categories.size()+1)*36+12,fade*menu);
            button(g,"cat:all","Все категории",x+6,y+6,182,mx,my,category==null,fade*menu);
            for(int i=0;i<Math.min(7,categories.size());i++) button(g,"cat:"+i,categories.get(i+categoryOffset),x+6,y+42+i*36,182,mx,my,categories.get(i+categoryOffset).equals(category),fade*menu);
        }
        if(menu>.01f &&lastMenu.equals("settings")) {
            int x=panelX+panelW-234,y=panelY+58;UiRenderer.panel(g,x,y,222,120,fade*menu);
            button(g,"animations","Анимации: "+(AWAssistant.animations?"вкл":"выкл"),x+6,y+6,210,mx,my,AWAssistant.animations,fade*menu);
            button(g,"folder-menu","Папка правил",x+6,y+42,210,mx,my,false,fade*menu);
            button(g,"reload-menu","Обновить правила",x+6,y+78,210,mx,my,false,fade*menu);
        }
        g.pose().popMatrix();
    }
    private int copyTop() { return resultTop+36+titleLines.size()*18; }
    private int modelHeight() { return (showSanctions?0:detailLines.size()*18) + (sanctionLines.isEmpty()?0:50+sanctionLines.size()*16); }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        x/=scale;y/=scale;
        if(serverMenu){serverOffset=Math.max(0,Math.min(Math.max(0,data.books().size()-8),serverOffset-(int)Math.signum(vertical)));return true;}
        if(categoryMenu){categoryOffset=Math.max(0,Math.min(Math.max(0,categories.size()-7),categoryOffset-(int)Math.signum(vertical)));return true;}
        if(x<panelX+leftW) listTarget=Math.max(0,Math.min(Math.max(0,matches.size()*78-(bodyH-86)),listTarget-(float)vertical*44));
        else detailTarget=Math.max(0,Math.min(Math.max(0,modelHeight()-Math.max(40,panelY+64+bodyH-42-detailTop)),detailTarget-(float)vertical*44));
        return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        double x=event.x()/scale,y=event.y()/scale;
        if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return true;
        if(serverMenu) {
            int sx=panelX+panelW-204,sy=panelY+64;
            for(int i=0;i<Math.min(8,data.books().size());i++) if(UiRenderer.hit(x,y,sx,sy+i*36,178,30)){selectServer(data.books().get(i+serverOffset).id());return true;}
            serverMenu=false;
        }
        if(categoryMenu) {
            int cx=panelX+leftW-204,cy=resultTop+38;
            if(UiRenderer.hit(x,y,cx,cy,182,30)) {category=null;categoryMenu=false;updateSearch();return true;}
            for(int i=0;i<Math.min(7,categories.size());i++) if(UiRenderer.hit(x,y,cx,cy+36+i*36,182,30)){category=categories.get(i+categoryOffset);categoryMenu=false;updateSearch();return true;}
            categoryMenu=false;
        }
        if(settingsMenu) {
            int sx=panelX+panelW-228,sy=panelY+64;
            if(UiRenderer.hit(x,y,sx,sy,210,30)){AWAssistant.animations=!AWAssistant.animations;return true;}
            if(UiRenderer.hit(x,y,sx,sy+36,210,30)){openFolder();return true;}
            if(UiRenderer.hit(x,y,sx,sy+72,210,30)){reload();return true;}
            settingsMenu=false;
        }
        if(UiRenderer.hit(x,y,panelX+panelW-176,panelY+18,118,30)){serverOffset=0;serverMenu=true;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-50,panelY+18,34,30)){settingsMenu=true;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-376,panelY+18,92,30)){showSanctions=false;detailTarget=0;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-276,panelY+18,92,30)){showSanctions=true;detailTarget=0;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-428,panelY+13,32,38)){input.value("");updateSearch();return true;}
        if(input.value().isBlank())return true;
        if(UiRenderer.hit(x,y,panelX+leftW-146,resultTop-5,132,30)){categoryOffset=0;categoryMenu=true;return true;}
        int listY=resultTop+40;
        if(UiRenderer.hit(x,y,panelX+12,listY,leftW-24,bodyH-86)){select((int)((y-listY+listMotion.value())/78));return true;}
        int dx=panelX+leftW+22;
        if(selectedRule()==null) {
            if(UiRenderer.hit(x,y,dx,resultTop+124,140,30))openFolder();
            else if(UiRenderer.hit(x,y,dx+150,resultTop+124,100,30))reload();
        } else if(UiRenderer.hit(x,y,panelX+panelW-132,copyTop(),110,30)) {
            var rule=selectedRule();minecraft.keyboardHandler.setClipboard(rule.number()+" "+rule.title()+"\n"+rule.description()+
                (rule.sanction().isBlank()?"":"\nНаказание: "+rule.sanction()));copiedUntil=System.currentTimeMillis()+1800;
        }
        return true;
    }
    private void openFolder() { java.util.concurrent.CompletableFuture.runAsync(()-> { try {
        if(java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(AWAssistant.RULES.folder().toFile());
    } catch(java.io.IOException failure){ minecraft.execute(()->message="Не удалось открыть папку правил"); } }); }
    @Override public boolean charTyped(CharacterEvent event) {
        if(event.isAllowedChatCharacter()) {input.insert(event.codepointAsString());updateSearch();}return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if(event.key()==InputConstants.KEY_ESCAPE) {
            if(serverMenu||categoryMenu||settingsMenu){serverMenu=false;categoryMenu=false;settingsMenu=false;}
            else if(!input.value().isBlank()){input.value("");updateSearch();}else onClose();return true;
        }
        if(AWAssistant.OPEN.matches(event)){onClose();return true;}
        if(event.key()==InputConstants.KEY_DOWN||event.key()==InputConstants.KEY_UP) {
            select(selected+(event.key()==InputConstants.KEY_DOWN?1:-1));
            int visible=bodyH-86;float top=selected*78,bottom=top+70;
            if(top<listTarget)listTarget=top;else if(bottom>listTarget+visible)listTarget=bottom-visible;return true;
        }
        if(event.key()==InputConstants.KEY_RETURN){detailTarget=0;return true;}
        String before=input.value();if(input.key(event,minecraft.keyboardHandler.getClipboard(),minecraft.keyboardHandler::setClipboard)) {
            if(!before.equals(input.value()))updateSearch();return true;
        }
        return super.keyPressed(event);
    }
    @Override public void onFilesDrop(List<Path> paths) {
        message="Импортирую правила…";
        AWAssistant.RULES.importFiles(paths).whenComplete((result,failure)->minecraft.execute(()->{
            if(ClientBridge.screen(minecraft)!=this)return;
            if(failure!=null)message="Не удалось импортировать JSON: "+(failure.getCause()==null?failure:failure.getCause()).getMessage();else reload();
        }));
    }
}
