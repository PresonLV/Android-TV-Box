package app.jianxia.core.model

import kotlinx.serialization.Serializable

/** 点播站点实现类型。爬虫类站点保留在列表里，但标记为暂不支持。 */
@Serializable
enum class SiteKind {
    MACCMS_XML,
    MACCMS_JSON,
    UNSUPPORTED,
}

@Serializable
data class VodSiteDef(
    val key: String,
    val name: String,
    val kind: SiteKind,
    val api: String,
    val searchable: Boolean = true,
    val quickSearch: Boolean = true,
    val filterable: Boolean = false,
    val unsupportedReason: String? = null,
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

data class LiveSourceDef(
    val name: String,
    val url: String,
    val epgUrl: String? = null,
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

data class ParseDef(
    val name: String,
    val type: Int,
    val url: String,
) {
    /** type 0 是常见的 JSON 解析接口。嗅探 / 网页解析留待以后。 */
    val supported: Boolean get() = type == 0 && url.isNotBlank()
}

data class TvBoxConfig(
    val sites: List<VodSiteDef>,
    val lives: List<LiveSourceDef>,
    val parses: List<ParseDef>,
    val wallpaper: String? = null,
)

@Serializable
data class Episode(
    val name: String,
    val url: String,
)

@Serializable
data class PlayLine(
    val name: String,
    val episodes: List<Episode>,
)

@Serializable
data class VodItem(
    val sourceKey: String,
    val sourceName: String,
    val api: String,
    val siteKind: SiteKind,
    val id: String,
    val title: String,
    val year: String? = null,
    val pic: String? = null,
    val typeName: String? = null,
    val remarks: String? = null,
    val area: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val content: String? = null,
    val lines: List<PlayLine> = emptyList(),
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

data class VodClass(
    val id: String,
    val name: String,
    val parentId: String? = null,
)

data class VodPage(
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
    val classes: List<VodClass> = emptyList(),
    val items: List<VodItem> = emptyList(),
) {
    companion object {
        fun empty() = VodPage()
    }
}

@Serializable
data class MergedVod(
    val key: String,
    val title: String,
    val year: String? = null,
    val pic: String? = null,
    val typeName: String? = null,
    val remarks: String? = null,
    val area: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val content: String? = null,
    val variants: List<VodItem> = emptyList(),
)

data class LiveChannel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
)

data class EpgGuide(
    val displayNames: Map<String, String> = emptyMap(),
    val programmes: List<EpgProgramme> = emptyList(),
) {
    fun nowAndNext(channel: LiveChannel, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> {
        val matched = programmes
            .filter { matches(channel, it.channelId) }
            .sortedBy { it.startMs }
        if (matched.isEmpty()) return null to null
        val index = matched.indexOfFirst { nowMs in it.startMs until it.stopMs }
        if (index >= 0) {
            return matched[index] to matched.getOrNull(index + 1)
        }
        val upcoming = matched.indexOfFirst { it.startMs > nowMs }
        return null to if (upcoming >= 0) matched[upcoming] else null
    }

    private fun matches(channel: LiveChannel, programmeChannelId: String): Boolean {
        val tvg = channel.tvgId?.trim().orEmpty()
        if (tvg.isNotEmpty() && tvg.equals(programmeChannelId, ignoreCase = true)) return true
        val display = displayNames[programmeChannelId]
        if (display != null && loose(display) == loose(channel.name)) return true
        return loose(programmeChannelId) == loose(channel.name)
    }

    private fun loose(value: String): String =
        value.lowercase().replace(Regex("[\\s\\-_]+"), "")
}

/** 线路测速结果。分数越低越好，失败线路排在最后。 */
data class LineProbe(
    val id: String,
    val connectMs: Long,
    val firstByteMs: Long,
    val resolutionHeight: Int?,
    val ok: Boolean,
)

data class AggregateOutcome<T>(
    val items: List<T>,
    val successCount: Int,
    val failureCount: Int,
    val timedOutCount: Int,
)
