package app.jianxia.core.parser

import java.nio.charset.Charset

fun cleanDocument(raw: String): String = raw.trim().removePrefix("\uFEFF").trim()

/** 去掉 JSONP 外壳，取出第一个完整的对象或数组。 */
fun extractJsonPayload(raw: String): String {
    val text = cleanDocument(raw)
    if (text.startsWith("{") || text.startsWith("[")) return text
    val startObj = text.indexOf('{')
    val startArr = text.indexOf('[')
    val start = listOf(startObj, startArr).filter { it >= 0 }.minOrNull() ?: return text
    val end = if (text[start] == '{') text.lastIndexOf('}') else text.lastIndexOf(']')
    if (end <= start) return text
    return text.substring(start, end + 1)
}

fun normalizePic(pic: String?, base: String? = null): String? = PosterRefs.store(pic, base)

fun stripHtml(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val text = raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()
    return text.ifBlank { null }
}

fun cleanPlayUrl(url: String): String = url.trim().replace("&amp;", "&")

fun decodeBytes(bytes: ByteArray, contentType: String?): String {
    if (bytes.isEmpty()) return ""
    val header = String(bytes.copyOfRange(0, minOf(bytes.size, 240)), Charsets.ISO_8859_1)
    val declared = Regex("""encoding=["']([A-Za-z0-9_\-]+)["']""", RegexOption.IGNORE_CASE)
        .find(header)
        ?.groupValues
        ?.get(1)
    val fromType = Regex("""charset=([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
        .find(contentType.orEmpty())
        ?.groupValues
        ?.get(1)
    val name = (declared ?: fromType ?: "UTF-8").lowercase()
    val charset = when (name) {
        "gbk", "gb2312", "gb18030" -> Charset.forName("GB18030")
        "utf-8", "utf8" -> Charsets.UTF_8
        else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }
    return String(bytes, charset).removePrefix("\uFEFF")
}
