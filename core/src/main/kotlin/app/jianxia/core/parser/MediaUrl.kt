package app.jianxia.core.parser

private val directMedia = Regex(
    """\.(m3u8|mpd|mp4|m4v|mov|mkv|flv|avi|wmv|ts|webm|m3u)(\?|#|$)""",
    RegexOption.IGNORE_CASE,
)

fun isDirectMediaUrl(url: String): Boolean {
    val value = url.trim()
    if (!(value.startsWith("http://") || value.startsWith("https://"))) return false
    if (directMedia.containsMatchIn(value)) return true
    val lower = value.lowercase()
    return lower.contains("mime=video") || lower.contains("/m3u8") || lower.contains("type=m3u8")
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
