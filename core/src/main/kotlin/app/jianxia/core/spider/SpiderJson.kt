package app.jianxia.core.spider

import app.jianxia.core.model.Episode
import app.jianxia.core.model.PlayLine
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.model.VodClass
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.parser.SiteFilters
import app.jianxia.core.parser.asText
import app.jianxia.core.parser.cleanScore
import app.jianxia.core.parser.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val items = items(itemArray(root), site)
        return VodPage(
            page = root.text("page")?.toIntOrNull() ?: 1,
            pageCount = root.text("pagecount", "pageCount")?.toIntOrNull() ?: 1,
            total = root.text("total")?.toIntOrNull() ?: items.size,
            classes = classes,
            items = items,
            filters = SiteFilters.parseAttached(root["filters"] ?: root["filter"]),
        )
    }

    fun detail(raw: String?, site: VodSiteDef, fallbackId: String): VodItem? {
        val page = page(raw, site)
        val item = page.items.firstOrNull { it.id == fallbackId } ?: page.items.firstOrNull() ?: return null
        return if (item.id.isBlank()) item.copy(id = fallbackId) else item
    }

    fun play(raw: String?): SpiderPlay {
        val root = objectOf(raw) ?: return SpiderPlay("")
        val headers = linkedMapOf<String, String>()
        when (val header = root["header"]) {
            is JsonObject -> header.entries.forEach { (key, value) -> value.asText()?.let { headers[key] = it } }
            is kotlinx.serialization.json.JsonPrimitive -> {
                val nested = runCatching { json.parseToJsonElement(header.content).jsonObject }.getOrNull()
                nested?.entries?.forEach { (key, value) -> value.asText()?.let { headers[key] = it } }
            }
            else -> Unit
        }
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
        val kept = headers.filterKeys { key ->
            !key.equals("User-Agent", true) && !key.equals("Referer", true)
        }
        val media = root.text("url").orEmpty()
        val prefix = root.text("playUrl", "play_url").orEmpty()
        val url = when {
            media.isBlank() -> prefix
            prefix.isBlank() || media.startsWith(prefix) -> media
            else -> prefix + media
        }
        return SpiderPlay(
            url = url,
            headers = kept,
            parse = PlayText.flag(root.text("parse")),
            jx = PlayText.flag(root.text("jx")),
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
            filters = second.filters + first.filters,
        )
    }

    private fun itemArray(root: JsonObject): JsonArray? {
        val direct = root["list"] ?: root["data"] ?: root["video"] ?: root["videos"]
        when (direct) {
            is JsonArray -> return direct
            is JsonObject -> return JsonArray(listOf(direct))
            else -> Unit
        }
        if (root["vod_name"] != null || root["name"] != null || root["vod_play_url"] != null) {
            return JsonArray(listOf(root))
        }
        return null
    }

    private fun playLines(obj: JsonObject): List<PlayLine> {
        structured(obj)?.let { if (it.isNotEmpty()) return it }
        val from = textish(obj["vod_play_from"] ?: obj["play_from"] ?: obj["from"])
        val urls = textish(obj["vod_play_url"] ?: obj["play_url"] ?: obj["urls"])
        return PlayText.lines(from, urls)
    }

    private fun structured(obj: JsonObject): List<PlayLine>? {
        val node = obj["vod_play_list"] ?: obj["play_list"] ?: return null
        val array = node as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.text("flag", "from", "name").orEmpty().ifBlank { "默认" }
            val urls = item["urls"] ?: item["episodes"] ?: item["url"]
            val episodes = when (urls) {
                is JsonArray -> urls.mapNotNull { episodeOf(it) }
                else -> PlayText.lines(name, textish(urls)).firstOrNull()?.episodes.orEmpty()
            }
            if (episodes.isEmpty()) null else PlayLine(name, episodes)
        }
    }

    private fun episodeOf(element: JsonElement): Episode? {
        when (element) {
            is JsonObject -> {
                val url = element.text("url", "playUrl").orEmpty()
                if (url.isBlank()) return null
                return Episode(element.text("name", "title").orEmpty().ifBlank { "播放" }, url)
            }
            is JsonPrimitive -> {
                val text = element.asText().orEmpty()
                if (text.isBlank()) return null
                return PlayText.lines("", text).firstOrNull()?.episodes?.firstOrNull()
            }
            else -> return null
        }
    }

    private fun textish(element: JsonElement?): String {
        when (element) {
            null -> return ""
            is JsonArray -> {
                val objects = element.filterIsInstance<JsonObject>()
                if (objects.isNotEmpty() && objects.all { it["url"] != null || it["playUrl"] != null }) {
                    return objects.mapNotNull { item ->
                        val url = item.text("url", "playUrl").orEmpty()
                        if (url.isBlank()) null else "${item.text("name", "title").orEmpty().ifBlank { "播放" }}$$url"
                    }.joinToString("#")
                }
                return element.mapNotNull { it.asText() }.joinToString("$$$")
            }
            else -> return element.asText().orEmpty()
        }
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
                year = PlayText.year(obj.text("vod_year", "year")),
                pic = app.jianxia.core.parser.PosterRefs.store(
                    obj.text("vod_pic", "pic"),
                    site.api,
                    site.referer,
                    site.userAgent,
                    site.headers,
                ),
                typeName = obj.text("type_name"),
                remarks = obj.text("vod_remarks", "vod_remark"),
                area = obj.text("vod_area"),
                actor = obj.text("vod_actor"),
                director = obj.text("vod_director"),
                content = obj.text("vod_content"),
                score = cleanScore(obj.text("vod_douban_score", "vod_score")),
                lines = playLines(obj),
                userAgent = site.userAgent,
                referer = site.referer,
                headers = site.headers,
                spiderMode = site.spiderMode?.name?.lowercase().orEmpty(),
                spiderExt = site.spiderExt,
                spiderJar = site.spiderJar,
            )
        }
    }

    private fun objectOf(raw: String?): JsonObject? {
        var text = raw?.trim().orEmpty().removePrefix("\uFEFF")
        if (text.isEmpty() || text == "null") return null
        if (!text.startsWith("{") && !text.startsWith("\"") && !text.startsWith("[")) {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text = text.substring(start, end + 1)
        }
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        return when (element) {
            is JsonObject -> element
            is JsonPrimitive -> if (element.isString) objectOf(element.content) else null
            else -> null
        }
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
