package app.jianxia.core.backup

object UrlList {
    fun extract(raw: String): List<String>? {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        if (lines.any { !isHttp(it) }) return null
        return lines
    }

    fun isHttp(value: String): Boolean {
        val text = value.trim()
        return text.startsWith("http://") || text.startsWith("https://")
    }
}
