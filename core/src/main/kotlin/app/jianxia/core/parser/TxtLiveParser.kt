package app.jianxia.core.parser

import app.jianxia.core.model.LiveChannel

object TxtLiveParser {
    fun parse(raw: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        var group = "默认"
        cleanDocument(raw).replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#EXT")) return@forEach
            if (line.contains("#genre#", ignoreCase = true)) {
                group = line.substringBefore(",").substringBefore("，").trim().ifBlank { "未分组" }
                return@forEach
            }
            val splitAt = line.indexOfAny(charArrayOf(',', '，'))
            if (splitAt <= 0) return@forEach
            val name = line.substring(0, splitAt).trim()
            val url = line.substring(splitAt + 1).trim()
            if (name.isEmpty() || !url.startsWith("http", ignoreCase = true)) return@forEach
            channels += LiveChannel(name = name, url = url, group = group)
        }
        return channels
    }
}
