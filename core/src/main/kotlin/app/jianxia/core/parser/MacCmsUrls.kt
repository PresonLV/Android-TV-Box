package app.jianxia.core.parser

import app.jianxia.core.model.SiteKind
import java.net.URLDecoder
import java.net.URLEncoder

/** 首页和分类先走 ac=list。没有条目时再试 ac=videolist，两种苹果 CMS 都这样。 */
fun macCmsBrowseActions(kind: SiteKind): List<String> = listOf("list", "videolist")

fun macCmsUrl(api: String, params: Map<String, String>): String {
    val trimmed = api.trim()
    val noHash = trimmed.substringBefore('#')
    val base = noHash.substringBefore('?')
    val existing = noHash.substringAfter('?', "")
    val merged = linkedMapOf<String, String>()
    if (existing.isNotBlank()) {
        existing.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = urlDecode(part.substringBefore('='))
            val value = urlDecode(part.substringAfter('=', ""))
            if (key.isNotBlank()) merged[key] = value
        }
    }
    params.forEach { (key, value) -> merged[key] = value }
    if (merged.isEmpty()) return base
    val query = merged.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }
    return "$base?$query"
}

fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

fun urlDecode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
