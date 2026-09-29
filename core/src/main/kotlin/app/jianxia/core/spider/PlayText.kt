package app.jianxia.core.spider

import app.jianxia.core.model.Episode
import app.jianxia.core.model.PlayLine
import app.jianxia.core.parser.asText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

data class RangeSlice(
    val code: Int,
    val start: Long,
    val end: Long,
    val total: Long,
    val body: ByteArray,
)

/** 播放地址、年份和本地代理这些纯文本规则，方便在没有电视的环境里单测。 */
object PlayText {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val localProxy = Regex(
        """^https?://(?:127\.0\.0\.1|localhost|0\.0\.0\.0)(?::\d+)?/proxy\??(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val cloudWords = listOf(
        "夸父", "嘟嘟", "夸克", "阿里", "天翼", "百度", "迅雷", "网盘",
        "quark", "alipan", "aliyun", "ucpan", "pikpak", "115",
    )

    fun year(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "0" || value == "0000") return null
        if (value.equals("null", true) || value.equals("undefined", true)) return null
        return value
    }

    fun flag(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "1", "true" -> 1
        null, "", "0", "false" -> 0
        else -> raw.trim().toIntOrNull() ?: 0
    }

    /** 网盘选集和本地代理不要拿普通测速去判死刑。返回 null 表示可以测速。 */
    fun pendingLabel(name: String, url: String): String? {
        val target = url.trim()
        if (isLocalProxy(target)) return "本地代理"
        if (!target.startsWith("http://") && !target.startsWith("https://")) return "网盘"
        val blob = (name + " " + target).lowercase()
        if (cloudWords.any { blob.contains(it) }) return "网盘"
        return null
    }

    fun isLocalProxy(url: String): Boolean {
        val value = url.trim()
        if (value.startsWith("proxy://")) return true
        return localProxy.containsMatchIn(value)
    }

    fun lines(from: String, urls: String): List<PlayLine> {
        val fromText = from.trim()
        val urlText = urls.trim()
        if (fromText.isEmpty() && urlText.isEmpty()) return emptyList()
        val flags = splitGroups(fromText).ifEmpty { listOf("默认") }
        val groups = splitGroups(urlText)
        val count = maxOf(flags.size, groups.size).coerceAtLeast(1)
        return (0 until count).mapNotNull { index ->
            val name = flags.getOrElse(index) { "线路${index + 1}" }.trim().ifBlank { "线路${index + 1}" }
            val episodes = episodes(groups.getOrElse(index) { "" })
            if (episodes.isEmpty()) null else PlayLine(name, episodes)
        }
    }

    fun rewriteProxy(siteKey: String, url: String, base: String): String {
        val trimmed = url.trim()
        val query = when {
            trimmed.startsWith("proxy://") -> trimmed.removePrefix("proxy://").trimStart('?')
            else -> localProxy.find(trimmed)?.groupValues?.getOrNull(1) ?: return trimmed
        }
        val root = base.substringBefore("?").trimEnd('/')
        if (query.split("&").any { it.substringBefore("=") == "site" && it.substringAfter("=", "").isNotBlank() }) {
            return if (query.isBlank()) root else "$root?$query"
        }
        val site = URLEncoder.encode(siteKey, "UTF-8")
        val extra = if (query.isBlank()) "" else "&$query"
        return "$root?site=$site$extra"
    }

    fun slice(bytes: ByteArray, rangeHeader: String?): RangeSlice {
        val total = bytes.size.toLong()
        if (rangeHeader.isNullOrBlank() || !rangeHeader.trim().startsWith("bytes", ignoreCase = true)) {
            val end = if (bytes.isEmpty()) 0L else total - 1
            return RangeSlice(200, 0, end, total, bytes)
        }
        val spec = rangeHeader.substringAfter("=", "").substringBefore(",").trim()
        val startRaw = spec.substringBefore("-")
        val endRaw = spec.substringAfter("-", "")
        val start = startRaw.toLongOrNull() ?: 0L
        if (bytes.isEmpty() || start >= total || start < 0) {
            return RangeSlice(416, 0, 0, total, ByteArray(0))
        }
        val end = if (endRaw.isBlank()) total - 1 else endRaw.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (end < start) return RangeSlice(416, 0, 0, total, ByteArray(0))
        val body = bytes.copyOfRange(start.toInt(), (end + 1).toInt())
        return RangeSlice(206, start, end, total, body)
    }

    /** 只往 JSON 扩展里补空着的网盘字段。网址和密文原样返回。 */
    fun mergeCookies(ext: String, quark: String, uc: String, ali: String): String {
        if (quark.isBlank() && uc.isBlank() && ali.isBlank()) return ext
        val trimmed = ext.trim()
        if (!trimmed.startsWith("{")) return ext
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return ext
        val map = obj.toMutableMap()
        fun put(key: String, value: String) {
            if (value.isBlank()) return
            if (!map[key].asText().isNullOrBlank()) return
            map[key] = JsonPrimitive(value)
        }
        put("quark", quark)
        put("quarkCookie", quark)
        put("uc", uc)
        put("ucCookie", uc)
        put("ali", ali)
        put("aliToken", ali)
        return JsonObject(map).toString()
    }

    private fun splitGroups(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val parts = if (raw.contains("$$$")) raw.split("$$$") else listOf(raw)
        return parts.map { it.trim() }
    }

    private fun episodes(group: String): List<Episode> {
        if (group.isBlank()) return emptyList()
        val parts = when {
            group.contains("#") -> group.split("#")
            group.contains("\n") -> group.split('\n')
            else -> listOf(group)
        }
        return parts.mapNotNull { part ->
            val piece = part.trim()
            if (piece.isEmpty()) return@mapNotNull null
            val bits = piece.split("$")
            if (bits.size >= 2) {
                Episode(bits.first().ifBlank { "播放" }, bits.drop(1).joinToString("$"))
            } else {
                Episode(piece.take(48).ifBlank { "播放" }, piece)
            }
        }
    }
}
