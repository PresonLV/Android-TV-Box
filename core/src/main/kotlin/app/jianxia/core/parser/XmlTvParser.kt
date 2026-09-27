package app.jianxia.core.parser

import app.jianxia.core.model.EpgGuide
import app.jianxia.core.model.EpgProgramme
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

object XmlTvParser {
    fun parse(raw: String): EpgGuide {
        val document = Jsoup.parse(cleanDocument(raw), "", Parser.xmlParser())
        val names = linkedMapOf<String, String>()
        document.select("channel").forEach { channel ->
            val id = channel.attr("id").trim()
            if (id.isEmpty()) return@forEach
            val display = channel.select("display-name")
            val name = display.firstOrNull { it.attr("lang").lowercase().startsWith("zh") }?.text()
                ?: display.firstOrNull()?.text()
            if (!name.isNullOrBlank()) names[id] = name.trim()
        }
        val programmes = document.select("programme").mapNotNull { programme ->
            val channel = programme.attr("channel").trim()
            val title = programme.selectFirst("title")?.text()?.trim().orEmpty()
            val start = parseXmltvTime(programme.attr("start"))
            val stop = parseXmltvTime(programme.attr("stop"))
            if (channel.isEmpty() || title.isEmpty() || start == null || stop == null || stop <= start) {
                null
            } else {
                EpgProgramme(channel, title, start, stop)
            }
        }
        return EpgGuide(names, programmes)
    }
}

fun parseXmltvTime(raw: String): Long? {
    val match = Regex("""^(\d{14})(?:\s*([+-]\d{4}|Z))?$""").find(raw.trim()) ?: return null
    val zone = match.groupValues[2].ifBlank { "+0800" }.let { if (it == "Z") "+0000" else it }
    val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    return runCatching { format.parse("${match.groupValues[1]} $zone")?.time }.getOrNull()
}
