package ru.aw.assistant.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinRulesTest {
    @Test void commercialQueriesWorkForEachBundledServerThatHasCommerceRules() throws Exception {
        for(var source:BuiltinRules.SOURCES) {
            if(source.id().equals("reallyworld"))continue;
            var book=BuiltinRules.bundled(source);
            var result=new RuleSearch(book).find("фанпей",null);
            assertFalse(result.isEmpty(),source.name());
            assertTrue(result.getFirst().rule().description().contains("реаль")||result.getFirst().rule().description().contains("денеж")||result.getFirst().rule().description().contains("деньг"),source.name());
        }
    }
    @Test void finesAndConfiscationAreExtractedWithoutInventingDurations() throws Exception {
        var source=BuiltinRules.SOURCES.stream().filter(s->s.id().equals("holyworld")).findFirst().orElseThrow();
        var text=new StringBuilder("1. Правила:\n");
        for(int i=1;i<=10;i++)text.append("1.").append(i).append(" Условие ").append(i).append(i==1?" [Штраф 5000 монет]":i==2?" (Изъятие игровых ценностей)":"").append("\n");
        var parsed=BuiltinRules.parse(source,text.toString());
        assertEquals("Штраф 5000 монет",parsed.rules().get(0).sanction());
        assertEquals("Изъятие игровых ценностей",parsed.rules().get(1).sanction());
        assertTrue(parsed.rules().get(2).sanction().isBlank());
    }
    @TempDir Path temporary;
    @Test void fiveBuiltinsHaveRealNumbersAndDistinctSources() throws Exception {
        try (var repository = new RuleRepository(temporary)) {
            var books = repository.reload().get().books();
            assertEquals(5, books.size());
            for (var book : books) {
                assertFalse(book.rules().isEmpty());
                assertTrue(book.sourceUrl().startsWith("https://"));
                assertFalse(new RuleSearch(book).find(book.rules().getFirst().number(), null).isEmpty());
            }
            assertTrue(books.stream().filter(book -> book.id().equals("funtime")).flatMap(book -> book.rules().stream()).allMatch(rule -> rule.sanction().isBlank()));
        }
    }
    @Test void positionPersistsWithoutRemovingUnknownPreferences() throws Exception {
        var file = temporary.resolve("awassistant/ui.json"); Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"futurePreference\":\"keep\",\"x\":0.1,\"y\":0.2}");
        try (var repository = new RuleRepository(temporary)) {
            repository.reload().get(); assertEquals(.1, repository.ui().x());
            repository.saveUi(new RuleRepository.UiPreferences(1.5, -.2, false, false)).get();
        }
        try (var repository = new RuleRepository(temporary)) {
            repository.reload().get(); assertEquals(1, repository.ui().x()); assertEquals(0, repository.ui().y());
            assertFalse(repository.ui().animations()); assertFalse(repository.ui().blur());
            assertTrue(Files.readString(file).contains("futurePreference"));
        }
    }
    @Test void importedPackOverridesOfficialCacheAndBuiltin() throws Exception {
        try (var repository = new RuleRepository(temporary)) {
            var custom = new RuleBook("funtime", "Мои правила", List.of(), "", List.of(new RuleBook.Rule("99.1", "Своё", "", "Локальный текст", "", List.of())));
            var file = temporary.resolve("import.json"); BuiltinRules.writeCache(file, custom);
            repository.importFiles(List.of(file)).get();
            assertEquals("Мои правила", repository.reload().get().books().stream().filter(book -> book.id().equals("funtime")).findFirst().orElseThrow().name());
        }
    }
    @Test void sourceParserPreservesConditionsAndExtractsOnlyStatedPenalties() throws Exception {
        var source = BuiltinRules.SOURCES.stream().filter(s -> s.id().equals("holyworld")).findFirst().orElseThrow();
        StringBuilder text = new StringBuilder("1. Игровые правила:\n");
        for (int i = 1; i <= 10; i++) text.append("1.").append(i).append(" Тестовое условие ").append(i).append(i == 1 ? " [Бан 3 дня]" : "").append("\nПояснение условия\n");
        var parsed = BuiltinRules.parse(source, text.toString());
        assertEquals(10, parsed.rules().size());
        assertEquals("Бан 3 дня", parsed.rules().getFirst().sanction());
        assertTrue(parsed.rules().getFirst().description().contains("Пояснение"));
        assertTrue(parsed.rules().get(1).sanction().isEmpty());
        assertEquals("Игровые правила", parsed.rules().getFirst().category());
        assertThrows(java.io.IOException.class, () -> BuiltinRules.parse(source, "<html>Access denied</html>"));
    }
    @Test void sourceRedirectsCannotSendRequestsToOtherHostsOrPlainHttp() {
        var source = BuiltinRules.SOURCES.getFirst();
        assertTrue(BuiltinRules.allowedRedirect(source, URI.create(source.url())));
        assertFalse(BuiltinRules.allowedRedirect(source, URI.create("https://example.org/rules")));
        assertFalse(BuiltinRules.allowedRedirect(source, URI.create("http://vk.com/rules")));
        assertFalse(BuiltinRules.allowedRedirect(source, URI.create("https://user@vk.com/rules")));
    }
    @Test void longVkJsonPostsDoNotOverflowTheParserStack() throws Exception {
        var text = new StringBuilder("1. Игровые правила:\n");
        for (int i = 1; i <= 10; i++) text.append("1.").append(i).append(" Тестовый пункт\n").append("Длинное пояснение ".repeat(700)).append('\n');
        String page = "{\"from_id\":-217621155,\"id\":1,\"text\":" + new com.google.gson.Gson().toJson(text.toString()) + "}, {\"from_id\":123,\"id\":2,\"text\":\"1.1 Изменённое игроком правило\"}";
        var parsed = BuiltinRules.parse(BuiltinRules.SOURCES.getFirst(), page);
        assertEquals(10, parsed.rules().size());
        assertTrue(parsed.rules().getFirst().description().length() > 10000);
    }
    @Test void shorteningCachedHeadingsPreservesEveryConditionAndPenalty() throws Exception {
        var source = BuiltinRules.SOURCES.getFirst();
        var original = new RuleBook(source.id(), source.name(), source.addresses(), source.url(), List.of(
            new RuleBook.Rule("2.2", "Длинный заголовок из прежнего кэша", "Игра", "Полный текст с исключениями", "Оригинальное наказание", List.of("поиск"))));
        var rule = BuiltinRules.withShortTitles(source, original).rules().getFirst();
        assertEquals("Читы и нечестные моды", rule.title());
        assertEquals(original.rules().getFirst().description(), rule.description());
        assertEquals(original.rules().getFirst().sanction(), rule.sanction());
        assertEquals(original.rules().getFirst().keywords(), rule.keywords());
    }
}
