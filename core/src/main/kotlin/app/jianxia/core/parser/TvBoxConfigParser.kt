package app.jianxia.core.parser

import app.jianxia.core.model.LiveSourceDef
import app.jianxia.core.model.ParseDef
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.TvBoxConfig
import app.jianxia.core.model.VodSiteDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull

object TvBoxConfigParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, baseUrl: String? = null): TvBoxConfig {
        val root = json.parseToJsonElement(extractJsonPayload(ConfigDecoder.normalize(raw))) as? JsonObject
            ?: throw IllegalArgumentException("不是 TVBox JSON 配置")
        val sitesElement = root["sites"] as? JsonArray
        val livesElement = root["lives"] as? JsonArray
        if (sitesElement == null && livesElement == null) {
            throw IllegalArgumentException("不是 TVBox JSON 配置")
        }
        val sites = sitesElement.orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val type = obj["type"].asText()?.toIntOrNull()
                ?: (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                ?: -1
            val name = obj.text("name") ?: "站点${index + 1}"
            val key = obj.text("key") ?: "site_$index"
            val api = obj.text("api").orEmpty()
            val kind = when (type) {
                0 -> SiteKind.MACCMS_XML
                1 -> SiteKind.MACCMS_JSON
                else -> SiteKind.UNSUPPORTED
            }
            val reason = when {
                kind == SiteKind.UNSUPPORTED && type == 3 -> "不支持 JAR/JS 爬虫源"
                kind == SiteKind.UNSUPPORTED -> "不支持的站点类型 $type"
                api.isBlank() -> "缺少接口地址"
                else -> null
            }
            val resolvedKind = if (reason != null && kind != SiteKind.UNSUPPORTED) SiteKind.UNSUPPORTED else kind
            val meta = obj.streamMeta()
            VodSiteDef(
                key = key,
                name = name,
                kind = resolvedKind,
                api = api,
                searchable = obj.flag("searchable", default = true),
                quickSearch = obj.flag("quickSearch", default = true),
                filterable = obj.flag("filterable", default = false),
                unsupportedReason = reason,
                userAgent = meta.userAgent,
                referer = meta.referer,
                headers = meta.headers,
            )
        }
        val lives = livesElement.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.text("url") ?: return@mapNotNull null
            val meta = obj.streamMeta()
            LiveSourceDef(
                name = obj.text("name") ?: "直播",
                url = url,
                epgUrl = obj.text("epg"),
                userAgent = meta.userAgent,
                referer = meta.referer,
                headers = meta.headers,
            )
        }
        val parses = (root["parses"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.text("url") ?: return@mapNotNull null
            ParseDef(
                name = obj.text("name") ?: "解析",
                type = obj.text("type")?.toIntOrNull() ?: 0,
                url = url,
            )
        }
        return TvBoxConfig(
            sites = sites,
            lives = lives,
            parses = parses,
            wallpaper = root.text("wallpaper"),
        ).resolve(baseUrl)
    }
}

private fun TvBoxConfig.resolve(baseUrl: String?): TvBoxConfig {
    if (baseUrl.isNullOrBlank()) return this
    return copy(
        sites = sites.map { site -> site.copy(api = resolveAgainst(baseUrl, site.api)) },
        lives = lives.map { live ->
            live.copy(
                url = resolveAgainst(baseUrl, live.url),
                epgUrl = live.epgUrl?.let { resolveAgainst(baseUrl, it) },
            )
        },
        parses = parses.map { parse -> parse.copy(url = resolveAgainst(baseUrl, parse.url)) },
        wallpaper = wallpaper?.let { resolveAgainst(baseUrl, it) },
    )
}

private fun JsonObject.flag(key: String, default: Boolean): Boolean {
    val text = this[key].asText() ?: return default
    return text == "1" || text.equals("true", true)
}
