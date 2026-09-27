package app.jianxia.core.parser

import app.jianxia.core.model.LiveSourceDef
import app.jianxia.core.model.ParseDef
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.model.TvBoxConfig
import app.jianxia.core.model.VodSiteDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val rootSpider = root.text("spider").orEmpty()
        val sites = sitesElement.orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val type = obj["type"].asText()?.toIntOrNull()
                ?: (obj["type"] as? JsonPrimitive)?.intOrNull
                ?: -1
            val name = obj.text("name") ?: "站点${index + 1}"
            val key = obj.text("key") ?: "site_$index"
            val api = obj.text("api").orEmpty()
            val kind = when (type) {
                0 -> SiteKind.MACCMS_XML
                1 -> SiteKind.MACCMS_JSON
                3 -> SiteKind.SPIDER
                else -> SiteKind.UNSUPPORTED
            }
            val reason = when {
                kind == SiteKind.UNSUPPORTED -> "不支持的站点类型 $type"
                api.isBlank() -> "缺少接口地址"
                else -> null
            }
            val resolvedKind = if (reason != null && kind != SiteKind.UNSUPPORTED) SiteKind.UNSUPPORTED else kind
            val mode = if (resolvedKind == SiteKind.SPIDER) spiderMode(api) else null
            val ownJar = obj.text("jar").orEmpty()
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
                spiderMode = mode,
                spiderExt = extText(obj["ext"]),
                spiderJar = when {
                    ownJar.isNotBlank() -> ownJar
                    mode == SpiderMode.JAR -> rootSpider
                    else -> ""
                },
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
            spider = rootSpider,
            filters = SiteFilters.parseRoot(root["filters"]),
        ).resolve(baseUrl)
    }
}

internal fun spiderMode(api: String): SpiderMode {
    val value = api.trim()
    val lower = value.lowercase()
    val script = lower.endsWith(".js") ||
        lower.contains("drpy") ||
        value.startsWith("http://") ||
        value.startsWith("https://") ||
        value.startsWith("./") ||
        value.startsWith("../") ||
        value.startsWith("/")
    return if (script) SpiderMode.JS else SpiderMode.JAR
}

private fun extText(element: JsonElement?): String = when (element) {
    null -> ""
    is JsonPrimitive -> element.content
    else -> element.toString()
}

private fun TvBoxConfig.resolve(baseUrl: String?): TvBoxConfig {
    if (baseUrl.isNullOrBlank()) return this
    return copy(
        sites = sites.map { site ->
            site.copy(
                api = resolveAgainst(baseUrl, site.api),
                spiderExt = resolveExt(baseUrl, site.spiderExt),
                spiderJar = resolveJar(baseUrl, site.spiderJar),
            )
        },
        lives = lives.map { live ->
            live.copy(
                url = resolveAgainst(baseUrl, live.url),
                epgUrl = live.epgUrl?.let { resolveAgainst(baseUrl, it) },
            )
        },
        parses = parses.map { parse -> parse.copy(url = resolveAgainst(baseUrl, parse.url)) },
        wallpaper = wallpaper?.let { resolveAgainst(baseUrl, it) },
        spider = resolveJar(baseUrl, spider),
    )
}

private fun resolveJar(baseUrl: String?, raw: String): String {
    if (raw.isBlank()) return ""
    val parts = raw.split(";")
    val url = resolveAgainst(baseUrl, parts.first().trim())
    val rest = parts.drop(1).joinToString(";") { it.trim() }
    return if (rest.isBlank()) url else "$url;$rest"
}

private fun resolveExt(baseUrl: String?, raw: String): String {
    val value = raw.trim()
    if (value.isEmpty() || value.startsWith("{") || value.startsWith("[")) return value
    return resolveAgainst(baseUrl, value)
}

private fun JsonObject.flag(key: String, default: Boolean): Boolean {
    val text = this[key].asText() ?: return default
    return text == "1" || text.equals("true", true)
}
