package app.jianxia.core.spider

import app.jianxia.core.model.Episode
import app.jianxia.core.model.PlayLine
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.model.VodClass
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.parser.asText
import app.jianxia.core.parser.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class SpiderPlay(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val parse: Int = 0,
    val jx: Int = 0,
    val userAgent: String = "",
    val referer: String = "",
)

object SpiderJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun page(raw: String?, site: VodSiteDef): VodPage {
        val root = objectOf(raw) ?: return VodPage.empty()
        val classes = (root["class"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("type_id", "id") ?: return@mapNotNull null
            VodClass(id, obj.text("type_name", "name") ?: id)
        }
        val items = items(root["list"] as? JsonArray, site)
        return VodPage(
            page = root.text("page")?.toIntOrNull() ?: 1,
            pageCount = root.text("pagecount", "pageCount")?.toIntOrNull() ?: 1,
            total = root.text("total")?.toIntOrNull() ?: items.size,
            classes = classes,
            items = items,
        )
    }

    fun detail(raw: String?, site: VodSiteDef, fallbackId: String): VodItem? {
        val page = page(raw, site)
        val item = page.items.firstOrNull { it.id == fallbackId } ?: page.items.firstOrNull() ?: return null
        return if (item.id.isBlank()) item.copy(id = fallbackId) else item
    }

    fun play(raw: String?): SpiderPlay {
        val root = objectOf(raw) ?: return SpiderPlay("")
        val header = root["header"] as? JsonObject
        val headers = linkedMapOf<String, String>()
        header?.entries?.forEach { (key, value) -> value.asText()?.let { headers[key] = it } }
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
        val kept = headers.filterKeys { key ->
            !key.equals("User-Agent", true) && !key.equals("Referer", true)
        }
        return SpiderPlay(
            url = root.text("url").orEmpty(),
            headers = kept,
            parse = root.text("parse")?.toIntOrNull() ?: 0,
            jx = root.text("jx")?.toIntOrNull() ?: 0,
            userAgent = userAgent,
            referer = referer,
        )
    }

    fun merge(first: VodPage, second: VodPage): VodPage {
        val seen = first.items.map { it.id }.toMutableSet()
        val extra = second.items.filter { seen.add(it.id) }
        return first.copy(
            classes = first.classes.ifEmpty { second.classes },
            items = first.items + extra,
            total = (first.items + extra).size,
        )
    }

    private fun items(array: JsonArray?, site: VodSiteDef): List<VodItem> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.text("vod_name", "name") ?: return@mapNotNull null
            val id = obj.text("vod_id", "id").orEmpty()
            if (id == "no_data") return@mapNotNull null
            VodItem(
                sourceKey = site.key,
                sourceName = site.name,
                api = site.api,
                siteKind = SiteKind.SPIDER,
                id = id,
                title = title,
                year = obj.text("vod_year", "year"),
                pic = obj.text("vod_pic", "pic"),
                typeName = obj.text("type_name"),
                remarks = obj.text("vod_remarks", "vod_remark"),
                area = obj.text("vod_area"),
                actor = obj.text("vod_actor"),
                director = obj.text("vod_director"),
                content = obj.text("vod_content"),
                lines = lines(obj.text("vod_play_from").orEmpty(), obj.text("vod_play_url").orEmpty()),
                userAgent = site.userAgent,
                referer = site.referer,
                headers = site.headers,
                spiderMode = site.spiderMode?.name?.lowercase().orEmpty(),
                spiderExt = site.spiderExt,
                spiderJar = site.spiderJar,
            )
        }
    }

    private fun lines(from: String, urls: String): List<PlayLine> {
        if (from.isBlank() && urls.isBlank()) return emptyList()
        val flags = if (from.isBlank()) listOf("默认") else from.split("$$$")
        val groups = urls.split("$$$")
        return flags.mapIndexedNotNull { index, name ->
            val episodes = groups.getOrElse(index) { "" }.split("#").mapNotNull { part ->
                val piece = part.trim()
                if (piece.isEmpty()) return@mapNotNull null
                val bits = piece.split("$")
                if (bits.size >= 2) {
                    Episode(bits.first().ifBlank { "播放" }, bits.drop(1).joinToString("$"))
                } else {
                    Episode("播放", piece)
                }
            }
            if (episodes.isEmpty()) null else PlayLine(name.ifBlank { "默认" }, episodes)
        }
    }

    private fun objectOf(raw: String?): JsonObject? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}

fun VodItem.toSpiderDef(): VodSiteDef = VodSiteDef(
    key = sourceKey,
    name = sourceName,
    kind = siteKind,
    api = api,
    userAgent = userAgent,
    referer = referer,
    headers = headers,
    spiderMode = when (spiderMode) {
        "js" -> SpiderMode.JS
        "jar" -> SpiderMode.JAR
        else -> if (siteKind == SiteKind.SPIDER) SpiderMode.JAR else null
    },
    spiderExt = spiderExt,
    spiderJar = spiderJar,
)
