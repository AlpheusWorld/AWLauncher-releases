package ru.aw.assistant.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import ru.aw.assistant.AWAssistant;
import ru.aw.assistant.ClientBridge;
import ru.aw.assistant.rules.*;
import java.nio.file.Path;
import java.util.*;

public final class RulesScreen extends Screen {
    private static final int HEADER=100,ROW=74,FOOTER=32;
    private final Screen parent;
    private final SearchInput input=new SearchInput();
    private final AnimatedValue appearance=new AnimatedValue(0),expansion=new AnimatedValue(0),detailFade=new AnimatedValue(1);
    private final AnimatedValue listMotion=new AnimatedValue(0),detailMotion=new AnimatedValue(0),menuMotion=new AnimatedValue(0);
    private final Map<String,AnimatedValue> hover=new HashMap<>();
    private RuleRepository.Snapshot data=new RuleRepository.Snapshot(List.of(),List.of());
    private RuleSearch index;
    private List<RuleSearch.Match> matches=List.of();
    private List<List<String>> resultTitles=List.of();
    private List<String> resultDescriptions=List.of(),titleLines=List.of(),detailLines=List.of(),sanctionLines=List.of(),categories=List.of();
    private String serverId="",category,message="",lastMenu="";
    private int selected,serverOffset,categoryOffset,panelX,panelY,panelW=740,leftW=250,bodyH,resultTop,detailTop,detailsWidth,virtualWidth,virtualHeight;
    private float scale=1,listTarget,detailTarget,dragX,dragY;
    private double positionX=.5,positionY=.05;
    private boolean closing,loading,refreshing,serverMenu,categoryMenu,settingsMenu,showSanctions,browsing,dragging,positionTouched;
    private long copiedUntil;

    public RulesScreen(Screen parent){super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("Правила серверов"));this.parent=parent;}
    @Override protected void init(){UiRenderer.prepare();layout();if(index==null)reload();}
    private void layout(){
        float pixels=Math.min(1f,Math.min((minecraft.getWindow().getWidth()-32)/740f,(minecraft.getWindow().getHeight()-32)/300f));
        scale=pixels/minecraft.getWindow().getGuiScale();virtualWidth=Math.round(width/scale);virtualHeight=Math.round(height/scale);
        bodyH=Math.max(170,Math.min(460,virtualHeight-HEADER-52));detailsWidth=panelW-leftW-44;
        panelX=12+(int)Math.round(Math.max(0,virtualWidth-panelW-24)*positionX);
        panelY=12+(int)Math.round(Math.max(0,virtualHeight-HEADER-bodyH-24)*positionY);
        anchors();wrapDetails();
    }
    private void anchors(){resultTop=panelY+HEADER+12;}
    private RuleBook currentBook(){return data.books().stream().filter(book->book.id().equals(serverId)).findFirst().orElse(null);}
    private RuleBook.Rule selectedRule(){return matches.isEmpty()?null:matches.get(Math.min(selected,matches.size()-1)).rule();}
    private void reload(){
        if(loading)return;loading=true;message="";
        AWAssistant.RULES.reload().whenComplete((snapshot,failure)->minecraft.execute(()->{
            if(ClientBridge.screen(minecraft)!=this)return;loading=false;
            if(failure!=null){message="Не удалось загрузить правила";return;}
            data=snapshot;
            if(!positionTouched){var ui=AWAssistant.RULES.ui();positionX=ui.x();positionY=ui.y();AWAssistant.animations=ui.animations();AWAssistant.blur=ui.blur();layout();}
            if(currentBook()==null&&!data.books().isEmpty()){
                String address=minecraft.getCurrentServer()==null?"":minecraft.getCurrentServer().ip.toLowerCase(Locale.ROOT);
                var fallback=data.books().stream().filter(book->!book.rules().isEmpty()).findFirst().orElse(data.books().getFirst());
                serverId=data.books().stream().filter(book->book.addresses().stream().anyMatch(a->address.equals(a)||address.startsWith(a+":"))).findFirst().orElse(fallback).id();
            }
            selectServer(serverId);if(!data.errors().isEmpty())message=data.errors().getFirst();
            if(AWAssistant.RULES.refreshNeeded())refreshSources();
        }));
    }
    private void refreshSources(){
        if(refreshing)return;refreshing=true;message="Обновляю правила с сайтов серверов…";
        AWAssistant.RULES.refresh().whenComplete((snapshot,failure)->minecraft.execute(()->{
            if(ClientBridge.screen(minecraft)!=this)return;refreshing=false;
            if(failure!=null){message="Не удалось обновить правила; сохранены прежние";return;}
            String selectedNumber=selectedRule()==null?"":selectedRule().number(),selectedCategory=category;
            data=snapshot;selectServer(serverId);
            if(categories.contains(selectedCategory)){category=selectedCategory;updateSearch();}
            for(int i=0;i<matches.size();i++)if(matches.get(i).rule().number().equals(selectedNumber)){select(i);break;}
            message=data.errors().isEmpty()?"Правила обновлены":data.errors().getFirst();
        }));
    }
    private void selectServer(String id){
        serverId=id;var book=currentBook();index=book==null?null:new RuleSearch(book);
        categories=book==null?List.of():book.rules().stream().map(RuleBook.Rule::category).filter(value->!value.isBlank()).distinct().toList();
        category=null;serverMenu=false;updateSearch();
    }
    private void updateSearch(){
        var book=currentBook();
        matches=browsing&&input.value().isBlank()&&book!=null?book.rules().stream().filter(rule->category==null||category.equals(rule.category())).limit(500).map(rule->new RuleSearch.Match(rule,0)).toList():index==null?List.of():index.find(input.value(),category);
        selected=0;copiedUntil=0;listTarget=0;listMotion.snap(0);
        resultTitles=matches.stream().map(match->UiRenderer.wrap(match.rule().title(),leftW-48,false).stream().limit(2).toList()).toList();
        resultDescriptions=matches.stream().map(match->UiRenderer.ellipsis(font,match.rule().description().replace('\n',' '),leftW-48)).toList();wrapDetails();
    }
    private void wrapDetails(){
        var rule=selectedRule();detailTarget=0;detailMotion.snap(0);
        titleLines=rule==null?List.of():UiRenderer.wrap(rule.title(),detailsWidth,true);
        detailLines=rule==null?List.of():UiRenderer.wrap(rule.description(),detailsWidth,false);
        sanctionLines=rule==null||rule.sanction().isBlank()?List.of():UiRenderer.wrap(rule.sanction(),detailsWidth-28,false);
    }
    private void select(int value){if(matches.isEmpty())return;int next=Math.max(0,Math.min(value,matches.size()-1));if(next==selected)return;selected=next;copiedUntil=0;detailFade.snap(0);wrapDetails();}
    private float highlight(String key,boolean active){return hover.computeIfAbsent(key,ignored->new AnimatedValue(0)).update(active?1:0,AWAssistant.animations);}
    private void saveUi(){
        positionX=(panelX-12)/(double)Math.max(1,virtualWidth-panelW-24);positionY=(panelY-12)/(double)Math.max(1,virtualHeight-HEADER-bodyH-24);
        AWAssistant.RULES.saveUi(new RuleRepository.UiPreferences(positionX,positionY,AWAssistant.animations,AWAssistant.blur)).whenComplete((unused,failure)->{
            if(failure!=null)minecraft.execute(()->message="Не удалось сохранить положение панели");
        });
    }
    private int listTop(){return resultTop+36;}
    private int listHeight(){return bodyH-80;}
    private int detailHeight(){return Math.max(40,panelY+HEADER+bodyH-FOOTER-8-detailTop);}
    private int copyTop(){return resultTop+42;}
    private int modelHeight(){return (showSanctions?0:detailLines.size()*21)+(sanctionLines.isEmpty()?0:54+sanctionLines.size()*20);}
    private boolean expanded(){return browsing||!input.value().isBlank();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractBackground(GuiGraphicsExtractor graphics,int x,int y,float tick){}
    @Override public void onClose(){if(dragging)saveUi();closing=true;dragging=false;serverMenu=categoryMenu=settingsMenu=false;}
    @Override public void tick(){if(closing&&appearance.value()<.015f)ClientBridge.screen(minecraft,parent);}

    private void textButton(GuiGraphicsExtractor g,String key,String text,int x,int y,int w,double mx,double my,boolean active,float fade){
        float h=highlight(key,UiRenderer.hit(mx,my,x,y,w,28));
        if(h>.01f||active)UiRenderer.round(g,x,y,w,28,4,UiRenderer.alpha(active?0x44394149:0x223C4148,fade*Math.max(h,active?1:0)));
        UiRenderer.small(g,UiRenderer.ellipsis(font,text,Math.round((w-16)/.85f)),x+8,y+6,active?UiRenderer.ACCENT:UiRenderer.TEXT,fade);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
        if(minecraft.level==null)extractPanorama(g,tick);if(AWAssistant.blur)extractBlurredBackground(g);
        float fade=appearance.update(closing?0:1,AWAssistant.animations),open=expansion.update(expanded()?1:0,AWAssistant.animations),details=detailFade.update(1,AWAssistant.animations);
        float slide=(1-fade)*-8;double mx=mouseX/scale,my=mouseY/scale-slide;
        g.pose().pushMatrix();g.pose().scale(scale,scale);g.pose().translate(0,slide);
        UiRenderer.panel(g,panelX,panelY,panelW,HEADER+Math.round(bodyH*open),fade);
        UiRenderer.brand(g,panelX+15,panelY+11,fade);
        UiRenderer.bold(g,"Правила серверов",panelX+49,panelY+12,UiRenderer.TEXT,fade);
        for(int i=0;i<3;i++){g.fill(panelX+panelW/2-8+i*6,panelY+9,panelX+panelW/2-6+i*6,panelY+11,UiRenderer.alpha(UiRenderer.MUTED,fade*.5f));}
        textButton(g,"browse",expanded()?"Свернуть":"Все правила",panelX+panelW-212,panelY+8,108,mx,my,false,fade);
        textButton(g,"settings","•••",panelX+panelW-88,panelY+8,30,mx,my,settingsMenu,fade);
        float closeHover=highlight("close",UiRenderer.hit(mx,my,panelX+panelW-43,panelY+8,28,28));
        UiRenderer.cross(g,panelX+panelW-34,panelY+17,UiRenderer.alpha(closeHover>.2?UiRenderer.TEXT:UiRenderer.MUTED,fade));
        g.fill(panelX+1,panelY+43,panelX+panelW-1,panelY+44,UiRenderer.alpha(0xFF363B42,fade*.8f));
        int searchX=panelX+16,searchY=panelY+54,searchW=panelW-202;
        UiRenderer.round(g,searchX,searchY,searchW,34,4,UiRenderer.alpha(0xFF25292F,fade));
        UiRenderer.searchIcon(g,searchX+11,searchY+10,UiRenderer.alpha(UiRenderer.MUTED,fade));
        String query=input.value().isBlank()?"Найти правило или наказание…":input.value();query=UiRenderer.ellipsis(font,query,searchW-68);
        if(input.selectedAll())UiRenderer.round(g,searchX+33,searchY+5,Math.min(searchW-66,UiRenderer.width(query)),25,2,UiRenderer.alpha(0x554DBD87,fade));
        UiRenderer.text(g,font,query,searchX+33,searchY+8,input.value().isBlank()?UiRenderer.MUTED:UiRenderer.TEXT,fade);
        if(!input.value().isBlank()&&(System.nanoTime()/500_000_000L)%2==0){int caret=searchX+33+Math.min(searchW-68,UiRenderer.width(input.value().substring(0,input.cursor())));g.fill(caret,searchY+7,caret+1,searchY+26,UiRenderer.alpha(UiRenderer.TEXT,fade));}
        if(!input.value().isBlank())UiRenderer.cross(g,searchX+searchW-22,searchY+12,UiRenderer.alpha(UiRenderer.MUTED,fade));
        var book=currentBook();textButton(g,"server",book==null?"Выбрать сервер":book.name(),panelX+panelW-174,searchY+3,158,mx,my,serverMenu,fade);
        UiRenderer.chevron(g,panelX+panelW-30,searchY+15,UiRenderer.alpha(UiRenderer.MUTED,fade));
        if(open>.005f){
            g.enableScissor(panelX+1,panelY+HEADER,panelX+panelW-1,panelY+HEADER+Math.round(bodyH*open)-1);
            g.fill(panelX+leftW,panelY+HEADER,panelX+leftW+1,panelY+HEADER+bodyH-FOOTER,UiRenderer.alpha(0xFF363B42,fade*.8f));
            UiRenderer.small(g,loading?"Загрузка…":"Правила · "+matches.size(),panelX+16,resultTop+4,UiRenderer.MUTED,fade);
            textButton(g,"category",category==null?"Все разделы":category,panelX+leftW-124,resultTop-3,108,mx,my,categoryMenu,fade);
            float offset=listMotion.update(listTarget,AWAssistant.animations);
            g.enableScissor(panelX+8,listTop(),panelX+leftW-8,listTop()+listHeight());
            for(int i=Math.max(0,(int)(offset/ROW));i<matches.size();i++){
                int y=listTop()+i*ROW-Math.round(offset);if(y>listTop()+listHeight())break;
                var rule=matches.get(i).rule();float h=highlight("result:"+i,UiRenderer.hit(mx,my,panelX+8,y,leftW-16,ROW-2));
                if(i==selected||h>.01f)UiRenderer.round(g,panelX+8,y,leftW-16,ROW-3,4,UiRenderer.alpha(i==selected?0xFF252B2D:0xFF24272E,fade*(i==selected?1:h)));
                if(i==selected)g.fill(panelX+8,y+8,panelX+10,y+ROW-11,UiRenderer.alpha(UiRenderer.ACCENT,fade));
                UiRenderer.small(g,rule.number(),panelX+22,y+5,i==selected?UiRenderer.ACCENT:UiRenderer.MUTED,fade);
                int lineY=y+23;for(String line:resultTitles.get(i)){UiRenderer.text(g,font,line,panelX+22,lineY,UiRenderer.TEXT,fade);lineY+=18;}
                if(resultTitles.get(i).size()==1)UiRenderer.small(g,resultDescriptions.get(i),panelX+22,y+48,UiRenderer.MUTED,fade);
            }
            UiRenderer.scrollBar(g,panelX+leftW-11,listTop(),listHeight(),matches.size()*ROW,offset,fade);g.disableScissor();
            int dx=panelX+leftW+22;
            textButton(g,"rules","Правило",dx,resultTop-3,76,mx,my,!showSanctions,fade);
            textButton(g,"sanctions","Наказание",dx+84,resultTop-3,100,mx,my,showSanctions,fade);
            g.fill(dx+(showSanctions?92:8),resultTop+27,dx+(showSanctions?174:66),resultTop+29,UiRenderer.alpha(UiRenderer.ACCENT,fade));
            var rule=selectedRule();
            if(rule==null){
                UiRenderer.bold(g,book!=null&&book.rules().isEmpty()?"Правила не загружены":"Ничего не найдено",dx,resultTop+62,UiRenderer.TEXT,fade);
                int y=resultTop+97;for(String line:UiRenderer.wrap("Добавь набор правил или уточни запрос",detailsWidth,false)){UiRenderer.text(g,font,line,dx,y,UiRenderer.MUTED,fade);y+=21;}
                textButton(g,"folder","Папка правил",dx,resultTop+147,132,mx,my,false,fade);textButton(g,"reload","Обновить",dx+142,resultTop+147,98,mx,my,false,fade);
            }else{
                UiRenderer.small(g,rule.number()+"  /  "+(book==null?"":book.name()),dx,resultTop+44,UiRenderer.ACCENT,fade*details);
                float copying=highlight("copy",UiRenderer.hit(mx,my,panelX+panelW-49,copyTop(),28,28));
                UiRenderer.copyIcon(g,panelX+panelW-41,copyTop()+6,UiRenderer.alpha(copying>.2?UiRenderer.TEXT:UiRenderer.MUTED,fade));
                if(System.currentTimeMillis()<copiedUntil)UiRenderer.small(g,"Скопировано",panelX+panelW-141,copyTop()+6,UiRenderer.ACCENT,fade);
                int y=resultTop+70;for(String line:titleLines){UiRenderer.bold(g,line,dx,y,UiRenderer.TEXT,fade*details);y+=23;}
                if(!rule.category().isBlank()){UiRenderer.small(g,rule.category(),dx,y+5,UiRenderer.MUTED,fade*details);y+=29;}
                detailTop=y+16;float scroll=detailMotion.update(detailTarget,AWAssistant.animations);
                g.enableScissor(dx,detailTop,panelX+panelW-16,detailTop+detailHeight());y=detailTop-Math.round(scroll);
                if(!sanctionLines.isEmpty()){
                    int boxHeight=36+sanctionLines.size()*20;
                    UiRenderer.round(g,dx,y,detailsWidth,boxHeight,4,UiRenderer.alpha(0xFF30242A,fade*details));
                    g.fill(dx,y+5,dx+2,y+boxHeight-5,UiRenderer.alpha(0xFFC77E8B,fade*details));
                    UiRenderer.small(g,"Наказание",dx+14,y+8,0xFFE49BA7,fade*details);
                    int sy=y+29;for(String line:sanctionLines){if(sy>=detailTop-20&&sy<detailTop+detailHeight())UiRenderer.text(g,font,line,dx+14,sy,UiRenderer.TEXT,fade*details);sy+=20;}y+=boxHeight+18;
                }
                if(!showSanctions)for(String line:detailLines){if(y>=detailTop-21&&y<detailTop+detailHeight())UiRenderer.text(g,font,line,dx,y,UiRenderer.TEXT,fade*details);y+=21;}
                else if(sanctionLines.isEmpty())UiRenderer.text(g,font,"Наказание не указано в источнике",dx,y,UiRenderer.MUTED,fade);
                UiRenderer.scrollBar(g,panelX+panelW-19,detailTop,detailHeight(),modelHeight(),scroll,fade);g.disableScissor();
            }
            int footer=panelY+HEADER+bodyH-FOOTER;
            g.fill(panelX+1,footer,panelX+panelW-1,footer+1,UiRenderer.alpha(0xFF363B42,fade*.8f));
            String status=message.isBlank()?(book!=null&&book.name().endsWith(" · памятка")?"Краткая памятка · полные условия в источнике":AWAssistant.OPEN.getTranslatedKeyMessage().getString()+" · закрыть"):message;
            UiRenderer.small(g,UiRenderer.ellipsis(font,status,panelW-160),panelX+16,footer+9,UiRenderer.MUTED,fade);
            if(book!=null&&!book.sourceUrl().isBlank())textButton(g,"source","Источник ↗",panelX+panelW-114,footer+2,98,mx,my,false,fade);
            g.disableScissor();
        }
        if(serverMenu)lastMenu="server";else if(categoryMenu)lastMenu="category";else if(settingsMenu)lastMenu="settings";
        float menu=menuMotion.update(serverMenu||categoryMenu||settingsMenu?1:0,AWAssistant.animations);
        if(menu>.01f){
            if(lastMenu.equals("server")){int x=panelX+panelW-202,y=panelY+93;UiRenderer.panel(g,x,y,186,Math.min(8,data.books().size())*34+12,fade*menu);for(int i=0;i<Math.min(8,data.books().size());i++)textButton(g,"server:"+i,data.books().get(i+serverOffset).name(),x+6,y+6+i*34,174,mx,my,data.books().get(i+serverOffset).id().equals(serverId),fade*menu);}
            else if(lastMenu.equals("category")){int x=panelX+leftW-196,y=resultTop+30;UiRenderer.panel(g,x,y,184,Math.min(8,categories.size()+1)*34+12,fade*menu);textButton(g,"all","Все разделы",x+6,y+6,172,mx,my,category==null,fade*menu);for(int i=0;i<Math.min(7,categories.size());i++)textButton(g,"category:"+i,categories.get(i+categoryOffset),x+6,y+40+i*34,172,mx,my,categories.get(i+categoryOffset).equals(category),fade*menu);}
            else {int x=panelX+panelW-232,y=panelY+43;UiRenderer.panel(g,x,y,218,182,fade*menu);for(int i=0;i<5;i++)textButton(g,"setting:"+i,List.of("Анимации: "+(AWAssistant.animations?"вкл":"выкл"),"Размытие: "+(AWAssistant.blur?"вкл":"выкл"),"Вернуть в центр","Папка правил","Обновить правила").get(i),x+6,y+6+i*34,206,mx,my,false,fade*menu);}
        }
        g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        double x=event.x()/scale,y=event.y()/scale;if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return true;
        if(serverMenu){int sx=panelX+panelW-196,sy=panelY+99;for(int i=0;i<Math.min(8,data.books().size());i++)if(UiRenderer.hit(x,y,sx,sy+i*34,174,28)){selectServer(data.books().get(i+serverOffset).id());return true;}serverMenu=false;}
        if(categoryMenu){int cx=panelX+leftW-190,cy=resultTop+36;if(UiRenderer.hit(x,y,cx,cy,172,28)){category=null;categoryMenu=false;updateSearch();return true;}for(int i=0;i<Math.min(7,categories.size());i++)if(UiRenderer.hit(x,y,cx,cy+34+i*34,172,28)){category=categories.get(i+categoryOffset);categoryMenu=false;updateSearch();return true;}categoryMenu=false;}
        if(settingsMenu){int sx=panelX+panelW-226,sy=panelY+49;for(int i=0;i<5;i++)if(UiRenderer.hit(x,y,sx,sy+i*34,206,28)){switch(i){case 0->AWAssistant.animations=!AWAssistant.animations;case 1->AWAssistant.blur=!AWAssistant.blur;case 2->{positionX=.5;positionY=.05;positionTouched=true;layout();}case 3->openFolder();case 4->refreshSources();}if(i<3)saveUi();return true;}settingsMenu=false;}
        if(UiRenderer.hit(x,y,panelX+panelW-43,panelY+8,28,28)){onClose();return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-88,panelY+8,30,28)){settingsMenu=true;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-212,panelY+8,108,28)){browsing=!expanded();if(!browsing)input.value("");updateSearch();return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-174,panelY+57,158,28)){serverOffset=0;serverMenu=true;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-224,panelY+54,34,34)){input.value("");updateSearch();return true;}
        if(UiRenderer.hit(x,y,panelX+8,panelY+3,panelW-232,38)){dragging=true;positionTouched=true;dragX=(float)x-panelX;dragY=(float)y-panelY;return true;}
        if(!expanded())return true;
        if(UiRenderer.hit(x,y,panelX+leftW-124,resultTop-3,108,28)){categoryOffset=0;categoryMenu=true;return true;}
        if(UiRenderer.hit(x,y,panelX+8,listTop(),leftW-16,listHeight())){select((int)((y-listTop()+listMotion.value())/ROW));return true;}
        int dx=panelX+leftW+22;
        if(UiRenderer.hit(x,y,dx,resultTop-3,76,28)){showSanctions=false;detailTarget=0;detailMotion.snap(0);return true;}
        if(UiRenderer.hit(x,y,dx+84,resultTop-3,100,28)){showSanctions=true;detailTarget=0;detailMotion.snap(0);return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-114,panelY+HEADER+bodyH-FOOTER+2,98,28)){openSource();return true;}
        if(selectedRule()==null){if(UiRenderer.hit(x,y,dx,resultTop+147,132,28))openFolder();else if(UiRenderer.hit(x,y,dx+142,resultTop+147,98,28))refreshSources();}
        else if(UiRenderer.hit(x,y,panelX+panelW-49,copyTop(),28,28)){var rule=selectedRule();minecraft.keyboardHandler.setClipboard(rule.number()+" "+rule.title()+"\n"+rule.description()+(rule.sanction().isBlank()?"":"\nНаказание: "+rule.sanction()));copiedUntil=System.currentTimeMillis()+1800;}
        return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(!dragging||event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return false;
        panelX=Math.max(12,Math.min(Math.max(12,virtualWidth-panelW-12),Math.round((float)(event.x()/scale)-dragX)));
        panelY=Math.max(12,Math.min(Math.max(12,virtualHeight-HEADER-bodyH-12),Math.round((float)(event.y()/scale)-dragY)));
        anchors();return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){if(dragging&&event.button()==InputConstants.MOUSE_BUTTON_LEFT){dragging=false;saveUi();return true;}return false;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        x/=scale;y/=scale;if(serverMenu){serverOffset=Math.max(0,Math.min(Math.max(0,data.books().size()-8),serverOffset-(int)Math.signum(vertical)));return true;}
        if(categoryMenu){categoryOffset=Math.max(0,Math.min(Math.max(0,categories.size()-7),categoryOffset-(int)Math.signum(vertical)));return true;}
        if(!expanded())return true;
        if(x<panelX+leftW)listTarget=Math.max(0,Math.min(Math.max(0,matches.size()*ROW-listHeight()),listTarget-(float)vertical*40));
        else detailTarget=Math.max(0,Math.min(Math.max(0,modelHeight()-detailHeight()),detailTarget-(float)vertical*40));return true;
    }
    @Override public boolean charTyped(CharacterEvent event){if(event.isAllowedChatCharacter()){input.insert(event.codepointAsString());updateSearch();}return true;}
    @Override public boolean keyPressed(KeyEvent event){
        if(event.key()==InputConstants.KEY_ESCAPE){if(serverMenu||categoryMenu||settingsMenu)serverMenu=categoryMenu=settingsMenu=false;else if(expanded()){input.value("");browsing=false;updateSearch();}else onClose();return true;}
        if(AWAssistant.OPEN.matches(event)){onClose();return true;}
        if(event.key()==InputConstants.KEY_DOWN||event.key()==InputConstants.KEY_UP){select(selected+(event.key()==InputConstants.KEY_DOWN?1:-1));float top=selected*ROW,bottom=top+ROW-3;if(top<listTarget)listTarget=top;else if(bottom>listTarget+listHeight())listTarget=bottom-listHeight();return true;}
        if(event.key()==InputConstants.KEY_RETURN){if(!expanded()){browsing=true;updateSearch();}detailTarget=0;detailMotion.snap(0);return true;}
        String before=input.value();if(input.key(event,minecraft.keyboardHandler.getClipboard(),minecraft.keyboardHandler::setClipboard)){if(!before.equals(input.value()))updateSearch();return true;}return super.keyPressed(event);
    }
    private void openFolder(){java.util.concurrent.CompletableFuture.runAsync(()->{try{if(java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().open(AWAssistant.RULES.folder().toFile());}catch(java.io.IOException failure){minecraft.execute(()->message="Не удалось открыть папку правил");}});}
    private void openSource(){var book=currentBook();if(book==null)return;try{var uri=java.net.URI.create(book.sourceUrl());if(!"https".equals(uri.getScheme())||uri.getHost()==null)return;java.util.concurrent.CompletableFuture.runAsync(()->{try{if(java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().browse(uri);}catch(java.io.IOException failure){minecraft.execute(()->message="Не удалось открыть источник");}});}catch(IllegalArgumentException ignored){message="Некорректная ссылка источника";}}
    @Override public void onFilesDrop(List<Path> paths){message="Импортирую правила…";AWAssistant.RULES.importFiles(paths).whenComplete((unused,failure)->minecraft.execute(()->{if(ClientBridge.screen(minecraft)!=this)return;if(failure!=null)message="Не удалось импортировать правила";else reload();}));}
}
