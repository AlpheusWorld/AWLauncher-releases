package ru.aw.launcher.nativeui;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** Reuses the launcher's CommonMark sanitizer and SVG rasterizer for the native window. */
public final class ProjectMarkup {
    private static final int MAX_INPUT = 16 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        byte[] input = System.in.readNBytes(MAX_INPUT + 1);
        if (input.length > MAX_INPUT) throw new IllegalArgumentException("Project media is too large");
        if (args.length > 0 && args[0].equals("svg")) {
            rasterize(input);
            return;
        }
        String base = args.length > 0 ? args[0] : "https://modrinth.com";
        List<Element> elements = ru.aw.launcher.ui.components.ProjectDescription.INSTANCE.parse(
                new String(input, StandardCharsets.UTF_8), base);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i != 0) json.append(',');
            node(json, elements.get(i), 0);
        }
        json.append(']');
        System.out.write(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void node(StringBuilder output, Node node, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Project description is too deeply nested");
        output.append("{\"tag\":");
        quote(output, node instanceof Element element ? element.tagName() : "#text");
        output.append(",\"text\":");
        quote(output, node instanceof TextNode text ? text.text()
                : node instanceof Element element && element.tagName().equals("pre") ? element.wholeText() : "");
        output.append(",\"attrs\":{");
        boolean separator = false;
        if (node instanceof Element element) {
            for (String name : new String[]{"href", "src", "alt", "align", "width", "height"}) {
                if (!element.hasAttr(name)) continue;
                if (separator) output.append(',');
                quote(output, name); output.append(':'); quote(output, element.attr(name)); separator = true;
            }
        }
        output.append("},\"children\":[");
        for (int i = 0; i < node.childNodeSize(); i++) {
            if (i != 0) output.append(',');
            node(output, node.childNode(i), depth + 1);
        }
        output.append("]}");
    }

    private static void quote(StringBuilder output, String text) {
        output.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> { if (c < 32) output.append(String.format("\\u%04x", (int)c)); else output.append(c); }
            }
        }
        output.append('"');
    }

    private static void rasterize(byte[] bytes) throws Exception {
        org.jetbrains.skiko.Library.INSTANCE.load();
        try (org.jetbrains.skia.Data data = org.jetbrains.skia.Data.Companion.makeFromBytes(bytes, 0, bytes.length);
             org.jetbrains.skia.svg.SVGDOM document = new org.jetbrains.skia.svg.SVGDOM(data)) {
            var root = document.getRoot();
            if (root == null) throw new IllegalArgumentException("SVG has no root");
            float width = root.getWidth().getValue();
            float height = root.getHeight().getValue();
            var view = root.getViewBox();
            if (!(width > 0)) width = view != null ? view.getWidth() : 1280;
            if (!(height > 0)) height = view != null ? view.getHeight() : 1280;
            float ratio = Math.min(1f, 1280f / Math.max(width, height));
            int w = Math.max(1, (int)(width * ratio)), h = Math.max(1, (int)(height * ratio));
            try (var surface = org.jetbrains.skia.Surface.Companion.makeRasterN32Premul(w, h)) {
                document.setContainerSize(w, h); document.render(surface.getCanvas());
                try (var image = surface.makeImageSnapshot();
                     var encoded = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100)) {
                    if (encoded == null) throw new IllegalArgumentException("Could not encode SVG");
                    System.out.write(encoded.getBytes());
                }
            }
        }
    }
}
