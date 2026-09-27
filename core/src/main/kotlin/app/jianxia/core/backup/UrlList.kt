package app.jianxia.core.backup

object UrlList {
    private val embedded = Regex("""https?://[^\s<>"'，。；、]+""", RegexOption.IGNORE_CASE)

    /** 每一行都是完整网址时返回列表。夹杂说明文字时返回 null，交给 [findAll]。 */
    fun extract(raw: String): List<String>? {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        if (lines.any { !isHttp(it) }) return null
        return lines.map { clean(it) }.distinctBy { normalize(it) }
    }

    /**
     * 从整段文字里抽出全部 http(s) 网址。JSON 正文不拆开，避免把一份配置里的内链当成多个接口。
     * 同一地址只保留第一次出现。
     */
    fun findAll(raw: String): List<String> {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()
        val seen = linkedSetOf<String>()
        val urls = mutableListOf<String>()
        embedded.findAll(trimmed).forEach { match ->
            val url = clean(match.value)
            if (!isHttp(url)) return@forEach
            if (seen.add(normalize(url))) urls += url
        }
        return urls
    }

    fun isHttp(value: String): Boolean {
        val text = value.trim()
        return text.startsWith("http://") || text.startsWith("https://")
    }

    fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val scheme = trimmed.substringBefore("://", "").lowercase()
        val rest = trimmed.substringAfter("://", "")
        if (scheme.isEmpty() || rest.isEmpty()) return trimmed.lowercase()
        val host = rest.substringBefore('/').lowercase()
        val path = rest.substringAfter('/', "")
        return if (path.isEmpty()) "$scheme://$host" else "$scheme://$host/$path"
    }

    private fun clean(value: String): String =
        value.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '，', '。', '；', '、')
}

data class BatchLine(val url: String, val status: String, val detail: String)

sealed class BatchPlan {
    abstract val url: String

    data class Fresh(override val url: String) : BatchPlan()
    data class Exists(override val url: String) : BatchPlan()
}

object BatchAdd {
    fun plan(text: String, existing: Collection<String>): List<BatchPlan> {
        val known = existing.map { UrlList.normalize(it) }.toSet()
        return UrlList.findAll(text).map { url ->
            if (UrlList.normalize(url) in known) BatchPlan.Exists(url) else BatchPlan.Fresh(url)
        }
    }

    fun message(lines: List<BatchLine>): String {
        if (lines.isEmpty()) return "没有找到网址"
        return lines.joinToString("\n") { line ->
            val tail = when (line.status) {
                "ok" -> "成功"
                "exists" -> "已存在"
                else -> line.detail.ifBlank { "失败" }
            }
            "${line.url}  $tail"
        }
    }
}
