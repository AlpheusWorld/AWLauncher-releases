package ru.aw.assistant.rules;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Official pages are read as data, never executed. Full text is cached locally, not shipped in the mod. */
final class BuiltinRules {
    record Source(String id, String name, List<String> addresses, String url, String kind) {}
    static final List<Source> SOURCES = List.of(
        new Source("spookytime", "SpookyTime", List.of("spookytime.net", "mc.spookytime.net"), "https://vk.com/topic-217621155_49160648", "vk"),
        new Source("reallyworld", "ReallyWorld", List.of("mc.reallyworld.ru", "mc.playrw.ru", "mc.reallyworld.me", "mc.reallyworld.online", "mc.reallyworld.org"),
            "https://f.reallyworld.me/topic/224776-%D0%BD%D0%BE%D0%B2%D0%B0%D1%8F-%D1%80%D0%B5%D0%B4%D0%B0%D0%BA%D1%86%D0%B8%D1%8F-%D0%BF%D1%80%D0%B0%D0%B2%D0%B8%D0%BB-%D0%BF%D1%80%D0%BE%D0%B5%D0%BA%D1%82%D0%B0-reallyworld-%D0%BE%D1%82-26082026/", "forum"),
        new Source("funtime", "FunTime", List.of("funtime.su", "mc.funtime.su"), "https://funtime.su/rules", "html"),
        new Source("mineblaze", "MineBlaze · общие", List.of("mineblaze.net", "mc.mineblaze.net", "play.mineblaze.net"), "https://mineblaze.net/#rules", "mineblaze"),
        new Source("holyworld", "HolyWorld · классика", List.of("holyworld.ru", "hub.holyworld.ru", "mc.holyworld.ru"),
            "https://docs.google.com/document/d/12vLSs2pjWzoU2DOrFM_z4ftv2CVGzCCy4UO5w4W3sDE/edit", "text")
    );
    private static final Pattern NUMBER = Pattern.compile("(?m)^\\h*(\\d{1,2}(?:\\.\\d{1,3}){1,4})\\.?\\h*(?:>\\h*)?(?=\\p{L}|[«\"(])");
    private static final Pattern SECTION = Pattern.compile("(?m)^\\h*(\\d{1,2})\\.\\h+([^\\n]{3,100})$");
    private static final String PENALTY="бан|мут|блокиров|кик|предупрежд|удал|снят|снос|отключ|штраф|изъят|обнул|тюрьм|лишени|заключ|ограничен|навсегда|перманент|наказани|санкци";
    private static final Pattern SANCTION = Pattern.compile("(?iu)\\[([^]\\n]*(?:"+PENALTY+")[^]\\n]*)]|\\(([^)\\n]*(?:"+PENALTY+")[^)\\n]*)\\)|(?:^|\\n)\\h*[-—–]?\\h*((?:Наказание|Санкция|Санкции)[^\\n]*)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final int LIMIT = 4 * 1024 * 1024;

    static RuleBook bundled(Source source) throws IOException {
        try (var stream = BuiltinRules.class.getResourceAsStream("/assets/awassistant/rules/" + source.id() + ".json")) {
            if (stream == null) throw new IOException("Нет памятки: " + source.name());
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, RuleBook.class);
            }
        } catch (RuntimeException invalid) { throw new IOException("Некорректная памятка: " + source.name(), invalid); }
    }
    static RuleBook withShortTitles(Source source, RuleBook book) throws IOException {
        var titles = new HashMap<String, String>();
        for (var rule : bundled(source).rules()) titles.put(rule.number(), rule.title());
        var rules = book.rules().stream().map(rule -> new RuleBook.Rule(rule.number(), titles.getOrDefault(rule.number(), rule.title()),
            rule.category(), rule.description(), rule.sanction().isBlank()?extractSanctions(rule.description()):rule.sanction(), rule.keywords())).toList();
        return new RuleBook(book.id(), book.name(), book.addresses(), book.sourceUrl(), rules);
    }
    static String extractSanctions(String text){
        var values=new LinkedHashSet<String>();var match=SANCTION.matcher(text);
        while(match.find())for(int group=1;group<=3;group++)if(match.group(group)!=null)values.add(match.group(group).strip());
        return String.join("\n",values);
    }

    static CompletableFuture<RuleBook> download(Source source) {
        String url = source.kind().equals("text") ? source.url().replace("/edit", "/export?format=txt") : source.url();
        return download(source, URI.create(url), 0);
    }
    private static CompletableFuture<RuleBook> download(Source source, URI uri, int redirect) {
        if (redirect >= 5 || !allowedRedirect(source, uri))
            return CompletableFuture.failedFuture(new IOException("Неподдерживаемое перенаправление источника"));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("User-Agent", "AWAssistant/0.1.1 (+https://awlauncher.ru)").GET().build();
        return HTTP.sendAsync(request, ignored -> new BoundedBody()).thenCompose(response -> {
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                var location = response.headers().firstValue("Location");
                if (location.isEmpty()) return CompletableFuture.failedFuture(new IOException("Нет адреса перенаправления"));
                return download(source, uri.resolve(location.get()), redirect + 1);
            }
            if (status != 200) return CompletableFuture.failedFuture(new IOException("Источник ответил HTTP " + status));
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            var charset = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w-]+)").matcher(contentType);
            Charset encoding = StandardCharsets.UTF_8;
            if (charset.find()) try { encoding = Charset.forName(charset.group(1)); } catch (IllegalArgumentException ignored) {}
            try { return CompletableFuture.completedFuture(parse(source, new String(response.body(), encoding))); }
            catch (IOException | RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
        });
    }
    static boolean allowedRedirect(Source source, URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals(URI.create(source.url()).getHost()) || source.kind().equals("text") && host.endsWith(".googleusercontent.com");
    }

    static RuleBook parse(Source source, String page) throws IOException {
        String text;
        if (source.kind().equals("vk")) {
            // Public topic responses contain JSON string values, not an executable API response.
            // Only posts authored by the official community, never players' replies.
            var values = Pattern.compile("\"from_id\"\\s*:\\s*-217621155\\s*,\\s*\"id\"\\s*:\\s*\\d+\\s*,\\s*\"text\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*+\")").matcher(page);
            var posts = new LinkedHashSet<String>();
            while (values.find()) {
                try { String value = JsonParser.parseString(values.group(1)).getAsString(); if (NUMBER.matcher(value).find()) posts.add(value); }
                catch (RuntimeException ignored) {}
            }
            text = String.join("\n", posts);
        } else if (source.kind().equals("text")) text = page;
        else {
            if (source.kind().equals("mineblaze")) {
                int start = page.indexOf("id=\"general-rules-tab-pane\"");
                int end = page.indexOf("id=\"grief-rules-tab-pane\"", start);
                if (start < 0 || end < 0) throw new IOException("Изменилась разметка правил MineBlaze");
                page = page.substring(page.lastIndexOf('<', start), page.lastIndexOf('<', end));
            } else if (source.kind().equals("html")) {
                int start = page.indexOf("class=\"rule_box");
                int end = page.indexOf("footer-container", start);
                if (start < 0 || end < 0) throw new IOException("Изменилась разметка правил FunTime");
                page = page.substring(page.lastIndexOf('<', start), page.lastIndexOf('<', end));
            } else if (source.kind().equals("forum")) {
                int start = page.indexOf("data-role=\"commentContent\"");
                if (start < 0) throw new IOException("Изменилась разметка форума ReallyWorld");
                page = page.substring(page.lastIndexOf('<', start));
            }
            text = htmlText(page);
        }
        text = text.replace('\u00A0', ' ').replace("\r", "").replaceAll("[\\t ]+", " ").replaceAll("\\n[\\h\\n]+", "\n").strip();
        var starts = new ArrayList<Integer>(); var ends = new ArrayList<Integer>(); var numbers = new ArrayList<String>();
        var matcher = NUMBER.matcher(text);
        while (matcher.find()) { starts.add(matcher.start()); ends.add(matcher.end()); numbers.add(matcher.group(1)); }
        var rules = new LinkedHashMap<String, RuleBook.Rule>();
        String category = "Общие правила";
        var headings = SECTION.matcher(text);
        int nextHeading = headings.find() ? headings.start() : Integer.MAX_VALUE;
        for (int i = 0; i < starts.size(); i++) {
            while (nextHeading < starts.get(i)) {
                category = headings.group(2).replaceAll(":$", "").strip();
                nextHeading = headings.find() ? headings.start() : Integer.MAX_VALUE;
            }
            String body = text.substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length()).strip();
            var section = SECTION.matcher(body); if (section.find()) body = body.substring(0, section.start()).strip();
            if (body.isBlank() || body.length() > 24000) continue;
            String first = body.lines().findFirst().orElse(body).strip();
            int sentence = first.indexOf(". "); if (sentence > 8) first = first.substring(0, sentence);
            first = first.replaceAll("\\[[^]]*]|\\([^)]*(?i:бан|мут|наказание)[^)]*\\)", "").strip();
            String title = first.length() > 88 ? first.substring(0, 85).stripTrailing() + "…" : first;
            if (title.isBlank()) title = "Пункт " + numbers.get(i);
            var sanctions = new LinkedHashSet<String>(); var penalties = SANCTION.matcher(body);
            while (penalties.find()) for (int group = 1; group <= 3; group++) if (penalties.group(group) != null) sanctions.add(penalties.group(group).strip());
            // Duplicate headings on official pages (e.g. FunTime 5.9) must not hide the actual rule.
            var previous = rules.get(numbers.get(i));
            if (previous != null) { body = previous.description() + "\n" + body; title = previous.title(); if(!previous.sanction().isBlank())sanctions.add(previous.sanction()); }
            rules.put(numbers.get(i), new RuleBook.Rule(numbers.get(i), title, category, body, String.join("\n", sanctions), List.of()));
        }
        if (rules.size() < 10) throw new IOException("Полный текст правил не найден; сохранена памятка");
        return withShortTitles(source, new RuleBook(source.id(), source.name(), source.addresses(), source.url(), List.copyOf(rules.values())));
    }

    static String htmlText(String html) throws IOException {
        var text = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private boolean skip;
            private void line() { if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n'); }
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) skip = true;
                if (tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI || tag.toString().matches("h[1-6]")) line();
            }
            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) skip = false;
                if (tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI || tag.toString().matches("h[1-6]")) line();
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) { if (tag == HTML.Tag.BR) line(); }
            @Override public void handleText(char[] data, int pos) { if (!skip) text.append(data); }
        }, true);
        return text.toString();
    }

    static void writeCache(Path target, RuleBook book) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), "rules-", ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(book), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > LIMIT - bytes.size()) { subscription.cancel(); result.completeExceptionally(new IOException("Источник правил больше 4 МБ")); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
