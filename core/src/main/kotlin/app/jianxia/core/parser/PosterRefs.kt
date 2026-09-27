package app.jianxia.core.parser

data class PosterRef(val url: String, val headers: Map<String, String> = emptyMap())

/** 把 vod_pic 里的相对地址、协议相对地址和 TVBox 的 @Referer= 后缀拆开。 */
object PosterRefs {
    private val marker = Regex("""@(Referer|User-Agent|Cookie)=""", RegexOption.IGNORE_CASE)

    fun parse(raw: String?, base: String? = null, fallback: Map<String, String> = emptyMap()): PosterRef? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cut = marker.find(text)
        val body = if (cut == null) text else text.substring(0, cut.range.first)
        val headers = linkedMapOf<String, String>()
        fallback.forEach { (key, value) ->
            if (value.isNotBlank()) headers[canonical(key)] = value
        }
        if (cut != null) {
            val suffix = text.substring(cut.range.first)
            val marks = marker.findAll(suffix).toList()
            marks.forEachIndexed { index, match ->
                val start = match.range.last + 1
                val end = marks.getOrNull(index + 1)?.range?.first ?: suffix.length
                val value = suffix.substring(start, end).trim()
                if (value.isNotEmpty()) headers[canonical(match.groupValues[1])] = value
            }
        }
        val url = absolute(body.trim(), base)
        if (url.isEmpty()) return null
        return PosterRef(url, headers)
    }

    fun store(
        raw: String?,
        base: String? = null,
        referer: String = "",
        userAgent: String = "",
        extra: Map<String, String> = emptyMap(),
    ): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fallback = linkedMapOf<String, String>()
        if (referer.isNotBlank()) fallback["Referer"] = referer
        if (userAgent.isNotBlank()) fallback["User-Agent"] = userAgent
        extra.forEach { (key, value) -> if (value.isNotBlank()) fallback[key] = value }
        val parsed = parse(text, base, fallback) ?: return text
        return encode(parsed)
    }

    fun encode(ref: PosterRef): String {
        val suffix = listOf("Referer", "User-Agent", "Cookie").mapNotNull { key ->
            ref.headers.entries.firstOrNull { it.key.equals(key, true) }?.value?.takeIf { it.isNotBlank() }?.let { "@$key=$it" }
        }.joinToString("")
        return ref.url + suffix
    }

    private fun canonical(key: String): String = when (key.lowercase()) {
        "referer" -> "Referer"
        "user-agent" -> "User-Agent"
        "cookie" -> "Cookie"
        else -> key
    }

    private fun absolute(url: String, base: String?): String {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) return url
        if (url.startsWith("//")) return "https:$url"
        val root = base?.trim().orEmpty()
        if (!root.startsWith("http://") && !root.startsWith("https://")) return url
        val origin = runCatching { java.net.URI(root) }.getOrNull() ?: return url
        if (url.startsWith("/")) {
            val scheme = origin.scheme ?: "https"
            val authority = origin.authority ?: return url
            return "$scheme://$authority$url"
        }
        val clean = root.substringBefore('?').substringBefore('#')
        val slash = clean.lastIndexOf('/')
        val parent = if (slash >= "https://".length) clean.substring(0, slash + 1) else "$clean/"
        return parent + url.removePrefix("./")
    }
}
