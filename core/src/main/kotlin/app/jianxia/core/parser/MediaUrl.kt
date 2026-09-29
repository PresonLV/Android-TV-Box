package app.jianxia.core.parser

private val directMedia = Regex(
    """\.(m3u8|mpd|mp4|m4v|mov|mkv|flv|avi|wmv|ts|webm|m3u)(\?|#|$)""",
    RegexOption.IGNORE_CASE,
)

fun isLocalProxyUrl(url: String): Boolean = app.jianxia.core.spider.PlayText.isLocalProxy(url)

fun isDirectMediaUrl(url: String): Boolean {
    val value = url.trim()
    if (!(value.startsWith("http://") || value.startsWith("https://"))) return false
    if (directMedia.containsMatchIn(value)) return true
    val lower = value.lowercase()
    return lower.contains("mime=video") || lower.contains("/m3u8") || lower.contains("type=m3u8")
}

/** parse=1 / jx=1，或看起来是网页而不是媒体文件时，需要解析或嗅探。 */
fun needsSniff(url: String, parse: Int = 0, jx: Int = 0): Boolean {
    val value = url.trim()
    if (isLocalProxyUrl(value) || isDirectMediaUrl(value)) return false
    if (!(value.startsWith("http://") || value.startsWith("https://"))) return false
    if (parse == 1 || jx == 1) return true
    val path = value.lowercase().substringBefore('?').substringBefore('#')
    return path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php") || path.contains("/jx")
}

fun mediaMime(url: String, head: String = "", contentType: String? = null): String? {
    val type = contentType.orEmpty().lowercase()
    val path = url.lowercase().substringBefore('?').substringBefore('#')
    val sample = head.take(240)
    return when {
        path.contains(".m3u8") || path.contains(".m3u") || type.contains("mpegurl") || sample.contains("#EXTM3U", true) ->
            "application/vnd.apple.mpegurl"
        path.endsWith(".mpd") || type.contains("dash+xml") || sample.contains("<MPD") -> "application/dash+xml"
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> "video/mp4"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".flv") -> "video/x-flv"
        path.endsWith(".ts") -> "video/mp2t"
        path.endsWith(".webm") -> "video/webm"
        else -> null
    }
}

fun extractMediaUrl(raw: String): String? {
    val root = runCatching {
        kotlinx.serialization.json.Json { isLenient = true; ignoreUnknownKeys = true }
            .parseToJsonElement(extractJsonPayload(raw))
    }.getOrNull() ?: return null
    if (root is kotlinx.serialization.json.JsonPrimitive && root.isString) {
        return root.content.takeIf { isDirectMediaUrl(it) }
    }
    val preferred = mutableListOf<String>()
    val fallback = mutableListOf<String>()
    fun walk(element: kotlinx.serialization.json.JsonElement, key: String?) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> element.forEach { (childKey, child) -> walk(child, childKey) }
            is kotlinx.serialization.json.JsonArray -> element.forEach { walk(it, key) }
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (!element.isString) return
                val text = element.content
                if (!isDirectMediaUrl(text)) return
                val preferredKey = key != null && (
                    key.equals("url", true) ||
                        key.equals("playUrl", true) ||
                        key.equals("play_url", true)
                    )
                if (preferredKey) preferred += text else fallback += text
            }
            else -> Unit
        }
    }
    walk(root, null)
    return preferred.firstOrNull() ?: fallback.firstOrNull()
}
