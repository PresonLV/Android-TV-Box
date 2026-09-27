package app.jianxia.core

import app.jianxia.core.live.SportsLive
import app.jianxia.core.model.LineProbe
import app.jianxia.core.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveTest {
    @Test
    fun publicSportsListIsTheIptvOrgCategoryPlaylist() {
        assertEquals(
            "https://iptv-org.github.io/iptv/categories/sports.m3u",
            SportsLive.playlistUrl(),
        )
    }

    @Test
    fun cctv5AndPlusStaySeparateAndSortFirst() {
        val sources = listOf(
            channel("五星体育", "https://example.test/wuxing", "体育"),
            channel("CCTV-5+ HD", "https://example.test/plus-a", "Sports"),
            channel("CCTV5+", "https://example.test/plus-b", "体育"),
            channel("新闻", "https://example.test/news", "News"),
            channel("CCTV-5 (1080p)", "https://example.test/five-a", "综合"),
            channel("CCTV5 HD", "https://example.test/five-b", "Sports"),
        )
        val playlist = listOf(
            channel("beIN Sports", "https://example.test/bein", "Sports", tvgId = "BeinSports.qa@HD"),
            channel("CCTV-5+", "https://example.test/plus-a", "Sports", tvgId = "CCTV5Plus.cn@SD"),
            channel("Jiangsu Sports", "https://example.test/js", "Sports", tvgId = "JiangsuSports.cn@SD"),
        )
        val entries = SportsLive.aggregate(sources, playlist)
        assertEquals(listOf("CCTV-5", "CCTV-5+", "Jiangsu Sports", "五星体育"), entries.map { it.name })
        assertEquals(listOf("https://example.test/five-a", "https://example.test/five-b"), entries[0].lines.map { it.url })
        assertEquals(listOf("https://example.test/plus-a", "https://example.test/plus-b"), entries[1].lines.map { it.url })
        assertFalse(entries.any { it.lines.any { line -> line.url.contains("bein") } })
        assertFalse(entries.any { it.name == "新闻" })
    }

    @Test
    fun keywordsMatchGroupOrNameAndChineseNamesStay() {
        assertTrue(SportsLive.matchesSports("广东体育", "综合"))
        assertTrue(SportsLive.matchesSports("本地台", "体育"))
        assertTrue(SportsLive.matchesSports("City Sports", "General"))
        assertFalse(SportsLive.matchesSports("天气预报", "新闻"))
        assertFalse(SportsLive.matchesSports("sportscar", "Auto"))
        val merged = SportsLive.aggregate(
            listOf(channel("广东体育 HD", "https://example.test/gd", "地方")),
            emptyList(),
        )
        assertEquals("广东体育 HD", merged.single().name)
    }

    @Test
    fun fastestWorkingLineMovesFirst() {
        val lines = listOf(
            channel("CCTV-5", "https://example.test/slow", "体育"),
            channel("CCTV-5", "https://example.test/fast", "体育"),
            channel("CCTV-5", "https://example.test/dead", "体育"),
        )
        val probes = listOf(
            LineProbe("https://example.test/slow", 800, 900, 720, true),
            LineProbe("https://example.test/fast", 40, 50, 1080, true),
            LineProbe("https://example.test/dead", 10, 10, null, false),
        )
        assertEquals(
            listOf("https://example.test/fast", "https://example.test/slow", "https://example.test/dead"),
            SportsLive.orderLines(lines, probes).map { it.url },
        )
    }

    private fun channel(name: String, url: String, group: String, tvgId: String? = null) =
        LiveChannel(name = name, url = url, group = group, tvgId = tvgId)
}
