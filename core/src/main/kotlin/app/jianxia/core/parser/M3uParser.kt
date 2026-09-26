package app.jianxia.core.parser

import app.jianxia.core.model.LiveChannel

object M3uParser {
    fun parse(raw: String): List<LiveChannel> {
        val lines = cleanDocument(raw).replace("\r\n", "\n").replace('\r', '\n').lines()
        val channels = mutableListOf<LiveChannel>()
        var pending: String? = null
        var group = "未分组"
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#EXTGRP:", ignoreCase = true)) {
                group = trimmed.substringAfter(":").trim().ifBlank { "未分组" }
                continue
            }
            if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                pending = trimmed
                continue
            }
            if (trimmed.startsWith("#")) continue
            val info = pending
            pending = null
            if (info == null && channels.isEmpty() && !raw.contains("#EXTM3U", ignoreCase = true)) {
                continue
            }
            val (attrs, title) = if (info != null) splitExtinf(info) else "" to ""
            val name = title.ifBlank { attr(attrs, "tvg-name") ?: "未命名" }
            val itemGroup = attr(attrs, "group-title")?.ifBlank { null } ?: group
            channels += LiveChannel(
                name = name,
                url = trimmed,
                group = itemGroup.ifBlank { "未分组" },
                logo = attr(attrs, "tvg-logo")?.ifBlank { null },
                tvgId = attr(attrs, "tvg-id")?.ifBlank { null },
            )
        }
        return channels
    }

    /** 媒体分片列表不是频道表。 */
    fun looksLikeSegments(channels: List<LiveChannel>): Boolean {
        if (channels.size < 3) return false
        val segments = channels.count { channel ->
            val url = channel.url.lowercase()
            url.contains(".ts") || url.contains(".m4s") || url.contains(".mp4")
        }
        return segments * 2 > channels.size
    }

    private fun splitExtinf(line: String): Pair<String, String> {
        var quoted = false
        for (index in line.indices) {
            val char = line[index]
            if (char == '"') quoted = !quoted
            if (char == ',' && !quoted) {
                return line.substring(0, index) to line.substring(index + 1).trim()
            }
        }
        return line to ""
    }

    private fun attr(line: String, name: String): String? {
        val patterns = listOf(
            Regex("""$name="([^"]*)""""),
            Regex("""$name='([^']*)'"""),
        )
        return patterns.firstNotNullOfOrNull { it.find(line)?.groupValues?.get(1)?.trim() }
    }
}
