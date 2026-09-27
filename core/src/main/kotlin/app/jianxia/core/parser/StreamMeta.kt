package app.jianxia.core.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class StreamMeta(
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

private val headerJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 从 TVBox 站点或直播项里取出 User-Agent、Referer 和其他请求头。 */
fun JsonObject.streamMeta(): StreamMeta {
    val map = linkedMapOf<String, String>()
    when (val header = this["header"]) {
        is JsonObject -> header.entries.forEach { (key, value) ->
            value.asText()?.let { map[key] = it }
        }
        is JsonPrimitive -> {
            val text = header.content.trim()
            val nested = runCatching { headerJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (nested != null) {
                nested.entries.forEach { (key, value) -> value.asText()?.let { map[key] = it } }
            }
        }
        else -> Unit
    }
    text("ua", "user-agent", "User-Agent")?.let { incoming ->
        if (map.keys.none { it.equals("User-Agent", true) }) map["User-Agent"] = incoming
    }
    val userAgent = map.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
    val referer = map.entries.firstOrNull { it.key.equals("Referer", true) || it.key.equals("Referrer", true) }?.value.orEmpty()
    val headers = map.filterKeys {
        !it.equals("User-Agent", true) && !it.equals("Referer", true) && !it.equals("Referrer", true)
    }
    return StreamMeta(userAgent, referer, headers)
}
