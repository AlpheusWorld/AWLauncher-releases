package ru.aw.assistant.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class RuleRepository implements AutoCloseable {
    public record Snapshot(List<RuleBook> books, List<String> errors) {}
    private final Path folder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, "AWAssistant-rules"); thread.setDaemon(true); return thread; });
    public RuleRepository(Path config) { folder = config.resolve("awassistant/rules"); }
    public Path folder() { return folder; }
    public CompletableFuture<Snapshot> reload() { return CompletableFuture.supplyAsync(() -> {
        var books = new LinkedHashMap<String, RuleBook>(); var errors = new ArrayList<String>();
        try {
            Files.createDirectories(folder);
            if (!Files.exists(folder.resolve("aresmine.json"))) {
                try (var input = RuleRepository.class.getResourceAsStream("/assets/awassistant/rules/aresmine.json")) {
                    if (input != null) Files.write(folder.resolve("aresmine.json"), input.readAllBytes(), StandardOpenOption.CREATE_NEW);
                }
            }
            try (var files = Files.list(folder)) {
                for (var path : files.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().limit(100).toList()) {
                    try { var book = RuleBook.read(path); if (books.putIfAbsent(book.id(), book) != null) errors.add("Повторяется ID сервера: " + book.id()); }
                    catch (IOException failure) { errors.add(failure.getMessage()); }
                }
            }
        } catch (IOException failure) { errors.add("Не удалось открыть папку правил: " + failure.getMessage()); }
        return new Snapshot(List.copyOf(books.values()), List.copyOf(errors));
    }, io); }
    public CompletableFuture<Void> importFiles(List<Path> files) { return CompletableFuture.runAsync(() -> {
        try {
            Files.createDirectories(folder);
            for (var path : files.stream().limit(100).toList()) {
                var book = RuleBook.read(path);
                var target = folder.resolve(book.id() + ".json");
                var temp = Files.createTempFile(folder, "import-", ".tmp");
                try { Files.writeString(temp, new com.google.gson.Gson().toJson(book), StandardCharsets.UTF_8);
                    try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); } }
                finally { Files.deleteIfExists(temp); }
            }
        } catch (IOException failure) { throw new CompletionException(failure); }
    }, io); }
    @Override public void close() { io.shutdown(); }
}
