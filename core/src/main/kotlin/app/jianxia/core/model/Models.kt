package app.jianxia.core.model

import kotlinx.serialization.Serializable

/** 点播站点实现类型。爬虫默认不执行，打开设置后才按 JAR 或 JS 加载。 */
@Serializable
enum class SiteKind {
    MACCMS_XML,
    MACCMS_JSON,
    SPIDER,
    UNSUPPORTED,
}

@Serializable
enum class SpiderMode {
    JAR,
    JS,
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
    val spiderMode: SpiderMode? = null,
    val spiderExt: String = "",
    val spiderJar: String = "",
    val filters: Map<String, List<FilterGroup>> = emptyMap(),
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

@Serializable
data class FilterChoice(val name: String, val value: String)

@Serializable
data class FilterGroup(val key: String, val name: String, val choices: List<FilterChoice>)

data class TvBoxConfig(
    val sites: List<VodSiteDef>,
    val lives: List<LiveSourceDef>,
    val parses: List<ParseDef>,
    val wallpaper: String? = null,
    val spider: String = "",
    val filters: Map<String, Map<String, List<FilterGroup>>> = emptyMap(),
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
    val score: String? = null,
    val lines: List<PlayLine> = emptyList(),
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
    val spiderMode: String = "",
    val spiderExt: String = "",
    val spiderJar: String = "",
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
    val filters: Map<String, List<FilterGroup>> = emptyMap(),
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
    val score: String? = null,
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

/** 首页每个站点的结果，用来解释为什么没有海报。 */
data class SiteReport(
    val id: String,
    val configName: String,
    val siteName: String,
    val status: String,
    val detail: String,
)

object HomeSiteSummary {
    fun message(total: Int, usable: Int, spiders: Int, failed: Int): String {
        val spiderText = if (spiders > 0) "，$spiders 个是爬虫（JAR/JS）未打开" else ""
        return "共 $total 个站点：$usable 个可用$spiderText，$failed 个加载失败"
    }

    /** 每个站点只计入一种结果，避免「100 个可用，100 个失败」。 */
    fun fromReports(reports: List<SiteReport>): String {
        val ok = reports.count { it.status == "可用" }
        val failed = reports.count { it.status == "失败" }
        val closed = reports.count { it.status == "爬虫" }
        val hidden = reports.count { it.status == "已隐藏" }
        val other = (reports.size - ok - failed - closed - hidden).coerceAtLeast(0)
        val parts = mutableListOf("$ok 个可用")
        if (closed > 0) parts += "$closed 个爬虫未打开"
        if (hidden > 0) parts += "$hidden 个直播类已隐藏"
        if (other > 0) parts += "$other 个不支持"
        parts += "$failed 个加载失败"
        return "共 ${reports.size} 个站点：" + parts.joinToString("，")
    }
}
