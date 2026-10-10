package ru.aw.assistant.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuleSearchTest {
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
