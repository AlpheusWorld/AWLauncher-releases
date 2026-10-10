package ru.aw.assistant.rules;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public record RuleBook(String id, String name, List<String> addresses, String sourceUrl, List<Rule> rules) {
    public record Rule(String number, String title, String category, String description, String sanction, List<String> keywords) {
        public Rule {
            number = clean(number, 80); title = clean(title, 240); category = clean(category, 100);
            description = clean(description, 24000); sanction = clean(sanction, 8000);
            keywords = keywords == null ? List.of() : keywords.stream().limit(100).map(s -> clean(s, 100)).toList();
            if (number.isBlank() || title.isBlank() || description.isBlank()) throw new IllegalArgumentException("В правиле нужны номер, название и описание");
        }
    }
    public RuleBook {
        id = clean(id, 80); name = clean(name, 120); sourceUrl = clean(sourceUrl, 2000);
        if (!id.matches("[a-z0-9_-]+") || name.isBlank()) throw new IllegalArgumentException("Некорректное название или ID сервера");
        addresses = addresses == null ? List.of() : addresses.stream().limit(50).map(s -> clean(s, 255)).toList();
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (rules.size() > 10000) throw new IllegalArgumentException("В одном наборе не может быть больше 10000 правил");
        var numbers = new HashSet<String>();
        for (var rule : rules) if (!numbers.add(rule.number())) throw new IllegalArgumentException("Повторяющийся номер правила: " + rule.number());
    }
    private static String clean(String value, int limit) { String text=value==null?"":value.strip(); if(text.length()>limit)throw new IllegalArgumentException("Текст в наборе правил слишком длинный"); return text; }
    public static RuleBook read(Path path) throws IOException {
        if (Files.size(path) > 4 * 1024 * 1024) throw new IOException("JSON правил должен быть меньше 4 МБ");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return Objects.requireNonNull(new Gson().fromJson(reader, RuleBook.class), "Пустой JSON правил");
        } catch (RuntimeException failure) { throw new IOException("Не удалось прочитать " + path.getFileName() + ": " + failure.getMessage(), failure); }
    }
}
