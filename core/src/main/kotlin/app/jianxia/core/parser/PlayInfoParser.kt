package app.jianxia.core.parser

import app.jianxia.core.model.Episode
import app.jianxia.core.model.PlayLine

fun parsePlayInfo(from: String?, urls: String?): List<PlayLine> {
    val fromParts = splitLines(from)
    val urlParts = splitLines(urls)
    val count = maxOf(fromParts.size, urlParts.size)
    if (count == 0) return emptyList()
    val lines = (0 until count).map { index ->
        val name = fromParts.getOrNull(index)?.trim().orEmpty().ifBlank { "线路${index + 1}" }
        val episodes = parseEpisodes(urlParts.getOrNull(index).orEmpty())
        PlayLine(name = name, episodes = episodes)
    }.filter { it.episodes.isNotEmpty() }
    return disambiguateLineNames(lines)
}

private val lineSeparator = buildString(3) { repeat(3) { append('$') } }

private fun splitLines(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return if (text.contains(lineSeparator)) text.split(lineSeparator) else listOf(text)
}

fun parseEpisodes(raw: String): List<Episode> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    return text.split('#').mapIndexedNotNull { index, piece ->
        val token = piece.trim()
        if (token.isEmpty()) return@mapIndexedNotNull null
        val parts = token.split('$')
        val url = cleanPlayUrl(if (parts.size >= 2) parts[1] else parts[0])
        if (url.isBlank()) return@mapIndexedNotNull null
        val name = if (parts.size >= 2) parts[0].trim().ifBlank { "第${index + 1}集" } else {
            if (looksLikeUrl(parts[0])) "第${index + 1}集" else parts[0].trim().ifBlank { "第${index + 1}集" }
        }
        Episode(name = name, url = url)
    }
}

private fun looksLikeUrl(value: String): Boolean {
    val v = value.trim()
    return v.startsWith("http://") || v.startsWith("https://") || v.contains("://")
}

fun disambiguateLineNames(lines: List<PlayLine>): List<PlayLine> {
    val counts = lines.groupingBy { it.name }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return lines.map { line ->
        if ((counts[line.name] ?: 0) <= 1) {
            line
        } else {
            val next = (seen[line.name] ?: 0) + 1
            seen[line.name] = next
            line.copy(name = "${line.name} $next")
        }
    }
}
