package app.jianxia.core.spider

import app.jianxia.core.parser.resolveAgainst
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

private val TAGS = setOf(
    "a", "img", "div", "li", "span", "p", "ul", "ol", "body", "em", "strong",
    "h1", "h2", "h3", "h4", "h5", "video", "source", "script", "table", "tr",
    "td", "th", "section", "article", "header", "footer", "nav", "input",
    "form", "option", "select", "label", "i", "b", "dl", "dt", "dd", "figure",
    "meta", "link", "title", "head", "html",
)

/** 用 jsoup 实现常见的 `选择器&&属性` 规则。Text / Html 取文本或内部 HTML。 */
object HtmlRules {
    fun pdfh(html: String, rule: String): String = first(html, rule).orEmpty()

    fun pdfa(html: String, rule: String): List<String> {
        val (selectors, attr) = split(rule)
        if (selectors.isEmpty() && attr == null) return emptyList()
        return nodes(html, selectors).map { element ->
            if (attr == null) element.outerHtml() else read(element, attr)
        }
    }

    fun pd(html: String, rule: String, base: String): String {
        val value = pdfh(html, rule).trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) return value
        return joinUrl(base, value)
    }

    fun joinUrl(base: String, ref: String): String {
        val raw = ref.trim()
        if (raw.isEmpty()) return base
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
        if (raw.startsWith("//")) {
            val scheme = if (base.startsWith("http://")) "http:" else "https:"
            return scheme + raw
        }
        val root = if (base.startsWith("http://") || base.startsWith("https://")) base else return raw
        return resolveAgainst(root, raw).ifBlank { raw }
    }

    private fun first(html: String, rule: String): String? {
        val (selectors, attr) = split(rule)
        val element = nodes(html, selectors).firstOrNull() ?: return if (attr == null) "" else ""
        return if (attr == null) element.text() else read(element, attr)
    }

    private fun nodes(html: String, selectors: List<String>): List<Element> {
        val document = Jsoup.parse(html)
        var current = listOf<Element>(document)
        if (selectors.isEmpty()) return current
        for (selector in selectors) {
            current = current.flatMap { element ->
                runCatching { element.select(selector) }.getOrDefault(emptyList())
            }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    private fun read(element: Element, attr: String): String = when {
        attr.equals("Text", true) -> element.text()
        attr.equals("Html", true) -> element.html()
        else -> element.attr(attr)
    }

    private fun split(rule: String): Pair<List<String>, String?> {
        val bits = rule.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        if (bits.isEmpty()) return emptyList<String>() to null
        if (bits.size == 1) return bits to null
        val last = bits.last()
        return if (isSelector(last)) bits to null else bits.dropLast(1) to last
    }

    private fun isSelector(token: String): Boolean {
        if (token.equals("Text", true) || token.equals("Html", true)) return false
        if (token.any { it in ".:#>[ " } || token.contains("[")) return true
        return token.lowercase() in TAGS
    }
}
