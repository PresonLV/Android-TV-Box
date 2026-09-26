package app.jianxia.core.parser

import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodClass
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

object MacCmsXmlParser {
    fun parse(raw: String, sourceKey: String, sourceName: String, api: String): VodPage {
        val document = Jsoup.parse(cleanDocument(raw), "", Parser.xmlParser())
        if (document.select("video").isEmpty() && document.select("class").isEmpty() && document.select("list").isEmpty()) {
            throw IllegalArgumentException("不是苹果 CMS XML")
        }
        val list = document.selectFirst("list")
        val classes = document.select("ty").mapNotNull { ty ->
            val id = ty.attr("id").ifBlank { return@mapNotNull null }
            val name = ty.text().trim().ifBlank { return@mapNotNull null }
            VodClass(id = id, name = name)
        }
        val items = document.select("video").mapNotNull { video -> video.toItem(sourceKey, sourceName, api) }
        return VodPage(
            page = list?.attr("page")?.toIntOrNull() ?: 1,
            pageCount = list?.attr("pagecount")?.toIntOrNull() ?: 1,
            total = list?.attr("recordcount")?.toIntOrNull() ?: items.size,
            classes = classes,
            items = items,
        )
    }

    private fun Element.toItem(sourceKey: String, sourceName: String, api: String): VodItem? {
        val title = childText("name") ?: return null
        val lines = select("dl > dd").mapNotNull { dd ->
            val flag = dd.attr("flag").ifBlank { "默认线路" }
            val episodes = parseEpisodes(dd.text())
            if (episodes.isEmpty()) null else app.jianxia.core.model.PlayLine(flag, episodes)
        }
        return VodItem(
            sourceKey = sourceKey,
            sourceName = sourceName,
            api = api,
            siteKind = SiteKind.MACCMS_XML,
            id = childText("id").orEmpty(),
            title = title,
            year = childText("year"),
            pic = normalizePic(childText("pic")),
            typeName = childText("type"),
            remarks = childText("note"),
            area = childText("area"),
            actor = childText("actor"),
            director = childText("director"),
            content = stripHtml(childText("des") ?: childText("content")),
            lines = disambiguateLineNames(lines),
        )
    }

    private fun Element.childText(tag: String): String? =
        selectFirst(tag)?.text()?.trim()?.takeIf { it.isNotEmpty() }
}
