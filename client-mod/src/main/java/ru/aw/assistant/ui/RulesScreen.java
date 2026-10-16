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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Rules, saved items and settings share a stable, keyboard-accessible workspace. */
public final class RulesScreen extends Screen {
    private static final int HEADER=64,ROW=82,FOOTER=32;
    private static final String[] PAGES={"Правила","Избранное","Запросы","Изменения","Настройки"};
    private final Screen parent;
    private final SearchInput input=new SearchInput();
    private final AnimatedValue appearance=new AnimatedValue(0),listMotion=new AnimatedValue(0),detailMotion=new AnimatedValue(0);
    private RuleRepository.Snapshot data=new RuleRepository.Snapshot(List.of(),List.of());
    private RuleSearch index;
    private RuleBook indexedBook;
    private List<RuleSearch.Match> matches=List.of();
    private List<RuleRepository.RuleChange> changes=List.of();
    private List<Long> changeDates=List.of();
    private List<String> queries=List.of(),categories=List.of(),titleLines=List.of();
    private record Paragraph(String text,int color,boolean heading) {}
    private List<Paragraph> detailLines=List.of();
    private List<String> sanctionLines=List.of();
    private boolean sanctionAvailable;
    private String serverId="",category,message="",remembered="";
    private int page,selected,serverOffset,categoryOffset,panelX,panelY,panelW=1000,bodyH=568,leftW=336;
    private int virtualWidth,virtualHeight,indexRequest,queryGeneration,detailTop,resultTop,detailsWidth;
    private float scale=1,listTarget,detailTarget,dragX,dragY;
    private double positionX=.5,positionY=.5;
    private boolean loading,indexing,refreshing,closing,searchFocused,serverMenu,categoryMenu,dragging,positionTouched,fontsReady;
    private long queryAt,copiedUntil;
    public RulesScreen(Screen parent){super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("AWAssistant"));this.parent=parent;}
    @Override protected void init(){UiRenderer.prepare();fontsReady=UiRenderer.ready();layout();if(index==null)reload();}
    private void layout(){
        var frame=UiRenderer.workspace(minecraft,panelW,bodyH,positionX,positionY);
        panelX=frame.x();panelY=frame.y();scale=frame.scale();virtualWidth=frame.virtualWidth();virtualHeight=frame.virtualHeight();
        resultTop=panelY+152;detailsWidth=panelW-leftW-68;wrapResults();
    }
    private int contentX(){return panelX+20;}
    private int contentWidth(){return panelW-40;}
    private int bottom(){return panelY+bodyH-FOOTER;}
    private int listTop(){return resultTop+48;}
    private int listHeight(){return bottom()-listTop()-16;}
    private int detailHeight(){return Math.max(60,bottom()-detailTop-20);}
    private RuleBook currentBook(){return data.books().stream().filter(b->b.id().equals(serverId)).findFirst().orElse(null);}
    private RuleBook.Rule selectedRule(){return matches.isEmpty()?null:matches.get(Math.clamp(selected,0,matches.size()-1)).rule();}
    private void focusSearch(boolean focused){if(searchFocused!=focused){searchFocused=focused;ClientBridge.focusText(this,focused);}}
    private void reload(){
        if(loading)return;loading=true;
        AWAssistant.RULES.reload().whenComplete((snapshot,failure)->minecraft.execute(()->{
            loading=false;if(closing)return;
            if(failure!=null){message="Не удалось загрузить правила";return;}data=snapshot;
            if(!positionTouched){var ui=AWAssistant.RULES.ui();positionX=ui.x();positionY=ui.y()==.05?.5:ui.y();AWAssistant.animations=ui.animations();AWAssistant.blur=ui.blur();AWAssistant.smartSearch=ui.smartSearch();layout();}
            AWAssistant.SEMANTIC.enabled(AWAssistant.RULES.neural());
            if(currentBook()==null&&!data.books().isEmpty()){
                String address=minecraft.getCurrentServer()==null?"":minecraft.getCurrentServer().ip.toLowerCase(Locale.ROOT);
                serverId=data.books().stream().filter(b->b.addresses().stream().anyMatch(a->address.equals(a)||address.startsWith(a+":"))).findFirst().orElse(data.books().getFirst()).id();
            }
            prepareSearch();
            if(AWAssistant.RULES.refreshNeeded())refreshSources();
        }));
    }
    private void refreshSources(){
        if(refreshing)return;refreshing=true;message="Проверяю правила серверов";
        AWAssistant.RULES.refresh().whenComplete((snapshot,failure)->minecraft.execute(()->{
            refreshing=false;if(closing)return;
            if(failure!=null){message="Источники недоступны · открыты сохранённые правила";return;}
            data=snapshot;prepareSearch();message="Правила проверены";
        }));
    }
    private void selectServer(String id){rememberQuery();serverId=id;category=null;input.value("");serverMenu=false;selected=0;index=null;prepareSearch();}
    private void prepareSearch(){
        var book=currentBook();categories=book==null?List.of():book.rules().stream().map(RuleBook.Rule::category).filter(s->!s.isBlank()).distinct().toList();
        if(book==null){index=null;indexing=false;updateSearch();return;}
        if(book.equals(indexedBook)&&index!=null){updateSearch();return;}int request=++indexRequest;indexing=true;
        CompletableFuture.supplyAsync(()->new RuleSearch(book)).whenComplete((ready,failure)->minecraft.execute(()->{
            if(request!=indexRequest||closing)return;indexing=false;if(failure!=null){message="Не удалось подготовить поиск";return;}
            index=ready;indexedBook=book;updateSearch();
        }));
    }
    private void updateSearch(){
        int request=++queryGeneration;queryAt=System.currentTimeMillis();selected=0;listTarget=detailTarget=0;listMotion.snap(0);detailMotion.snap(0);
        var book=currentBook();if(book==null){matches=List.of();wrapResults();return;}
        if(page<=1){
            matches=input.value().isBlank()?book.rules().stream().filter(r->category==null||category.equals(r.category())).map(r->new RuleSearch.Match(r,0)).toList():index==null?List.of():index.find(input.value(),category,AWAssistant.smartSearch);
            if(page==1)matches=matches.stream().filter(m->AWAssistant.RULES.favorite(serverId,m.rule().number())).toList();
            wrapResults();
            if(!input.value().isBlank()&&index!=null&&AWAssistant.SEMANTIC.enabled())AWAssistant.SEMANTIC.search(book,input.value(),category,matches).whenComplete((found,failure)->minecraft.execute(()->{
                if(closing||request!=queryGeneration||failure!=null)return;String number=selected>0&&selectedRule()!=null?selectedRule().number():"";
                matches=page==1?found.stream().filter(m->AWAssistant.RULES.favorite(serverId,m.rule().number())).toList():found;
                selected=0;for(int i=0;i<matches.size();i++)if(matches.get(i).rule().number().equals(number))selected=i;wrapResults();
            }));
        }else if(page==2){queries=AWAssistant.RULES.library().queries().getOrDefault(serverId,List.of());}
        else if(page==3){var rows=new ArrayList<RuleRepository.RuleChange>();var dates=new ArrayList<Long>();for(var revision:AWAssistant.RULES.history(serverId).revisions())for(var change:revision.changes()){rows.add(change);dates.add(revision.at());}changes=List.copyOf(rows);changeDates=List.copyOf(dates);wrapResults();}
    }
    private void setPage(int next){rememberQuery();page=next;focusSearch(false);serverMenu=categoryMenu=false;input.value("");category=null;updateSearch();}
    private void rememberQuery(){String query=input.value().strip(),key=serverId+"\n"+query;if(page<=1&&!query.isBlank()&&!key.equals(remembered)&&!serverId.isBlank()){remembered=key;save(AWAssistant.RULES.rememberQuery(serverId,query));}}
    private void save(CompletableFuture<Void> result){result.exceptionally(f->{minecraft.execute(()->message="Не удалось сохранить изменения");return null;});}
    private void wrapResults(){
        wrapDetails();
    }
    private void paragraph(List<Paragraph> output,String text,int color,boolean heading){
        for(String line:UiRenderer.wrap(text,detailsWidth,heading?15:18,heading))output.add(new Paragraph(line,color,heading));
        output.add(new Paragraph("",color,false));
    }
    private void revisionText(List<Paragraph> output,String heading,RuleBook.Rule rule,int color){
        paragraph(output,heading,color,true);paragraph(output,rule.description(),UiRenderer.TEXT,false);
        paragraph(output,"Наказание",color,true);paragraph(output,rule.sanction().isBlank()?"В источнике не указано":rule.sanction(),UiRenderer.TEXT,false);
    }
    private void wrapDetails(){
        var paragraphs=new ArrayList<Paragraph>();String title="";sanctionLines=List.of();
        if(page==3&&!changes.isEmpty()){
            var change=changes.get(Math.clamp(selected,0,changes.size()-1));title=(change.after()!=null?change.after():change.before()).title();
            if(change.before()!=null)revisionText(paragraphs,"Было",change.before(),0xFFE5A5AC);
            if(change.after()!=null)revisionText(paragraphs,"Стало",change.after(),UiRenderer.ACCENT);
        }else{var rule=selectedRule();if(rule!=null){
            title=rule.title();sanctionAvailable=!rule.sanction().isBlank();sanctionLines=UiRenderer.wrap(rule.sanction().isBlank()?"В источнике не указано":rule.sanction(),detailsWidth-32,17,false);
            paragraph(paragraphs,rule.description(),UiRenderer.TEXT,false);
            if(rule.description().stripTrailing().endsWith(":"))for(var child:currentBook().rules())if(child.number().startsWith(rule.number()+".")){paragraph(paragraphs,child.number()+" · "+child.description(),UiRenderer.TEXT,false);if(!child.sanction().isBlank())paragraph(paragraphs,"Наказание: "+child.sanction(),0xFFE4A5AD,false);}
        }}
        titleLines=UiRenderer.wrap(title,detailsWidth,26,true);detailLines=List.copyOf(paragraphs);detailTarget=0;detailMotion.snap(0);
    }
    private int sanctionHeight(){return !sanctionAvailable?28:48+sanctionLines.size()*24;}
    private int detailContentHeight(){return detailLines.size()*27+(page==3?0:sanctionHeight()+24);}
    private int navX(int value){return panelX+232+switch(value){case 0->0;case 1->100;case 2->230;default->340;};}
    private int navWidth(int value){return switch(value){case 0->90;case 1->120;case 2->100;default->132;};}
    private int hudX(){return panelX+panelW-150;}
    private int settingsX(){return panelX+panelW-102;}
    private int searchWidth(){return contentWidth()-224;}
    private int serverX(){return panelX+panelW-232;}
    private int articleX(){return panelX+leftW;}
    private void select(int value){int count=page==3?changes.size():matches.size();if(count==0)return;int next=Math.clamp(value,0,count-1);if(next!=selected){selected=next;wrapDetails();}}
    private void saveUi(){
        positionX=(panelX-18)/(double)Math.max(1,virtualWidth-panelW-36);positionY=(panelY-18)/(double)Math.max(1,virtualHeight-bodyH-36);
        save(AWAssistant.RULES.saveUi(new RuleRepository.UiPreferences(positionX,positionY,AWAssistant.animations,AWAssistant.blur,AWAssistant.smartSearch)));
    }
    private static String date(long value){return value==0?"Ещё не проверялись":DateTimeFormatter.ofPattern("dd.MM · HH:mm").format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()));}
    private void button(GuiGraphicsExtractor g,String text,int x,int y,int w,int h,double mx,double my,boolean active,float fade){UiRenderer.button(g,text,x,y,w,h,active,UiRenderer.hit(mx,my,x,y,w,h),fade);}
    private void iconButton(GuiGraphicsExtractor g,UiRenderer.Icon icon,int x,int y,int size,boolean active,double mx,double my,float fade){UiRenderer.iconButton(g,icon,x,y,size,active,UiRenderer.hit(mx,my,x,y,size,size),fade);}
    private void empty(GuiGraphicsExtractor g,String title,String hint,int x,int y,int width,float fade){
        UiRenderer.icon(g,page==1?UiRenderer.Icon.BOOKMARK:UiRenderer.Icon.SEARCH,x,y,32,UiRenderer.MUTED,fade);
        UiRenderer.label(g,title,x,y+54,23,true,UiRenderer.TEXT,fade);int by=y+96;
        for(String line:UiRenderer.wrap(hint,width,17,false)){UiRenderer.label(g,line,x,by,17,false,UiRenderer.MUTED,fade);by+=26;}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
        if(minecraft.level==null)extractPanorama(g,tick);if(AWAssistant.blur)extractBlurredBackground(g);g.fill(0,0,width,height,0x78060708);
        float fade=appearance.update(closing?0:1,AWAssistant.animations);double mx=mouseX/scale,my=mouseY/scale;
        g.pose().pushMatrix();g.pose().scale(scale,scale);UiRenderer.panel(g,panelX,panelY,panelW,bodyH,fade);
        UiRenderer.brand(g,panelX+22,panelY+20,fade);UiRenderer.label(g,"AWAssistant",panelX+58,panelY+21,21,true,UiRenderer.TEXT,fade);
        for(int i=0;i<4;i++){
            int nx=navX(i),nw=navWidth(i);boolean active=page==i;
            if(UiRenderer.hit(mx,my,nx,panelY+12,nw,40))UiRenderer.round(g,nx,panelY+12,nw,40,8,UiRenderer.alpha(UiRenderer.SURFACE,fade));
            String name=i==2?"История":PAGES[i];UiRenderer.label(g,name,nx+(nw-UiRenderer.measure(name,16,true))/2,panelY+22,16,true,active?UiRenderer.TEXT:UiRenderer.MUTED,fade);
            if(active)UiRenderer.round(g,nx+16,panelY+HEADER-3,nw-32,3,1,UiRenderer.alpha(UiRenderer.ACCENT,fade));
        }
        iconButton(g,UiRenderer.Icon.HUD,hudX(),panelY+12,40,false,mx,my,fade);
        iconButton(g,UiRenderer.Icon.SETTINGS,settingsX(),panelY+12,40,page==4,mx,my,fade);
        iconButton(g,UiRenderer.Icon.CLOSE,panelX+panelW-52,panelY+12,40,false,mx,my,fade);
        g.fill(panelX+1,panelY+HEADER,panelX+panelW-1,panelY+HEADER+1,UiRenderer.alpha(UiRenderer.LINE,fade));
        if(page<=1)drawRules(g,mx,my,fade);else if(page==2)drawHistory(g,mx,my,fade);else if(page==3)drawChanges(g,mx,my,fade);else drawSettings(g,mx,my,fade);
        drawFooter(g,mx,my,fade);
        if(serverMenu)drawServerMenu(g,mx,my,fade);if(categoryMenu)drawCategoryMenu(g,mx,my,fade);
        if(!serverMenu&&!categoryMenu){
            if(UiRenderer.hit(mx,my,hudX(),panelY+12,40,40))tooltip(g,"Редактор HUD · F9",hudX()-36,panelY+60,fade);
            else if(UiRenderer.hit(mx,my,settingsX(),panelY+12,40,40))tooltip(g,"Настройки",settingsX()-44,panelY+60,fade);
            else if(UiRenderer.hit(mx,my,panelX+panelW-96,resultTop+14,34,34)&&page<=1&&selectedRule()!=null)tooltip(g,"Сохранить правило",panelX+panelW-218,resultTop+52,fade);
            else if(UiRenderer.hit(mx,my,panelX+panelW-56,resultTop+14,34,34)&&page<=1&&selectedRule()!=null)tooltip(g,"Копировать",panelX+panelW-156,resultTop+52,fade);
        }
        g.pose().popMatrix();
    }
    private void tooltip(GuiGraphicsExtractor g,String text,int x,int y,float fade){int w=UiRenderer.measure(text,14,false)+24;UiRenderer.round(g,x,y,w,34,7,UiRenderer.alpha(0xFF3C444A,fade));UiRenderer.label(g,text,x+12,y+8,14,false,UiRenderer.TEXT,fade);}
    private void drawFooter(GuiGraphicsExtractor g,double mx,double my,float fade){
        var book=currentBook();int y=bottom()+8;UiRenderer.label(g,"F8",contentX(),y,12,true,UiRenderer.MUTED,fade);UiRenderer.label(g,"Закрыть",contentX()+24,y,12,false,UiRenderer.MUTED,fade);
        long checked=AWAssistant.RULES.history(serverId).checkedAt();
        String status=!message.isBlank()?message:book!=null&&book.name().endsWith(" · памятка")?"Сохранённая памятка":checked==0?"Сохранённые правила":"Проверено "+date(checked);
        if(book!=null){String project=book.name().split(" · ")[0];String issue=data.errors().stream().filter(error->error.startsWith(project+":")).findFirst().orElse("");if(!issue.isBlank())status=issue;}
        if(AWAssistant.SEMANTIC.enabled()&&page<=1&&!input.value().isBlank()&&(message.isBlank()||message.equals("Правила проверены")))status=AWAssistant.SEMANTIC.status();
        if(System.currentTimeMillis()<copiedUntil)status="Правило скопировано";
        UiRenderer.label(g,UiRenderer.fit(status,contentWidth()-370,12,false),contentX()+116,y,12,false,UiRenderer.MUTED,fade);
        if(book!=null&&!book.sourceUrl().isBlank()){
            int sx=panelX+panelW-206;boolean hover=UiRenderer.hit(mx,my,sx,bottom(),186,30);
            UiRenderer.label(g,"Правила на сайте",sx+10,y,13,false,hover?UiRenderer.TEXT:UiRenderer.MUTED,fade);
            UiRenderer.icon(g,UiRenderer.Icon.EXTERNAL,sx+153,y-1,15,hover?UiRenderer.TEXT:UiRenderer.MUTED,fade);
        }
    }
    private void drawSearch(GuiGraphicsExtractor g,double mx,double my,float fade){
        int x=contentX(),y=panelY+82,w=searchWidth();UiRenderer.outline(g,x,y,w,54,searchFocused,fade);
        UiRenderer.icon(g,UiRenderer.Icon.SEARCH,x+18,y+17,20,searchFocused?UiRenderer.ACCENT:UiRenderer.MUTED,fade);
        String value=input.value().isBlank()?"Поиск по правилам":input.value();value=UiRenderer.fit(value,w-112,18,false);
        if(searchFocused&&input.selectedAll())UiRenderer.round(g,x+52,y+12,Math.min(w-112,UiRenderer.measure(value,18,false)),30,3,UiRenderer.alpha(0x554E9B75,fade));
        UiRenderer.label(g,value,x+52,y+16,18,false,input.value().isBlank()?UiRenderer.MUTED:UiRenderer.TEXT,fade);
        if(searchFocused){ClientBridge.textArea(Math.round((x+52)*scale),Math.round((y+12)*scale),Math.round((w-112)*scale),Math.round(30*scale));
            if((System.nanoTime()/500_000_000L)%2==0){int cx=x+52+Math.min(w-112,UiRenderer.measure(input.value().substring(0,input.cursor()),18,false));g.fill(cx,y+15,cx+1,y+38,UiRenderer.alpha(UiRenderer.TEXT,fade));}}
        if(input.value().isBlank()){UiRenderer.round(g,x+w-58,y+17,42,23,4,UiRenderer.alpha(UiRenderer.HOVER,fade));UiRenderer.label(g,"Ctrl K",x+w-52,y+21,11,false,UiRenderer.MUTED,fade);}
        else iconButton(g,UiRenderer.Icon.CLOSE,x+w-46,y+10,34,false,mx,my,fade);
        var book=currentBook();String name=book==null?"Сервер":book.name().replace(" · памятка","");int sx=serverX();boolean hovered=UiRenderer.hit(mx,my,sx,y,212,54);
        UiRenderer.outline(g,sx,y,212,54,serverMenu,fade);if(hovered)UiRenderer.round(g,sx+1,y+1,210,52,8,UiRenderer.alpha(UiRenderer.HOVER,fade));
        UiRenderer.round(g,sx+12,y+11,32,32,7,UiRenderer.alpha(0xFF2A3D33,fade));
        UiRenderer.label(g,name.isEmpty()?"?":name.substring(0,1),sx+21,y+17,18,true,UiRenderer.ACCENT,fade);
        UiRenderer.label(g,UiRenderer.fit(name,123,16,true),sx+54,y+10,16,true,UiRenderer.TEXT,fade);
        UiRenderer.label(g,"Правила сервера",sx+54,y+31,11,false,UiRenderer.MUTED,fade);UiRenderer.icon(g,UiRenderer.Icon.CHEVRON,sx+182,y+20,14,UiRenderer.MUTED,fade);
        int ly=resultTop;boolean hover=UiRenderer.hit(mx,my,x,ly,256,36);UiRenderer.round(g,x,ly,256,36,7,UiRenderer.alpha(hover?UiRenderer.HOVER:UiRenderer.SURFACE,fade));
        UiRenderer.label(g,UiRenderer.fit(category==null?"Все разделы":category,198,15,true),x+12,ly+9,15,true,UiRenderer.TEXT,fade);UiRenderer.icon(g,UiRenderer.Icon.CHEVRON,x+230,ly+11,14,UiRenderer.MUTED,fade);
        String count=loading||indexing?"…":Integer.toString(matches.size());UiRenderer.label(g,count,x+300-UiRenderer.measure(count,14,false),ly+10,14,false,UiRenderer.MUTED,fade);
    }
    private void drawRules(GuiGraphicsExtractor g,double mx,double my,float fade){drawSearch(g,mx,my,fade);drawList(g,mx,my,fade,false);drawDetail(g,mx,my,fade,false);}
    private void drawList(GuiGraphicsExtractor g,double mx,double my,float fade,boolean revisions){
        int x=contentX(),w=300,count=revisions?changes.size():matches.size();float scroll=listMotion.update(listTarget,AWAssistant.animations);
        g.enableScissor(x,listTop(),x+w,bottom()-16);
        for(int i=Math.max(0,(int)(scroll/ROW));i<count;i++){
            int y=listTop()+i*ROW-Math.round(scroll);if(y>bottom()-16)break;boolean active=i==selected,hover=UiRenderer.hit(mx,my,x,y,w,ROW-7);
            if(active||hover){UiRenderer.round(g,x,y,w,ROW-7,9,UiRenderer.alpha(active?0xFF20392D:UiRenderer.SURFACE,fade));if(active)UiRenderer.round(g,x,y+16,3,ROW-39,1,UiRenderer.alpha(UiRenderer.ACCENT,fade));}
            var rule=revisions?(changes.get(i).after()!=null?changes.get(i).after():changes.get(i).before()):matches.get(i).rule();
            String metadata=revisions?changes.get(i).number()+" · "+(changes.get(i).before()==null?"Добавлен":changes.get(i).after()==null?"Удалён":"Изменён"):rule.number()+" · "+rule.category();
            UiRenderer.label(g,UiRenderer.fit(metadata,w-32,12,false),x+16,y+11,12,false,active?UiRenderer.ACCENT:UiRenderer.MUTED,fade);
            var lines=UiRenderer.wrap(rule.title(),w-32,17,active);int ty=y+31;
            for(int j=0;j<Math.min(2,lines.size());j++){String line=lines.get(j);if(j==1&&lines.size()>2)line=UiRenderer.fit(line+"…",w-32,17,active);UiRenderer.label(g,line,x+16,ty,17,active,UiRenderer.TEXT,fade);ty+=22;}
        }
        g.disableScissor();UiRenderer.scrollBar(g,x+w+5,listTop(),listHeight(),count*ROW,scroll,fade);
        if(count==0&&!loading&&!indexing){UiRenderer.label(g,page==1?"Пока ничего не сохранено":"Ничего не найдено",x+12,listTop()+22,16,false,UiRenderer.MUTED,fade);}
    }
    private void drawDetail(GuiGraphicsExtractor g,double mx,double my,float fade,boolean revisions){
        int ax=articleX(),aw=panelW-leftW-20,x=ax+24,y=resultTop;UiRenderer.round(g,ax,y,aw,bottom()-y-12,10,UiRenderer.alpha(UiRenderer.SURFACE,fade));
        boolean exists=revisions?!changes.isEmpty():selectedRule()!=null;
        if(!exists){
            if(loading||indexing){for(int i=0;i<5;i++)UiRenderer.round(g,x,y+34+i*42,detailsWidth-(i%3)*61,14,4,UiRenderer.alpha(UiRenderer.HOVER,fade));return;}
            empty(g,page==1?"Важное — под рукой":revisions?"Правила не менялись":"Не нашлось подходящего пункта",page==1?"Сохраняй правила закладкой — они останутся здесь для выбранного сервера":revisions?"Когда источник обновится, здесь появятся прежний и новый текст":"Попробуй уточнить запрос или выбрать другой раздел",x,y+48,detailsWidth-24,fade);return;
        }
        var rule=revisions?(changes.get(selected).after()!=null?changes.get(selected).after():changes.get(selected).before()):selectedRule();
        String meta=revisions?date(changeDates.get(selected)):"Пункт "+rule.number();
        UiRenderer.label(g,meta,x,y+22,13,true,UiRenderer.ACCENT,fade);
        if(!revisions){int mw=UiRenderer.measure(meta,13,true);UiRenderer.label(g,UiRenderer.fit(rule.category(),detailsWidth-mw-110,13,false),x+mw+14,y+22,13,false,UiRenderer.MUTED,fade);
            boolean saved=AWAssistant.RULES.favorite(serverId,rule.number());iconButton(g,UiRenderer.Icon.BOOKMARK,panelX+panelW-104,y+12,34,saved,mx,my,fade);iconButton(g,UiRenderer.Icon.COPY,panelX+panelW-64,y+12,34,false,mx,my,fade);}
        int ty=y+53;for(int i=0;i<Math.min(2,titleLines.size());i++){String line=titleLines.get(i);if(i==1&&titleLines.size()>2)line=UiRenderer.fit(line+"…",detailsWidth,26,true);UiRenderer.label(g,line,x,ty,26,true,UiRenderer.TEXT,fade);ty+=33;}detailTop=ty+20;
        float scroll=detailMotion.update(detailTarget,AWAssistant.animations);g.enableScissor(x,detailTop,x+detailsWidth,bottom()-28);int dy=detailTop-Math.round(scroll);
        if(!revisions){int sh=sanctionHeight();
            if(sanctionAvailable){UiRenderer.round(g,x,dy,detailsWidth,sh,8,UiRenderer.alpha(0xFF302629,fade));
                UiRenderer.round(g,x,dy+12,3,Math.max(12,sh-24),1,UiRenderer.alpha(0xFFD9959E,fade));
                UiRenderer.label(g,"НАКАЗАНИЕ",x+16,dy+12,11,true,0xFFE4A5AD,fade);int py=dy+35;
                for(String line:sanctionLines){if(py>=detailTop-24&&py<bottom()-28)UiRenderer.label(g,line,x+16,py,17,false,UiRenderer.TEXT,fade);py+=24;}
            }else UiRenderer.label(g,"Наказание в источнике не указано",x,dy+4,13,false,UiRenderer.MUTED,fade);
            dy+=sh+24;
        }
        for(var line:detailLines){if(dy>=detailTop-27&&dy<bottom()-28)UiRenderer.label(g,line.text(),x,dy,line.heading()?15:18,line.heading(),line.color(),fade);dy+=27;}
        g.disableScissor();UiRenderer.scrollBar(g,panelX+panelW-30,detailTop,detailHeight()-8,detailContentHeight(),scroll,fade);
    }
    private void drawHistory(GuiGraphicsExtractor g,double mx,double my,float fade){
        int x=contentX(),y=panelY+88;UiRenderer.label(g,"История поиска",x,y,26,true,UiRenderer.TEXT,fade);
        var book=currentBook();UiRenderer.label(g,book==null?"":book.name().replace(" · памятка",""),x,y+39,15,false,UiRenderer.MUTED,fade);
        if(!queries.isEmpty()){UiRenderer.outline(g,x+contentWidth()-144,y,144,40,false,fade);UiRenderer.icon(g,UiRenderer.Icon.TRASH,x+contentWidth()-130,y+11,18,UiRenderer.MUTED,fade);UiRenderer.label(g,"Очистить",x+contentWidth()-100,y+11,15,true,UiRenderer.TEXT,fade);}
        if(queries.isEmpty()){empty(g,"Здесь будут недавние запросы","История хранится отдельно для каждого сервера",x+28,y+124,contentWidth()-100,fade);return;}
        float scroll=listMotion.update(listTarget,AWAssistant.animations);int top=panelY+164;UiRenderer.round(g,x,top,contentWidth(),bottom()-top-16,10,UiRenderer.alpha(UiRenderer.SURFACE,fade));g.enableScissor(x+1,top+1,x+contentWidth()-1,bottom()-17);
        for(int i=Math.max(0,(int)(scroll/64));i<queries.size();i++){int ry=top+i*64-Math.round(scroll);if(ry>bottom())break;boolean hovered=UiRenderer.hit(mx,my,x,ry,contentWidth(),64);
            if(hovered)g.fill(x+1,ry,x+contentWidth()-1,ry+64,UiRenderer.alpha(UiRenderer.HOVER,fade));
            UiRenderer.icon(g,UiRenderer.Icon.HISTORY,x+20,ry+23,20,UiRenderer.MUTED,fade);UiRenderer.label(g,UiRenderer.fit(queries.get(i),contentWidth()-136,18,false),x+58,ry+21,18,false,UiRenderer.TEXT,fade);
            if(hovered)UiRenderer.icon(g,UiRenderer.Icon.ARROW,x+contentWidth()-44,ry+23,20,UiRenderer.ACCENT,fade);
            g.fill(x+58,ry+63,x+contentWidth()-24,ry+64,UiRenderer.alpha(UiRenderer.LINE,fade));}
        g.disableScissor();UiRenderer.scrollBar(g,x+contentWidth()+7,top,bottom()-16-top,queries.size()*64,scroll,fade);
    }
    private void drawChanges(GuiGraphicsExtractor g,double mx,double my,float fade){
        int x=contentX(),y=panelY+88;UiRenderer.label(g,"Изменения правил",x,y,26,true,UiRenderer.TEXT,fade);
        UiRenderer.label(g,"Последняя проверка: "+date(AWAssistant.RULES.history(serverId).checkedAt()),x,y+39,14,false,UiRenderer.MUTED,fade);
        UiRenderer.label(g,"Журнал",x+12,resultTop+12,15,true,UiRenderer.TEXT,fade);UiRenderer.label(g,Integer.toString(changes.size()),x+274,resultTop+12,14,false,UiRenderer.MUTED,fade);
        drawList(g,mx,my,fade,true);drawDetail(g,mx,my,fade,true);
    }
    private int settingX(int row){return contentX()+(row>=2?488:0);}
    private int settingY(int row){return panelY+220+(row%2)*128;}
    private void settingsRow(GuiGraphicsExtractor g,int row,String title,String hint,boolean value,double mx,double my,float fade){
        int x=settingX(row),y=settingY(row);UiRenderer.label(g,title,x+20,y,17,true,UiRenderer.TEXT,fade);
        UiRenderer.toggle(g,x+408,y-2,value,UiRenderer.hit(mx,my,x+396,y-10,64,44),fade);
        int yy=y+35;for(String line:UiRenderer.wrap(hint,374,15,false)){UiRenderer.label(g,line,x+20,yy,15,false,UiRenderer.MUTED,fade);yy+=23;}
        if(row%2==0)g.fill(x+20,y+105,x+452,y+106,UiRenderer.alpha(UiRenderer.LINE,fade));
    }
    private void drawSettings(GuiGraphicsExtractor g,double mx,double my,float fade){
        int x=contentX(),y=panelY+88;UiRenderer.label(g,"Настройки",x,y,26,true,UiRenderer.TEXT,fade);UiRenderer.label(g,"Изменения сохраняются автоматически",x,y+39,15,false,UiRenderer.MUTED,fade);
        for(int i=0;i<2;i++){int sx=x+i*488;UiRenderer.round(g,sx,panelY+168,472,310,10,UiRenderer.alpha(UiRenderer.SURFACE,fade));UiRenderer.icon(g,i==0?UiRenderer.Icon.SEARCH:UiRenderer.Icon.SETTINGS,sx+20,panelY+185,20,UiRenderer.ACCENT,fade);UiRenderer.label(g,i==0?"Поиск":"Интерфейс",sx+52,panelY+186,15,true,UiRenderer.TEXT,fade);}
        settingsRow(g,0,"Поиск по смыслу","Сопоставляет вопрос с текстом правил. Модель и компоненты: "+Math.round(SemanticSearch.downloadBytes()/1048576.0)+" МБ, скачиваются при первом запросе",AWAssistant.SEMANTIC.enabled(),mx,my,fade);
        settingsRow(g,1,"Формы слов","Находить слова в разных падежах и склонениях",AWAssistant.smartSearch,mx,my,fade);
        settingsRow(g,2,"Плавные анимации","Мягкое появление окна и прокрутка без скачков",AWAssistant.animations,mx,my,fade);
        settingsRow(g,3,"Размытие фона","Приглушать игру, пока открыт помощник",AWAssistant.blur,mx,my,fade);
        button(g,refreshing?"Проверяю правила…":"Проверить правила",x,panelY+494,228,38,mx,my,false,fade);
        button(g,"Центрировать окно",x+244,panelY+494,220,38,mx,my,false,fade);
    }
    private void drawServerMenu(GuiGraphicsExtractor g,double mx,double my,float fade){
        int x=serverX()-66,y=panelY+143,count=Math.min(8,data.books().size());UiRenderer.panel(g,x,y,278,count*48+12,fade);
        for(int i=0;i<count;i++){
            var book=data.books().get(i+serverOffset);int ry=y+6+i*48;boolean active=book.id().equals(serverId);
            if(active||UiRenderer.hit(mx,my,x+6,ry,266,44))UiRenderer.round(g,x+6,ry,266,44,7,UiRenderer.alpha(active?0xFF20392D:UiRenderer.HOVER,fade));
            UiRenderer.label(g,UiRenderer.fit(book.name().replace(" · памятка",""),218,16,active),x+18,ry+13,16,active,active?UiRenderer.ACCENT:UiRenderer.TEXT,fade);
            if(active)UiRenderer.icon(g,UiRenderer.Icon.CHECK,x+244,ry+14,16,UiRenderer.ACCENT,fade);
        }
    }
    private void drawCategoryMenu(GuiGraphicsExtractor g,double mx,double my,float fade){
        int x=contentX(),y=resultTop+42;UiRenderer.panel(g,x,y,302,Math.min(8,categories.size()+1)*42+12,fade);
        button(g,"Все разделы",x+6,y+6,290,38,mx,my,category==null,fade);
        for(int i=0;i<Math.min(7,categories.size());i++)button(g,UiRenderer.fit(categories.get(i+categoryOffset),258,16,true),x+6,y+48+i*42,290,38,mx,my,categories.get(i+categoryOffset).equals(category),fade);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return true;double x=event.x()/scale,y=event.y()/scale;focusSearch(false);
        if(serverMenu){int sx=serverX()-60,sy=panelY+149;for(int i=0;i<Math.min(8,data.books().size());i++)if(UiRenderer.hit(x,y,sx,sy+i*48,266,44)){selectServer(data.books().get(i+serverOffset).id());return true;}serverMenu=false;return true;}
        if(categoryMenu){int cy=resultTop+48;if(UiRenderer.hit(x,y,contentX()+6,cy,290,38)){category=null;categoryMenu=false;updateSearch();return true;}for(int i=0;i<Math.min(7,categories.size());i++)if(UiRenderer.hit(x,y,contentX()+6,cy+42+i*42,290,38)){category=categories.get(i+categoryOffset);categoryMenu=false;updateSearch();return true;}categoryMenu=false;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-52,panelY+12,40,40)){onClose();return true;}
        for(int i=0;i<4;i++)if(UiRenderer.hit(x,y,navX(i),panelY+12,navWidth(i),40)){setPage(i);return true;}
        if(UiRenderer.hit(x,y,settingsX(),panelY+12,40,40)){setPage(4);return true;}
        if(UiRenderer.hit(x,y,hudX(),panelY+12,40,40)){ClientBridge.screen(minecraft,AWAssistant.HUD.editor(this));return true;}
        if(y>=panelY+4&&y<panelY+HEADER&&x>=panelX+12&&x<panelX+222){dragging=positionTouched=true;dragX=(float)x-panelX;dragY=(float)y-panelY;return true;}
        if(UiRenderer.hit(x,y,panelX+panelW-206,bottom(),186,30)){openSource();return true;}
        if(page<=1){
            if(UiRenderer.hit(x,y,contentX(),panelY+82,searchWidth(),54)){focusSearch(true);if(x>contentX()+searchWidth()-46&&!input.value().isBlank()){input.value("");updateSearch();}return true;}
            if(UiRenderer.hit(x,y,serverX(),panelY+82,212,54)){serverOffset=0;serverMenu=true;return true;}
            if(UiRenderer.hit(x,y,contentX(),resultTop,256,36)){categoryOffset=0;categoryMenu=true;return true;}
            if(selectedRule()!=null&&UiRenderer.hit(x,y,panelX+panelW-104,resultTop+12,34,34)){save(AWAssistant.RULES.toggleFavorite(serverId,selectedRule().number()));if(page==1)updateSearch();return true;}
            if(selectedRule()!=null&&UiRenderer.hit(x,y,panelX+panelW-64,resultTop+12,34,34)){var r=selectedRule();minecraft.keyboardHandler.setClipboard(r.number()+" "+r.title()+"\n"+r.description()+"\nНаказание: "+(r.sanction().isBlank()?"в источнике не указано":r.sanction()));copiedUntil=System.currentTimeMillis()+1800;return true;}
        }
        if((page<=1||page==3)&&UiRenderer.hit(x,y,contentX(),listTop(),300,listHeight())){select((int)((y-listTop()+listMotion.value())/ROW));return true;}
        if(page==2){
            if(UiRenderer.hit(x,y,contentX()+contentWidth()-144,panelY+88,144,40)){save(AWAssistant.RULES.clearQueries(serverId));updateSearch();return true;}
            int top=panelY+164;if(UiRenderer.hit(x,y,contentX(),top,contentWidth(),bottom()-top-16)&&!queries.isEmpty()){int i=(int)((y-top+listMotion.value())/64);if(i<queries.size()){String query=queries.get(i);setPage(0);input.value(query);focusSearch(true);updateSearch();}return true;}
        }
        if(page==4){
            for(int i=0;i<4;i++)if(UiRenderer.hit(x,y,settingX(i)+396,settingY(i)-10,64,44)){
                switch(i){case 0->{boolean enabled=!AWAssistant.SEMANTIC.enabled();AWAssistant.SEMANTIC.enabled(enabled);save(AWAssistant.RULES.saveNeural(enabled));message="";}case 1->AWAssistant.smartSearch=!AWAssistant.smartSearch;case 2->AWAssistant.animations=!AWAssistant.animations;case 3->AWAssistant.blur=!AWAssistant.blur;}if(i>0)saveUi();return true;}
            if(UiRenderer.hit(x,y,contentX(),panelY+494,228,38))refreshSources();
            if(UiRenderer.hit(x,y,contentX()+244,panelY+494,220,38)){positionX=positionY=.5;positionTouched=true;layout();saveUi();}
        }return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){if(!dragging||event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return false;panelX=Math.clamp(Math.round((float)(event.x()/scale)-dragX),18,Math.max(18,virtualWidth-panelW-18));panelY=Math.clamp(Math.round((float)(event.y()/scale)-dragY),18,Math.max(18,virtualHeight-bodyH-18));resultTop=panelY+152;return true;}
    @Override public boolean mouseReleased(MouseButtonEvent event){if(dragging&&event.button()==InputConstants.MOUSE_BUTTON_LEFT){dragging=false;saveUi();return true;}return false;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        x/=scale;y/=scale;if(serverMenu){serverOffset=Math.clamp(serverOffset-(int)Math.signum(vertical),0,Math.max(0,data.books().size()-8));return true;}
        if(categoryMenu){categoryOffset=Math.clamp(categoryOffset-(int)Math.signum(vertical),0,Math.max(0,categories.size()-7));return true;}if(page==4)return true;
        if(!UiRenderer.hit(x,y,contentX(),panelY+HEADER,contentWidth(),bottom()-panelY-HEADER))return true;
        if(page==2||x<articleX()){int count=page==2?queries.size():page==3?changes.size():matches.size();int row=page==2?64:ROW;int visible=page==2?bottom()-panelY-180:listHeight();listTarget=Math.clamp(listTarget-(float)vertical*48,0,Math.max(0,count*row-visible));}
        else detailTarget=Math.clamp(detailTarget-(float)vertical*54,0,Math.max(0,detailContentHeight()-detailHeight()+8));return true;
    }
    @Override public boolean charTyped(CharacterEvent event){if(!searchFocused||page>1)return false;if(event.isAllowedChatCharacter()){input.insert(event.codepointAsString());updateSearch();}return true;}
    @Override public boolean keyPressed(KeyEvent event){
        if(AWAssistant.OPEN.matches(event)){onClose();return true;}
        if(event.key()==InputConstants.KEY_ESCAPE){if(serverMenu||categoryMenu){serverMenu=categoryMenu=false;}else if(searchFocused&&!input.value().isBlank()){input.value("");updateSearch();}else onClose();return true;}
        if(event.key()==InputConstants.KEY_K&&(event.modifiers()&InputConstants.MOD_CONTROL)!=0&&page<=1){focusSearch(true);return true;}
        if(event.key()==InputConstants.KEY_TAB&&page<=1){focusSearch(!searchFocused);return true;}
        if((event.key()==InputConstants.KEY_DOWN||event.key()==InputConstants.KEY_UP)&&(page<=1||page==3)){select(selected+(event.key()==InputConstants.KEY_DOWN?1:-1));float top=selected*ROW;if(top<listTarget)listTarget=top;else if(top+ROW>listTarget+listHeight())listTarget=top+ROW-listHeight();return true;}
        if(event.key()==InputConstants.KEY_RETURN){rememberQuery();detailTarget=0;detailMotion.snap(0);return true;}
        String before=input.value();if(searchFocused&&input.key(event,minecraft.keyboardHandler.getClipboard(),minecraft.keyboardHandler::setClipboard)){if(!before.equals(input.value()))updateSearch();return true;}return super.keyPressed(event);
    }
    @Override public void tick(){if(!fontsReady&&UiRenderer.ready()){fontsReady=true;layout();}if(System.currentTimeMillis()-queryAt>900&&!indexing)rememberQuery();if(closing&&appearance.value()<.015f)ClientBridge.screen(minecraft,parent);}
    @Override public void onClose(){rememberQuery();focusSearch(false);if(dragging)saveUi();dragging=false;closing=true;serverMenu=categoryMenu=false;}
    @Override public void removed(){focusSearch(false);rememberQuery();super.removed();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float tick){}
    private void openSource(){var book=currentBook();if(book==null)return;try{var uri=java.net.URI.create(book.sourceUrl());if(!"https".equals(uri.getScheme())||uri.getHost()==null)return;CompletableFuture.runAsync(()->{try{if(java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().browse(uri);}catch(java.io.IOException failure){minecraft.execute(()->message="Не удалось открыть источник");}});}catch(IllegalArgumentException invalid){message="Некорректная ссылка источника";}}
    @Override public void onFilesDrop(List<Path> paths){message="Импортирую правила";AWAssistant.RULES.importFiles(paths).whenComplete((unused,failure)->minecraft.execute(()->{if(failure!=null)message="Не удалось импортировать правила";else reload();}));}
}
