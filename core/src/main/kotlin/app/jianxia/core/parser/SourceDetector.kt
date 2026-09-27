package app.jianxia.core.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

sealed class DetectedSource {
    data class TvBox(val suggestedName: String) : DetectedSource()
    data class MacCmsJson(val suggestedName: String) : DetectedSource()
    data class MacCmsXml(val suggestedName: String) : DetectedSource()
    data class Live(val suggestedName: String, val channelCount: Int) : DetectedSource()
    data class Unknown(val reason: String) : DetectedSource()
}

object SourceDetector {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun detect(raw: String, url: String = ""): DetectedSource {
        val text = ConfigDecoder.normalize(raw)
        if (text.isEmpty()) {
            return DetectedSource.Unknown("内容是空的")
        }
        if (text.startsWith("{") || text.startsWith("[") || text.contains("{")) {
            val payload = runCatching { json.parseToJsonElement(extractJsonPayload(text)) }.getOrNull()
            val obj = payload as? JsonObject
            if (obj != null) {
                val sites = obj["sites"] as? JsonArray
                val lives = obj["lives"] as? JsonArray
                if (sites != null || (lives != null && obj["list"] !is JsonArray)) {
                    return DetectedSource.TvBox(guessName(url, "TVBox 配置"))
                }
                val list = obj["list"] as? JsonArray
                val first = list?.firstOrNull() as? JsonObject
                if (list != null && (first?.containsKey("vod_name") == true || first?.containsKey("vod_id") == true || obj["class"] is JsonArray)) {
                    return DetectedSource.MacCmsJson(guessName(url, "苹果 CMS"))
                }
            }
        }
        if (text.contains("<video", ignoreCase = true) || (text.contains("<rss", ignoreCase = true) && text.contains("<list", ignoreCase = true))) {
            return DetectedSource.MacCmsXml(guessName(url, "苹果 CMS"))
        }
        if (text.contains("#EXTM3U", ignoreCase = true)) {
            val channels = M3uParser.parse(text)
            if (M3uParser.looksLikeSegments(channels)) {
                return DetectedSource.Unknown("这看起来是单个视频流，不是频道列表")
            }
            if (channels.isEmpty()) {
                return DetectedSource.Unknown("M3U 里没有频道")
            }
            return DetectedSource.Live(guessName(url, "直播"), channels.size)
        }
        val txt = TxtLiveParser.parse(text)
        if (txt.isNotEmpty() && looksLikeTxtPlaylist(text, txt)) {
            return DetectedSource.Live(guessName(url, "直播"), txt.size)
        }
        return DetectedSource.Unknown("无法识别。请填写 TVBox JSON、苹果 CMS 接口、M3U 或 TXT 直播源")
    }

    private fun looksLikeTxtPlaylist(text: String, channels: List<app.jianxia.core.model.LiveChannel>): Boolean {
        if (text.contains("#genre#", ignoreCase = true)) return true
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        return channels.size * 2 >= lines.size
    }

    private fun guessName(url: String, fallback: String): String {
        val host = runCatching {
            val cleaned = url.substringAfter("://").substringBefore("/").substringBefore(":")
            cleaned
        }.getOrNull().orEmpty()
        return host.ifBlank { fallback }
    }
}
