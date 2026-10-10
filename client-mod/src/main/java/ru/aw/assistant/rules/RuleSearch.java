package ru.aw.assistant.rules;

import java.text.Normalizer;
import java.util.*;

public final class RuleSearch {
    public record Match(RuleBook.Rule rule, int score) {}
    private record Entry(RuleBook.Rule rule, String number, String title, String body, String keywords) {}
    private final List<Entry> entries;
    public RuleSearch(RuleBook book) {
        entries = book.rules().stream().map(r -> new Entry(r, normalize(r.number()), normalize(r.title()),
            normalize(r.description() + " " + r.sanction()), normalize(String.join(" ", r.keywords())))).toList();
    }
    public List<Match> find(String query, String category) {
        String normalized = normalize(query);
        if (normalized.isBlank()) return List.of();
        String[] tokens = normalized.split("\\s+");
        var matches = new ArrayList<Match>();
        for (var entry : entries) {
            if (category != null && !category.equals(entry.rule.category())) continue;
            int score = 0;
            boolean all = true;
            for (String token : tokens) {
                int value = entry.number.equals(token) ? 140 : entry.title.equals(token) ? 110 :
                    entry.title.contains(token) ? 70 : entry.keywords.contains(token) ? 45 : entry.body.contains(token) ? 15 : 0;
                if (value == 0) { all = false; break; }
                score += value;
            }
            if (all) matches.add(new Match(entry.rule, score));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed());
        return matches.stream().limit(500).toList();
    }
    public static String normalize(String text) { return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е').strip(); }
}
