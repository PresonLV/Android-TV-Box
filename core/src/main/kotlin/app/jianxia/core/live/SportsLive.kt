package app.jianxia.core.live

import app.jianxia.core.line.lineScore
import app.jianxia.core.model.LineProbe
import app.jianxia.core.model.LiveChannel

data class SportsEntry(
    val name: String,
    val lines: List<LiveChannel>,
)

/**
 * 直播页顶部的「体育」快捷分类。
 * 只合并已经启用的直播源，以及 iptv-org 公布的体育分类列表。
 * 不写入任何付费体育信号地址。
 */
object SportsLive {
    const val LABEL = "体育"

    private val sportsWord = Regex("""(?i)(^|[^a-z])sports([^a-z]|$)""")
    private val chineseRegion = Regex("""\.(cn|hk|tw|mo)(@|$)""")
    private val cctv5Plus = Regex("""cctv5(\+|plus)""")
    private val cctv5 = Regex("""cctv5([^0-9]|$)""")

    fun playlistUrl(): String = IptvOrg.playlist("category", "sports")

    fun aggregate(fromSources: List<LiveChannel>, fromSportsPlaylist: List<LiveChannel>): List<SportsEntry> {
        val groups = linkedMapOf<String, MutableList<LiveChannel>>()
        fromSources.filter { matchesSports(it.name, it.group) }.forEach { add(groups, it) }
        fromSportsPlaylist.filter { includeFromPublicSportsList(it) }.forEach { add(groups, it) }
        return groups.map { (key, lines) ->
            SportsEntry(displayName(key, lines.map { it.name }), preferWorking(lines))
        }.sortedWith(compareBy<SportsEntry> { priority(it) }.thenBy { it.name })
    }

    fun matchesSports(name: String, group: String): Boolean {
        val blob = "$name $group"
        if (blob.contains("体育")) return true
        val key = mergeKey(name)
        if (key == "cctv5" || key == "cctv5+") return true
        return sportsWord.containsMatchIn(blob)
    }

    fun looksChinese(channel: LiveChannel): Boolean {
        val id = channel.tvgId.orEmpty().lowercase()
        if (chineseRegion.containsMatchIn(id)) return true
        return channel.name.any { it.code in 0x4E00..0x9FFF }
    }

    /** 测速之后把更快的线路放到前面。没测到的排在失败线路之前。 */
    fun orderLines(lines: List<LiveChannel>, probes: List<LineProbe>): List<LiveChannel> {
        val byUrl = probes.associateBy { it.id }
        return lines.withIndex().sortedWith(
            compareBy<IndexedValue<LiveChannel>> { item ->
                val probe = byUrl[item.value.url]
                when {
                    probe == null -> Long.MAX_VALUE - 1
                    else -> lineScore(probe)
                }
            }.thenBy { it.index },
        ).map { it.value }
    }

    private fun includeFromPublicSportsList(channel: LiveChannel): Boolean {
        if (looksChinese(channel)) return true
        val key = mergeKey(channel.name)
        return key == "cctv5" || key == "cctv5+" || channel.name.contains("体育")
    }

    private fun add(groups: LinkedHashMap<String, MutableList<LiveChannel>>, channel: LiveChannel) {
        val key = mergeKey(channel.name)
        val bucket = groups.getOrPut(key) { mutableListOf() }
        if (bucket.none { it.url == channel.url }) bucket += channel
    }

    private fun preferWorking(lines: List<LiveChannel>): List<LiveChannel> =
        lines.withIndex()
            .sortedWith(compareBy<IndexedValue<LiveChannel>> { if (IptvOrg.listedAsBlocked(it.value.name)) 1 else 0 }.thenBy { it.index })
            .map { it.value }

    private fun priority(entry: SportsEntry): Int = when (mergeKey(entry.name)) {
        "cctv5" -> 0
        "cctv5+" -> 1
        else -> if (entry.lines.any { looksChinese(it) } || entry.name.any { it.code in 0x4E00..0x9FFF }) 2 else 3
    }

    private fun displayName(key: String, samples: List<String>): String = when (key) {
        "cctv5" -> "CCTV-5"
        "cctv5+" -> "CCTV-5+"
        else -> samples.minWithOrNull(compareBy<String> { name -> if (name.any { it.code in 0x4E00..0x9FFF }) 0 else 1 }.thenBy { it.length })
            ?.let(::cleanLabel)
            ?: key
    }

    private fun cleanLabel(name: String): String =
        name.replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun mergeKey(raw: String): String {
        var text = raw.lowercase()
        text = text.replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
        text = text.replace(Regex("""\b(hd|sd|fhd|uhd|4k|\d{3,4}p)\b"""), " ")
        text = text.replace(Regex("[\\s_\\-]+"), "")
        return when {
            cctv5Plus.containsMatchIn(text) -> "cctv5+"
            cctv5.containsMatchIn(text) -> "cctv5"
            else -> text
        }
    }
}
