package ru.aw.assistant.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class RuleRepository implements AutoCloseable {
    public record Snapshot(List<RuleBook> books, List<String> errors) {}
    public record UiPreferences(double x,double y,boolean animations,boolean blur,boolean smartSearch) {
        public UiPreferences(double x,double y,boolean animations,boolean blur){this(x,y,animations,blur,true);}
        public UiPreferences { x=Double.isFinite(x)?Math.max(0,Math.min(1,x)):.5;y=Double.isFinite(y)?Math.max(0,Math.min(1,y)):.05; }
    }
    private volatile UiPreferences ui=new UiPreferences(.5,.05,true,true);
    private volatile boolean uiLoaded;
    private final Path folder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, "AWAssistant-rules"); thread.setDaemon(true); return thread; });
    public RuleRepository(Path config) { folder = config.resolve("awassistant/rules"); }
    public Path folder() { return folder; }
    public UiPreferences ui() { return ui; }
    private Path uiFile() { return folder.getParent().resolve("ui.json"); }
    private com.google.gson.JsonObject readUiJson() throws IOException {
        var file=uiFile();if(!Files.exists(file))return new com.google.gson.JsonObject();
        if(Files.size(file)>65536)throw new IOException("Файл настроек помощника слишком большой");
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject(); }
        catch(RuntimeException invalid){throw new IOException("Не удалось прочитать настройки помощника",invalid);}
    }
    private void loadUi() throws IOException {
        if(uiLoaded)return;var json=readUiJson();
        try { ui=new UiPreferences(json.has("x")?json.get("x").getAsDouble():.5,json.has("y")?json.get("y").getAsDouble():.05,
            !json.has("animations")||json.get("animations").getAsBoolean(),!json.has("blur")||json.get("blur").getAsBoolean(),
            !json.has("smartSearch")||json.get("smartSearch").getAsBoolean());uiLoaded=true; }
        catch(RuntimeException invalid){throw new IOException("Некорректные настройки помощника",invalid);}
    }
    public CompletableFuture<Void> saveUi(UiPreferences preferences) {
        ui=preferences;uiLoaded=true;
        return CompletableFuture.runAsync(()-> {
            try {
                Files.createDirectories(folder.getParent());var json=readUiJson();
                json.addProperty("x",preferences.x());json.addProperty("y",preferences.y());json.addProperty("animations",preferences.animations());json.addProperty("blur",preferences.blur());
                json.addProperty("smartSearch",preferences.smartSearch());
                var temporary=Files.createTempFile(folder.getParent(),"ui-",".tmp");
                try { Files.writeString(temporary,new com.google.gson.Gson().toJson(json),StandardCharsets.UTF_8);
                    try { Files.move(temporary,uiFile(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                    catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,uiFile(),StandardCopyOption.REPLACE_EXISTING);} }
                finally {Files.deleteIfExists(temporary);}
                ui=preferences;uiLoaded=true;
            } catch(IOException failure){throw new CompletionException(failure);}
        },io);
    }
    public CompletableFuture<Snapshot> reload() { return CompletableFuture.supplyAsync(this::readLocal, io); }
    private Path cacheFolder() { return folder.getParent().resolve("official-cache"); }
    private volatile boolean sourcesChecked;
    public boolean refreshNeeded() { return !sourcesChecked; }
    private CompletableFuture<Snapshot> refreshing;
    public synchronized CompletableFuture<Snapshot> refresh() {
        if (refreshing != null && !refreshing.isDone()) return refreshing;
        var failures = new ConcurrentLinkedQueue<String>();
        var requests = new ArrayList<CompletableFuture<Void>>();
        boolean initial = !sourcesChecked;
        for (var source : BuiltinRules.SOURCES) {
            var cache = cacheFolder().resolve(source.id() + ".json");
            var request = CompletableFuture.supplyAsync(() -> {
                try {
                    if (!initial || !Files.isRegularFile(cache) || System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() >= 24 * 60 * 60 * 1000L) return false;
                    var saved = RuleBook.read(cache);
                    return saved.id().equals(source.id()) && saved.sourceUrl().equals(source.url()) && saved.rules().size() >= 10;
                }
                catch (IOException ignored) { return false; }
            }, io).thenCompose(fresh -> fresh ? CompletableFuture.<RuleBook>completedFuture(null) : BuiltinRules.download(source))
                .thenAcceptAsync(book -> {
                    if (book != null) try { BuiltinRules.writeCache(cache, book); }
                    catch (IOException failure) { throw new CompletionException(failure); }
                }, io).exceptionally(failure -> {
                    failures.add(source.name() + ": сайт недоступен, сохранены прежние правила"); return null;
                });
            requests.add(request);
        }
        refreshing = CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).thenApplyAsync(unused -> {
            sourcesChecked = true;
            var local = readLocal(); failures.addAll(local.errors());
            return new Snapshot(local.books(), List.copyOf(failures));
        }, io);
        return refreshing;
    }
    private Snapshot readLocal() {
        var books = new LinkedHashMap<String, RuleBook>(); var errors = new ArrayList<String>();
        try {
            Files.createDirectories(folder);
            try { loadUi(); } catch(IOException failure){errors.add(failure.getMessage());}
            try (var files = Files.list(folder)) {
                for (var path : files.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().limit(100).toList()) {
                    try { var book = RuleBook.read(path); if (books.putIfAbsent(book.id(), book) != null) errors.add("Повторяется ID сервера: " + book.id()); }
                    catch (IOException failure) { errors.add(failure.getMessage()); }
                }
            }
            for (var source : BuiltinRules.SOURCES) {
                // User imports take priority; refreshing official sources never replaces them.
                if (books.containsKey(source.id())) continue;
                RuleBook book = null; var cache = cacheFolder().resolve(source.id() + ".json");
                if (Files.isRegularFile(cache)) try {
                    var cached = RuleBook.read(cache);
                    if (cached.id().equals(source.id()) && cached.sourceUrl().equals(source.url())) book = BuiltinRules.withShortTitles(source, cached);
                } catch (IOException failure) { errors.add(source.name() + ": сохранённый набор повреждён"); }
                if (book == null) try { book = BuiltinRules.bundled(source); } catch (IOException failure) { errors.add(failure.getMessage()); }
                if (book != null) books.put(source.id(), book);
            }
        } catch (IOException failure) { errors.add("Не удалось открыть папку правил: " + failure.getMessage()); }
        return new Snapshot(List.copyOf(books.values()), List.copyOf(errors));
    }
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
