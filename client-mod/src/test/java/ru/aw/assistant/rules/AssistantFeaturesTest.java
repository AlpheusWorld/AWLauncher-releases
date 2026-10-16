package ru.aw.assistant.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantFeaturesTest {
    @TempDir Path root;
    private RuleBook.Rule rule(String number,String text,String sanction){return new RuleBook.Rule(number,"Правило "+number,"Игра",text,sanction,List.of());}
    private RuleBook book(RuleBook.Rule... rules){return new RuleBook("funtime","Тест",List.of(),"https://example.org/rules",List.of(rules));}
    @Test void revisionsKeepFullOldNewTextAndOnlyRecordActualChanges() throws Exception {
        var before=book(rule("1.1","Первый текст","Бан 1 день"),rule("1.2","Удаляемый пункт",""));
        var after=book(rule("1.1","Новый текст с условиями","Бан 2 дня"),rule("1.3","Новый пункт",""));
        try(var repo=new RuleRepository(root)){
            repo.recordCheck(null,before,100);assertTrue(repo.history("funtime").revisions().isEmpty());
            repo.recordCheck(before,after,200);repo.recordCheck(after,after,300);
            var history=repo.history("funtime");assertEquals(300,history.checkedAt());assertEquals(200,history.changedAt());
            assertEquals(1,history.revisions().size());var changes=history.revisions().getFirst().changes();assertEquals(3,changes.size());
            assertEquals("Бан 1 день",changes.getFirst().before().sanction());assertEquals("Бан 2 дня",changes.getFirst().after().sanction());
            assertNull(changes.get(1).before());assertNull(changes.get(2).after());
        }
        try(var repo=new RuleRepository(root)){repo.reload().get();assertEquals(1,repo.history("funtime").revisions().size());assertEquals(300,repo.history("funtime").checkedAt());}
    }
    @Test void savedItemsAndQueriesAreSeparatedByServerAndPersist() throws Exception {
        try(var repo=new RuleRepository(root)){
            repo.reload().get();repo.toggleFavorite("funtime","1.1").get();repo.toggleFavorite("holyworld","2.2").get();
            repo.rememberQuery("funtime","фанпей").get();repo.rememberQuery("holyworld","читы").get();repo.rememberQuery("funtime","фанпей").get();
            for(int i=0;i<40;i++)repo.rememberQuery("funtime","запрос "+i).get();
            assertEquals(30,repo.library().queries().get("funtime").size());assertEquals("запрос 39",repo.library().queries().get("funtime").getFirst());
        }
        try(var repo=new RuleRepository(root)){repo.reload().get();assertTrue(repo.favorite("funtime","1.1"));assertFalse(repo.favorite("holyworld","1.1"));
            assertEquals(List.of("читы"),repo.library().queries().get("holyworld"));repo.clearQueries("funtime").get();
            assertFalse(repo.library().queries().containsKey("funtime"));assertTrue(repo.favorite("funtime","1.1"));
        }
    }
    @Test void hudSettingsAndOptInModelPersistWithoutOverwritingExistingPreferences() throws Exception {
        var file=root.resolve("awassistant/ui.json");Files.createDirectories(file.getParent());Files.writeString(file,"{\"unknown\":42,\"animations\":false,\"x\":0.2}");
        try(var repo=new RuleRepository(root)){
            repo.reload().get();assertFalse(repo.neural());assertTrue(repo.hud().values().stream().noneMatch(RuleRepository.HudWidget::enabled));
            var widgets=new LinkedHashMap<>(repo.hud());widgets.put("fps",new RuleRepository.HudWidget(true,3,-2,99));repo.saveHud(widgets).get();repo.saveNeural(true).get();
        }
        try(var repo=new RuleRepository(root)){repo.reload().get();assertTrue(repo.neural());assertFalse(repo.ui().animations());assertEquals(.2,repo.ui().x());
            assertEquals(new RuleRepository.HudWidget(true,1,0,2),repo.hud().get("fps"));assertTrue(Files.readString(file).contains("unknown"));
        }
    }
    @Test void wordPiecePreservesRussianCasePunctuationAndUnknownTokens(){
        var vocab=Map.of("[CLS]",2,"[SEP]",3,"[UNK]",1,"Продать",7,"меч",8,"за",9,"руб",10,"##ли",11,"?",12);
        assertArrayEquals(new long[]{2,7,8,9,10,11,12,3},SemanticSearch.tokenize("Продать меч за рубли?",vocab,128));
        assertArrayEquals(new long[]{2,1,3},SemanticSearch.tokenize("продать",vocab,128));
        assertEquals(4,SemanticSearch.tokenize("меч меч меч меч",vocab,4).length);
    }
    @Test void semanticRankingRetainsExactEvidenceButDoesNotReturnUnrelatedLowScores(){
        var commerce=rule("1.1","Продажа за реальные деньги запрещена","");var cheats=rule("2.2","Запрещены читы","");var unrelated=rule("3.3","Не спамить","");
        var ranked=SemanticSearch.rank(Map.of(commerce,.82f,cheats,.33f,unrelated,.1f),List.of(new RuleSearch.Match(cheats,80)));
        assertEquals(commerce,ranked.getFirst().rule());assertTrue(ranked.stream().anyMatch(m->m.rule().equals(cheats)));assertTrue(ranked.stream().noneMatch(m->m.rule().equals(unrelated)));
    }
    @Test void disabledSemanticSearchDoesNotCreateOrDownloadAnything() throws Exception {
        var folder=root.resolve("model");var lexical=List.of(new RuleSearch.Match(rule("1.1","Текст",""),10));
        try(var search=new SemanticSearch(folder)){assertEquals(lexical,search.search(book(),"вопрос",null,lexical).get());assertFalse(Files.exists(folder));}
    }
    @Test void explicitCommerceQueriesCannotBeReplacedWithCheatRules(){
        var commerce=rule("1.1","Запрещены продажи игровых предметов за реальные деньги","");var cheats=rule("2.2","Запрещено использовать читы","");
        assertTrue(RuleSearch.matchesIntent("фанпей",commerce));assertFalse(RuleSearch.matchesIntent("фанпей",cheats));
        assertTrue(RuleSearch.matchesIntent("можно продать меч за рубли?",commerce));assertFalse(RuleSearch.matchesIntent("можно продать меч за рубли?",cheats));
        assertTrue(SemanticSearch.rank(Map.of(commerce,.51f,cheats,.50f),List.of()).isEmpty());
    }
}
