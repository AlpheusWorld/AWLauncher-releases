package ru.aw.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import ru.aw.launcher.core.Shell
import ru.aw.launcher.ui.theme.AWColors

internal object ProjectDescription {
    fun parse(markdown: String, baseUrl: String = "https://modrinth.com"): List<Element> {
        val extensions = listOf(TablesExtension.create())
        val document = Parser.builder().extensions(extensions).build().parse(markdown)
        val html = HtmlRenderer.builder().extensions(extensions).sanitizeUrls(true).build().render(document)
        val allowed = Safelist.relaxed().addAttributes(":all", "align").addAttributes("img", "width", "height")
            .removeProtocols("img", "src", "http").removeProtocols("a", "href", "ftp", "mailto")
        return Jsoup.parseBodyFragment(Jsoup.clean(html, baseUrl, allowed)).body().children().toList()
    }
}

@Composable
internal fun DescriptionBlock(element: Element, image: @Composable (String, String?) -> Unit) {
    when (element.tagName()) {
        "img" -> DescriptionImage(element, image)
        "div", "section", "article", "details", "summary", "tbody", "thead" ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                element.childNodes().forEach { node ->
                    if (node is Element) DescriptionBlock(node, image)
                    else if (node is TextNode && node.text().isNotBlank()) DescriptionText(listOf(node))
                }
            }
        "ul", "ol" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
            element.children().forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text(if (element.tagName() == "ol") "${index + 1}." else "•", color = AWColors.TextSoft)
                    Column(Modifier.weight(1f)) { DescriptionBlock(item, image) }
                }
            }
        }
        "table" -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            element.select("tr").forEach { row ->
                Row {
                    row.children().filter { it.tagName() in setOf("td", "th") }.forEach { cell ->
                        Column(Modifier.width(170.dp).background(AWColors.SurfaceHigh).padding(12.dp)) {
                            DescriptionText(cell.childNodes(), bold = cell.tagName() == "th")
                        }
                    }
                }
            }
        }
        "pre" -> SelectionContainer {
            androidx.compose.material3.Text(
                element.wholeText().trimEnd(),
                modifier = Modifier.fillMaxWidth().background(AWColors.SurfaceHigh).padding(14.dp)
                    .horizontalScroll(rememberScrollState()),
                color = AWColors.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
        "blockquote" -> Column(Modifier.background(AWColors.SurfaceHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            element.children().forEach { DescriptionBlock(it, image) }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val level = element.tagName().takeIf { it.matches(Regex("h[1-6]")) }?.last()?.digitToInt() ?: 0
            DescriptionText(element.childNodes(), level)
            val images = element.select("img")
            if (images.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEach { img -> DescriptionImage(img, image) }
            } else images.forEach { img ->
                DescriptionImage(img, image)
            }
            element.children().filter { it.tagName() in setOf("ul", "ol", "pre", "table") }.forEach { DescriptionBlock(it, image) }
        }
    }
}

@Composable
private fun DescriptionImage(element: Element, image: @Composable (String, String?) -> Unit) {
    val url = element.attr("src").takeIf { it.startsWith("https://") } ?: return
    val link = element.parents().firstOrNull { it.tagName() == "a" }?.attr("href")
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    Box(if (link == null) Modifier else Modifier.clickable { Shell.browse(link) }) {
        image(url, element.attr("alt"))
    }
}

@Composable
private fun DescriptionText(nodes: List<Node>, heading: Int = 0, bold: Boolean = false) {
    val accent = AWColors.Accent
    val bodyColor = AWColors.TextSoft
    val text = remember(nodes, accent) {
        buildAnnotatedString {
            fun appendNode(node: Node) {
                if (node is TextNode) { append(node.text()); return }
                if (node !is Element) return
                val tag = node.tagName()
                if (tag in setOf("img", "ul", "ol", "pre", "table")) return
                if (tag == "br") { append('\n'); return }
                val span = when (tag) {
                    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "code" -> SpanStyle(fontFamily = FontFamily.Monospace)
                    "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "a" -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                    else -> SpanStyle()
                }
                pushStyle(span)
                val href = node.attr("href")
                val link = tag == "a" && (href.startsWith("https://") || href.startsWith("http://"))
                if (link) pushLink(LinkAnnotation.Url(href, linkInteractionListener = { Shell.browse(href) }))
                node.childNodes().forEach(::appendNode)
                if (link) pop()
                pop()
            }
            nodes.forEach(::appendNode)
        }
    }
    if (text.isBlank()) return
    val style = when (heading) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3, 4, 5, 6 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.bodyMedium
    }.copy(color = if (heading > 0) AWColors.Text else bodyColor,
        fontWeight = if (heading > 0 || bold) FontWeight.Bold else FontWeight.Normal)
    SelectionContainer {
        androidx.compose.material3.Text(text, style = style, modifier = Modifier.fillMaxWidth())
    }
}
