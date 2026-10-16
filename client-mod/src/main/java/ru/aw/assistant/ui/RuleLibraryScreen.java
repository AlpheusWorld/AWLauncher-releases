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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Per-server saved items and a read-only comparison of the actual rule revisions. */
public final class RuleLibraryScreen extends Screen {
    private record Row(String title,String text,String query) {}
    private final Screen parent;
    private final RuleBook book;
    private final Consumer<String> open;
    private int tab,selected,listScroll,detailScroll,x,y,w,h;
    private float scale=1;
    private List<Row> rows=List.of();
    private List<String> lines=List.of();
    public RuleLibraryScreen(Screen parent,RuleBook book,Consumer<String> open){super(Minecraft.getInstance(),Minecraft.getInstance().font,Component.literal("Мои правила"));this.parent=parent;this.book=book;this.open=open;}
    private static String date(long at){return at==0?"ещё не проверено":DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));}
    @Override protected void init(){UiRenderer.prepare();float pixels=Math.min(1f,Math.min((minecraft.getWindow().getWidth()-32)/820f,(minecraft.getWindow().getHeight()-32)/480f));scale=pixels/minecraft.getWindow().getGuiScale();int vw=Math.round(width/scale),vh=Math.round(height/scale);w=Math.min(820,vw-24);h=Math.min(510,vh-24);x=(vw-w)/2;y=(vh-h)/2;populate();}
    private void populate(){
        var values=new ArrayList<Row>();var repo=AWAssistant.RULES;
        if(tab==0)for(var number:repo.library().favorites().getOrDefault(book.id(),List.of())){
            var rule=book.rules().stream().filter(r->r.number().equals(number)).findFirst().orElse(null);
            values.add(new Row(number+(rule==null?" · удалён": " · "+rule.title()),rule==null?"Этого пункта больше нет в текущих правилах":rule.description()+"\n\nНаказание: "+(rule.sanction().isBlank()?"в источнике не указано":rule.sanction()),rule==null?null:number));
        }
        if(tab==1)for(var query:repo.library().queries().getOrDefault(book.id(),List.of()))values.add(new Row(query,"Нажми «Открыть», чтобы повторить запрос для "+book.name(),query));
        if(tab==2)for(var revision:repo.history(book.id()).revisions())for(var change:revision.changes()){
            String kind=change.before()==null?"Добавлен":change.after()==null?"Удалён":"Изменён";
            StringBuilder text=new StringBuilder(date(revision.at())).append("\n\n");
            if(change.before()!=null)appendRule(text,"Было",change.before());
            if(change.after()!=null)appendRule(text,"Стало",change.after());
            values.add(new Row(kind+" "+change.number()+" · "+date(revision.at()),text.toString(),change.after()==null?null:change.number()));
        }
        rows=List.copyOf(values);selected=Math.clamp(selected,0,Math.max(0,rows.size()-1));listScroll=detailScroll=0;wrap();
    }
    private static void appendRule(StringBuilder text,String heading,RuleBook.Rule rule){text.append(heading).append("\n").append(rule.number()).append(" ").append(rule.title()).append("\n").append(rule.description()).append("\nНаказание: ").append(rule.sanction().isBlank()?"в источнике не указано":rule.sanction()).append("\n\n");}
    private void wrap(){lines=rows.isEmpty()?List.of():UiRenderer.wrap(rows.get(selected).text(),Math.max(40,w-310),false);}
    private void button(GuiGraphicsExtractor g,String label,int bx,int by,int bw,boolean active,int mx,int my){UiRenderer.round(g,bx,by,bw,28,4,active?0xFF244333:UiRenderer.hit(mx,my,bx,by,bw,28)?0xFF30373F:0xFF20262C);UiRenderer.small(g,label,bx+9,by+7,active?UiRenderer.ACCENT:UiRenderer.TEXT,1);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
        if(minecraft.level==null)extractPanorama(g,tick);if(AWAssistant.blur)extractBlurredBackground(g);
        mx=Math.round(mx/scale);my=Math.round(my/scale);g.pose().pushMatrix();g.pose().scale(scale,scale);
        UiRenderer.panel(g,x,y,w,h,1);UiRenderer.bold(g,UiRenderer.ellipsis(font,"Мои правила · "+book.name(),Math.round((w-130)/1.12f)),x+18,y+17,UiRenderer.TEXT,1);
        button(g,"Назад",x+w-82,y+11,66,false,mx,my);
        int[] widths={112,106,118};String[] labels={"Избранное","Запросы","Изменения"};int tx=x+16;
        for(int i=0;i<3;i++){button(g,labels[i],tx,y+53,widths[i],tab==i,mx,my);tx+=widths[i]+6;}
        var history=AWAssistant.RULES.history(book.id());
        UiRenderer.small(g,UiRenderer.ellipsis(font,"Проверено: "+date(history.checkedAt())+" · Изменено: "+(history.changedAt()==0?"нет сохранённых изменений":date(history.changedAt())),w-32),x+16,y+91,UiRenderer.MUTED,1);
        int top=y+121,bottom=y+h-46;
        g.fill(x+258,top,x+259,bottom,0xFF343C43);
        if(rows.isEmpty()){UiRenderer.text(g,font,tab==0?"Здесь будут закреплённые правила":tab==1?"История запросов пока пустая":"Изменений пока нет",x+18,top+18,UiRenderer.MUTED,1);}
        g.enableScissor(x+8,top,x+253,bottom);
        for(int i=0;i<rows.size();i++){int ry=top+i*42-listScroll;if(ry+40<top||ry>bottom)continue;var row=rows.get(i);
            if(i==selected)UiRenderer.round(g,x+10,ry,240,38,4,0xFF26382F);
            UiRenderer.small(g,UiRenderer.ellipsis(font,row.title(),263),x+19,ry+12,i==selected?UiRenderer.ACCENT:UiRenderer.TEXT,1);
        }g.disableScissor();
        g.enableScissor(x+274,top,x+w-16,bottom);int dy=top+8-detailScroll;
        for(var line:lines){if(dy>=top-20&&dy<bottom)UiRenderer.text(g,font,line,x+276,dy,line.equals("Было")?0xFFE49BA7:line.equals("Стало")?UiRenderer.ACCENT:UiRenderer.TEXT,1);dy+=21;}g.disableScissor();
        UiRenderer.scrollBar(g,x+250,top,bottom-top,rows.size()*42,listScroll,1);UiRenderer.scrollBar(g,x+w-13,top,bottom-top,lines.size()*21,detailScroll,1);
        if(!rows.isEmpty()&&rows.get(selected).query()!=null)button(g,"Открыть",x+w-108,y+h-36,90,false,mx,my);
        if(tab==1&&!rows.isEmpty())button(g,"Очистить историю",x+16,y+h-36,166,false,mx,my);
        if(tab==0&&!rows.isEmpty())button(g,"Убрать из избранного",x+16,y+h-36,202,false,mx,my);
        g.pose().popMatrix();
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){
        if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return true;double mx=event.x()/scale,my=event.y()/scale;
        if(UiRenderer.hit(mx,my,x+w-82,y+11,66,28)){onClose();return true;}
        int tx=x+16;int[] widths={112,106,118};for(int i=0;i<3;i++){if(UiRenderer.hit(mx,my,tx,y+53,widths[i],28)){tab=i;selected=0;populate();return true;}tx+=widths[i]+6;}
        int top=y+121,bottom=y+h-46;
        if(UiRenderer.hit(mx,my,x+8,top,245,bottom-top)&&!rows.isEmpty()){selected=Math.clamp((int)(my-top+listScroll)/42,0,rows.size()-1);detailScroll=0;wrap();return true;}
        if(!rows.isEmpty()&&UiRenderer.hit(mx,my,x+w-108,y+h-36,90,28)&&rows.get(selected).query()!=null){String query=rows.get(selected).query();ClientBridge.screen(minecraft,parent);open.accept(query);return true;}
        if(!rows.isEmpty()&&UiRenderer.hit(mx,my,x+16,y+h-36,tab==0?202:166,28)){
            if(tab==1)repoResult(AWAssistant.RULES.clearQueries(book.id()));
            if(tab==0)repoResult(AWAssistant.RULES.toggleFavorite(book.id(),AWAssistant.RULES.library().favorites().get(book.id()).get(selected)));
            populate();
        }return true;
    }
    private void repoResult(java.util.concurrent.CompletableFuture<Void> result){result.exceptionally(failure->{minecraft.execute(()->lines=List.of("Не удалось сохранить изменения"));return null;});}
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){mx/=scale;int available=Math.max(1,h-167);if(mx<x+259)listScroll=Math.clamp(listScroll-(int)(vertical*36),0,Math.max(0,rows.size()*42-available));else detailScroll=Math.clamp(detailScroll-(int)(vertical*36),0,Math.max(0,lines.size()*21-available));return true;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientBridge.screen(minecraft,parent);}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float tick){}
}
