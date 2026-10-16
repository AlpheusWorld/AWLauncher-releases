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
    public record RuleChange(String number, RuleBook.Rule before, RuleBook.Rule after) {}
    public record Revision(long at, List<RuleChange> changes) {}
    public record SourceHistory(long checkedAt, long changedAt, List<Revision> revisions) {}
    public record Library(Map<String, List<String>> favorites, Map<String, List<String>> queries) {}
    public record HudWidget(boolean enabled, double x, double y, double scale) {
        public HudWidget {
            x=finite(x,0,1,.02);y=finite(y,0,1,.05);scale=finite(scale,.5,2,1);
        }
        private static double finite(double value,double min,double max,double fallback){return Double.isFinite(value)?Math.clamp(value,min,max):fallback;}
    }
    private volatile Library library=new Library(Map.of(),Map.of());
    private volatile boolean libraryLoaded;
    private volatile Map<String,HudWidget> hud=defaultHud();
    private volatile boolean neural;
    public boolean neural(){return neural;}
    public Library library(){return library;}
    public Map<String,HudWidget> hud(){return hud;}
    public void previewHud(Map<String,HudWidget> widgets){hud=Collections.unmodifiableMap(new LinkedHashMap<>(widgets));}
    public static Map<String,HudWidget> defaultHud(){
        var result=new LinkedHashMap<String,HudWidget>();int i=0;
        for(String id:List.of("fps","ping","coordinates","armor","effects"))result.put(id,new HudWidget(false,.02,.04+i++*.09,1));
        return Collections.unmodifiableMap(result);
    }
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    private final Map<String,SourceHistory> histories=new ConcurrentHashMap<>();
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
            !json.has("smartSearch")||json.get("smartSearch").getAsBoolean());
            neural=json.has("neuralSearch")&&json.get("neuralSearch").getAsBoolean();
            var widgets=new LinkedHashMap<>(defaultHud());
            if(json.has("hud"))for(var id:widgets.keySet())if(json.getAsJsonObject("hud").has(id))
                widgets.put(id,JSON.fromJson(json.getAsJsonObject("hud").get(id),HudWidget.class));
            hud=Collections.unmodifiableMap(widgets);uiLoaded=true; }
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
    private static void writeJson(Path file,Object value) throws IOException {
        Files.createDirectories(file.getParent());var temporary=Files.createTempFile(file.getParent(),"aw-",".tmp");
        try { Files.writeString(temporary,JSON.toJson(value),StandardCharsets.UTF_8);
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    public CompletableFuture<Void> saveHud(Map<String,HudWidget> widgets){
        var copy=new LinkedHashMap<>(defaultHud());copy.replaceAll((id,value)->widgets.getOrDefault(id,value));hud=Collections.unmodifiableMap(copy);
        return CompletableFuture.runAsync(()->{try{var json=readUiJson();json.add("hud",JSON.toJsonTree(copy));writeJson(uiFile(),json);}catch(IOException e){throw new CompletionException(e);}},io);
    }
    public CompletableFuture<Void> saveNeural(boolean enabled){
        neural=enabled;
        return CompletableFuture.runAsync(()->{try{var json=readUiJson();json.addProperty("neuralSearch",enabled);writeJson(uiFile(),json);}catch(IOException e){throw new CompletionException(e);}},io);
    }
    public boolean favorite(String server,String number){return library.favorites().getOrDefault(server,List.of()).contains(number);}
    public CompletableFuture<Void> toggleFavorite(String server,String number){
        var favorites=new LinkedHashMap<>(library.favorites());var values=new ArrayList<>(favorites.getOrDefault(server,List.of()));
        if(!values.remove(number)&&values.size()<500)values.add(number);favorites.put(server,List.copyOf(values));
        library=new Library(Map.copyOf(favorites),library.queries());return saveLibrary(library);
    }
    public CompletableFuture<Void> rememberQuery(String server,String query){
        query=query.strip();if(query.isEmpty()||query.length()>300||server.isBlank())return CompletableFuture.completedFuture(null);
        var queries=new LinkedHashMap<>(library.queries());var values=new ArrayList<>(queries.getOrDefault(server,List.of()));
        values.remove(query);values.addFirst(query);queries.put(server,List.copyOf(values.subList(0,Math.min(30,values.size()))));
        library=new Library(library.favorites(),Map.copyOf(queries));return saveLibrary(library);
    }
    public CompletableFuture<Void> clearQueries(String server){var queries=new LinkedHashMap<>(library.queries());queries.remove(server);library=new Library(library.favorites(),Map.copyOf(queries));return saveLibrary(library);}
    private CompletableFuture<Void> saveLibrary(Library snapshot){libraryLoaded=true;return CompletableFuture.runAsync(()->{try{writeJson(folder.getParent().resolve("library.json"),snapshot);}catch(IOException e){throw new CompletionException(e);}},io);}
    public SourceHistory history(String server){
        var cached=histories.get(server);return cached==null?new SourceHistory(0,0,List.of()):cached;
    }
    private void loadHistory(String id) throws IOException {
        try {var file=folder.getParent().resolve("rule-changes").resolve(id+".json");
            if(Files.isRegularFile(file)&&Files.size(file)<16_777_216){var saved=JSON.fromJson(Files.readString(file),SourceHistory.class);
                if(saved!=null&&saved.revisions()!=null&&saved.revisions().size()<=10&&saved.revisions().stream().allMatch(r->r!=null&&r.changes()!=null&&r.changes().stream().allMatch(c->c!=null&&c.number()!=null&&(c.before()!=null||c.after()!=null))))histories.put(id,saved);}
        }catch(IOException|RuntimeException invalid){histories.remove(id);}
    }
    public static List<RuleChange> differences(RuleBook before,RuleBook after){
        if(before==null)return List.of();var old=new LinkedHashMap<String,RuleBook.Rule>();before.rules().forEach(rule->old.put(rule.number(),rule));
        var result=new ArrayList<RuleChange>();
        for(var rule:after.rules()){var previous=old.remove(rule.number());if(!Objects.equals(previous,rule))result.add(new RuleChange(rule.number(),previous,rule));}
        old.values().forEach(rule->result.add(new RuleChange(rule.number(),rule,null)));return List.copyOf(result);
    }
    void recordCheck(RuleBook before,RuleBook after,long at) throws IOException {
        var previous=history(after.id());var changes=differences(before,after);var revisions=new ArrayList<>(previous.revisions());
        if(!changes.isEmpty())revisions.addFirst(new Revision(at,changes));
        while(revisions.size()>10)revisions.removeLast();
        var updated=new SourceHistory(at,changes.isEmpty()?previous.changedAt():at,List.copyOf(revisions));
        while(JSON.toJson(updated).length()>8_000_000&&revisions.size()>1){revisions.removeLast();updated=new SourceHistory(at,updated.changedAt(),List.copyOf(revisions));}
        writeJson(folder.getParent().resolve("rule-changes").resolve(after.id()+".json"),updated);histories.put(after.id(),updated);
    }
    private Path cacheFolder() { return folder.getParent().resolve("official-cache"); }
    private volatile boolean sourcesChecked;
    private volatile Set<String> importedIds=Set.of();
    public boolean refreshNeeded() { return !sourcesChecked; }
    private CompletableFuture<Snapshot> refreshing;
    public synchronized CompletableFuture<Snapshot> refresh() {
        if (refreshing != null && !refreshing.isDone()) return refreshing;
        var failures = new ConcurrentLinkedQueue<String>();
        var requests = new ArrayList<CompletableFuture<Void>>();
        boolean initial = !sourcesChecked;
        for (var source : BuiltinRules.SOURCES) {
            if(importedIds.contains(source.id()))continue;
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
                    if (book != null) try {
                        RuleBook previous=null;if(Files.isRegularFile(cache))try{previous=RuleBook.read(cache);}catch(IOException ignored){}
                        recordCheck(previous,book,System.currentTimeMillis());BuiltinRules.writeCache(cache, book);
                    }
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
            try {
                var file=folder.getParent().resolve("library.json");
                if(!libraryLoaded){if(Files.exists(file)){if(Files.size(file)>1_048_576)throw new IOException("История помощника слишком большая");
                    var saved=JSON.fromJson(Files.readString(file),Library.class);
                    if(saved!=null)library=new Library(saved.favorites()==null?Map.of():Map.copyOf(saved.favorites()),saved.queries()==null?Map.of():Map.copyOf(saved.queries()));}libraryLoaded=true;}
                for(var source:BuiltinRules.SOURCES)loadHistory(source.id());
            }catch(IOException|RuntimeException failure){errors.add("Не удалось прочитать историю помощника");}
            try (var files = Files.list(folder)) {
                for (var path : files.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().limit(100).toList()) {
                    try { var book = RuleBook.read(path);loadHistory(book.id()); if (books.putIfAbsent(book.id(), book) != null) errors.add("Повторяется ID сервера: " + book.id()); }
                    catch (IOException failure) { errors.add(failure.getMessage()); }
                }
            }
            importedIds=Set.copyOf(books.keySet());
            for (var source : BuiltinRules.SOURCES) {
                // User imports take priority; refreshing official sources never replaces them.
                if (books.containsKey(source.id())) continue;
                RuleBook book = null; var cache = cacheFolder().resolve(source.id() + ".json");
                if (Files.isRegularFile(cache)) try {
                    var cached = RuleBook.read(cache);
                    if (cached.id().equals(source.id()) && cached.sourceUrl().equals(source.url())) book = BuiltinRules.withShortTitles(source, cached);
                    if(book!=null)histories.putIfAbsent(source.id(),new SourceHistory(Files.getLastModifiedTime(cache).toMillis(),0,List.of()));
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
                RuleBook previous=null;if(Files.isRegularFile(target))previous=RuleBook.read(target);
                var temp = Files.createTempFile(folder, "import-", ".tmp");
                try { Files.writeString(temp, new com.google.gson.Gson().toJson(book), StandardCharsets.UTF_8);
                    try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
                    recordCheck(previous,book,System.currentTimeMillis()); }
                finally { Files.deleteIfExists(temp); }
            }
        } catch (IOException failure) { throw new CompletionException(failure); }
    }, io); }
    @Override public void close() { io.shutdown(); }
}
