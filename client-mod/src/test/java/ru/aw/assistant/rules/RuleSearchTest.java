package ru.aw.assistant.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuleSearchTest {
    @Test void commerceSlangAndRussianCasesFindTheRuleNotUnrelatedMentions() {
        var book=new RuleBook("test","Test",List.of(),"",List.of(
            new RuleBook.Rule("1.5","Игровой ник","Аккаунт","Нельзя использовать название читов в нике","Предупреждение",List.of()),
            new RuleBook.Rule("2.2","Читы и запрещённые программы","Игра","Использование читов запрещено","Бан 14 дней",List.of()),
            new RuleBook.Rule("2.4","Коммерческая деятельность","Игра","Нельзя продавать игровые ценности за реальную валюту","Бан",List.of()),
            new RuleBook.Rule("3.1","Рынок","Игра","Разрешена продажа ресурсов за игровые монеты","",List.of())));
        var search=new RuleSearch(book);
        assertEquals("2.2",search.find("читов",null).getFirst().rule().number());
        assertEquals("2.2",search.find("читы",null).getFirst().rule().number());
        for(String term:List.of("фанпей","фанпэй","FunPay","можно продавать на фанпей")) {
            assertEquals("2.4",search.find(term,null).getFirst().rule().number());
            assertFalse(search.find(term,null).stream().anyMatch(m->m.rule().number().equals("3.1")));
        }
        assertTrue(search.find("фанпей",null,false).isEmpty());
        assertTrue(search.find("фанпей","Аккаунт").isEmpty());
        assertEquals("2.2",search.find("2.2",null).getFirst().rule().number());
    }
    @Test void directCheatRuleRanksAboveShortInspectionRules(){
        var main=new RuleBook.Rule("2.4","Нечестное ПО","Игра","Запрещены читы. "+"Подробные условия использования программ. ".repeat(40),"Бан 30 дней",List.of("читы","xray"));
        var inspection=new RuleBook.Rule("2.5","Правила проверки на читы","Игра","Порядок проверки на читы","",List.of());
        var index=new RuleSearch(new RuleBook("test","Test",List.of(),"",List.of(main,inspection)));
        assertEquals("2.4",index.find("читы",null).getFirst().rule().number());
        assertEquals("2.5",index.find("проверка на читы",null).getFirst().rule().number());
    }
    @TempDir Path temporary;
    private RuleBook book(String id) { return new RuleBook(id,"Test",List.of(),"",List.of(
        new RuleBook.Rule("2.1","Запрещённые модификации","Игра","Использование читов","Блокировка",List.of("читы","софт")),
        new RuleBook.Rule("3.2","Общение","Чат","Спам и реклама читов","Предупреждение",List.of("чат")))); }
    @Test void russianSearchRanksKeywordsAndPreservesServerIsolation() {
        var index=new RuleSearch(book("server-a"));
        assertEquals("2.1",index.find("ЧИТЫ",null).getFirst().rule().number());
        assertEquals("2.1",index.find("2.1",null).getFirst().rule().number());
        assertTrue(index.find("софт","Чат").isEmpty());
        assertTrue(new RuleSearch(new RuleBook("empty","Empty",List.of(),"",List.of())).find("читы",null).isEmpty());
        assertEquals("елка",RuleSearch.normalize("ЁЛКА"));
    }
    @Test void importsValidateBeforeReplacingAnExistingPack() throws Exception {
        try(var repository=new RuleRepository(temporary)) {
            var good=temporary.resolve("good.json");Files.writeString(good,new com.google.gson.Gson().toJson(book("server-a")));
            repository.importFiles(List.of(good)).get();
            var target=repository.folder().resolve("server-a.json");String before=Files.readString(target);
            Files.writeString(good,"{broken}");
            assertThrows(Exception.class,()->repository.importFiles(List.of(good)).get());
            assertEquals(before,Files.readString(target));
            assertTrue(repository.reload().get().books().stream().anyMatch(b->b.id().equals("server-a")));
        }
    }
    @Test void duplicateNumbersAndUnsafeServerIdsAreRejected() {
        var rule=book("a").rules().getFirst();
        assertThrows(IllegalArgumentException.class,()->new RuleBook("../bad","Bad",List.of(),"",List.of(rule)));
        assertThrows(IllegalArgumentException.class,()->new RuleBook("bad","Bad",List.of(),"",List.of(rule,rule)));
    }
}
