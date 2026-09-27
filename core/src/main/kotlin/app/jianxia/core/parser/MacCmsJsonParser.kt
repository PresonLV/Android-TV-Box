package app.jianxia.core.parser

import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodClass
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object MacCmsJsonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, sourceKey: String, sourceName: String, api: String): VodPage {
        val root = json.parseToJsonElement(extractJsonPayload(raw)) as? JsonObject
            ?: throw IllegalArgumentException("不是苹果 CMS JSON")
        if (root["list"] !is JsonArray && root["class"] !is JsonArray) {
            throw IllegalArgumentException("不是苹果 CMS JSON")
        }
        val classes = (root["class"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("type_id") ?: return@mapNotNull null
            val name = obj.text("type_name") ?: return@mapNotNull null
            VodClass(id = id, name = name, parentId = obj.text("type_pid"))
        }
        val items = (root["list"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.text("vod_name", "name") ?: return@mapNotNull null
            VodItem(
                sourceKey = sourceKey,
                sourceName = sourceName,
                api = api,
                siteKind = SiteKind.MACCMS_JSON,
                id = obj.text("vod_id", "id").orEmpty(),
                title = title,
                year = obj.text("vod_year", "year"),
                pic = normalizePic(obj.text("vod_pic", "pic")),
                typeName = obj.text("type_name", "vod_class"),
                remarks = obj.text("vod_remarks", "note"),
                area = obj.text("vod_area", "area"),
                actor = obj.text("vod_actor", "actor"),
                director = obj.text("vod_director", "director"),
                content = stripHtml(obj.text("vod_content", "vod_blurb", "content")),
                score = cleanScore(obj.text("vod_douban_score", "vod_score")),
                lines = parsePlayInfo(obj.text("vod_play_from"), obj.text("vod_play_url")),
            )
        }
        return VodPage(
            page = root.text("page")?.toIntOrNull() ?: 1,
            pageCount = root.text("pagecount")?.toIntOrNull() ?: 1,
            total = root.text("total")?.toIntOrNull() ?: items.size,
            classes = classes,
            items = items,
        )
    }
}

internal fun JsonObject.text(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key].asText()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

internal fun JsonElement?.asText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    else -> null
}
