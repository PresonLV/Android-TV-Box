package app.jianxia.core

import app.jianxia.core.model.LineProbe
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodItem
import app.jianxia.core.backup.BackupCodec
import app.jianxia.core.line.lineScore
import app.jianxia.core.line.rankLines
import app.jianxia.core.merge.CategoryMatcher
import app.jianxia.core.model.HomeSiteSummary
import app.jianxia.core.parser.macCmsBrowseActions
import app.jianxia.core.merge.mergeKey
import app.jianxia.core.merge.mergeVodItems
import app.jianxia.core.merge.normalizeTitle
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.HomeRowSetting
import app.jianxia.core.parser.DetectedSource
import app.jianxia.core.parser.M3uParser
import app.jianxia.core.parser.MacCmsJsonParser
import app.jianxia.core.parser.MacCmsXmlParser
import app.jianxia.core.parser.SourceDetector
import app.jianxia.core.parser.TvBoxConfigParser
import app.jianxia.core.parser.TxtLiveParser
import app.jianxia.core.parser.XmlTvParser
import app.jianxia.core.parser.decodeBytes
import app.jianxia.core.parser.extractMediaUrl
import app.jianxia.core.parser.isDirectMediaUrl
import app.jianxia.core.parser.macCmsUrl
import app.jianxia.core.parser.parsePlayInfo
import app.jianxia.core.parser.parseXmltvTime
import app.jianxia.core.pinyin.PinyinIme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class ParserTest {
    @Test
    fun tvboxJsonKeepsUnsupportedSpiders() {
        val config = TvBoxConfigParser.parse(fixture("tvbox.json"))
        assertEquals(4, config.sites.size)
        assertEquals(SiteKind.MACCMS_XML, config.sites[0].kind)
        assertEquals(SiteKind.MACCMS_JSON, config.sites[1].kind)
        assertFalse(config.sites[0].quickSearch)
        assertTrue(config.sites[1].quickSearch)
        assertEquals(SiteKind.SPIDER, config.sites[2].kind)
        assertEquals(app.jianxia.core.model.SpiderMode.JAR, config.sites[2].spiderMode)
        assertEquals("x", config.sites[2].spiderExt)
        assertNull(config.sites[2].unsupportedReason)
        assertEquals(SiteKind.UNSUPPORTED, config.sites[3].kind)
        assertEquals("直播", config.lives.single().name)
        assertEquals("https://example.test/epg.xml", config.lives.single().epgUrl)
        assertTrue(config.parses[0].supported)
        assertFalse(config.parses[1].supported)
        assertEquals("https://example.test/wall.jpg", config.wallpaper)
    }

    @Test
    fun macCmsJsonParsesLinesAndClasses() {
        val page = MacCmsJsonParser.parse(fixture("maccms.json"), "src", "示例", "https://example.test/api")
        assertEquals(1, page.page)
        assertEquals(2, page.pageCount)
        assertEquals("电影", page.classes.first().name)
        val movie = page.items.first()
        assertEquals("山海灯市", movie.title)
        assertEquals("https://img.example.test/a.jpg", movie.pic)
        assertEquals("海边的灯市。", movie.content)
        assertEquals(2, movie.lines.size)
        assertEquals("https://cdn.example.test/a.m3u8", movie.lines[0].episodes.single().url)
        assertEquals(2, page.items[1].lines.single().episodes.size)
    }

    @Test
    fun macCmsXmlParsesCdataAndDuplicateFlags() {
        val page = MacCmsXmlParser.parse(fixture("maccms.xml"), "src", "示例", "https://example.test/xml")
        assertEquals(listOf("电影", "电视剧"), page.classes.map { it.name })
        val video = page.items.single()
        assertEquals("15", video.id)
        assertEquals("山海灯市", video.title)
        assertEquals(2, video.lines.size)
        assertEquals("线路1 1", video.lines[0].name)
        assertEquals("花絮", video.lines[0].episodes[1].name)
        assertEquals(SiteKind.MACCMS_XML, video.siteKind)
    }

    @Test
    fun playInfoWithoutNames() {
        val lines = parsePlayInfo("只此一线", "https://cdn.example.test/a.mp4#https://cdn.example.test/b.mp4")
        assertEquals("第1集", lines.single().episodes[0].name)
        assertEquals("https://cdn.example.test/b.mp4", lines.single().episodes[1].url)
    }

    @Test
    fun m3uAndTxt() {
        val m3u = M3uParser.parse(fixture("live.m3u"))
        assertEquals(3, m3u.size)
        assertEquals("演示", m3u[0].group)
        assertEquals("demo1", m3u[0].tvgId)
        assertEquals("https://img.example.test/1.png", m3u[0].logo)
        assertEquals("其他", m3u[2].group)
        val txt = TxtLiveParser.parse(fixture("live.txt"))
        assertEquals(listOf("演示组", "演示组", "另一组"), txt.map { it.group })
        assertTrue(txt.all { it.url.startsWith("https://") })
    }

    @Test
    fun xmltvCurrentProgramme() {
        assertEquals(1_704_081_600_000L, parseXmltvTime("20240101120000 +0800"))
        val guide = XmlTvParser.parse(fixture("epg.xml"))
        val channel = M3uParser.parse(fixture("live.m3u")).first()
        val (now, next) = guide.nowAndNext(channel, 1_704_081_600_000L + 60_000)
        assertEquals("灯市新闻", now?.title)
        assertEquals("下一档", next?.title)
        assertNull(guide.nowAndNext(channel, 1_700_000_000_000L).first)
    }

    @Test
    fun detectorRecognizesEachFormat() {
        assertTrue(SourceDetector.detect(fixture("tvbox.json")) is DetectedSource.TvBox)
        assertTrue(SourceDetector.detect(fixture("maccms.json")) is DetectedSource.MacCmsJson)
        assertTrue(SourceDetector.detect(fixture("maccms.xml")) is DetectedSource.MacCmsXml)
        val live = SourceDetector.detect(fixture("live.m3u")) as DetectedSource.Live
        assertEquals(3, live.channelCount)
        assertTrue(SourceDetector.detect(fixture("live.txt")) is DetectedSource.Live)
        assertTrue(SourceDetector.detect(fixture("segments.m3u8")).reason().contains("单个视频流"))
        assertTrue(SourceDetector.detect("<html>nope</html>") is DetectedSource.Unknown)
    }

    @Test
    fun gbkXmlDeclarationWins() {
        val xml = """<?xml version="1.0" encoding="gbk"?><rss><list><video><name>测试</name></video></list></rss>"""
        val bytes = xml.toByteArray(Charset.forName("GB18030"))
        assertTrue(decodeBytes(bytes, "text/xml; charset=utf-8").contains("测试"))
        assertTrue(decodeBytes(bytes, null).contains("测试"))
    }

    @Test
    fun macCmsUrlMergesQuery() {
        val url = macCmsUrl(
            "https://example.test/api.php/provide/vod/?ac=list&pg=1",
            mapOf("ac" to "detail", "ids" to "5", "wd" to "山海"),
        )
        assertTrue(url.startsWith("https://example.test/api.php/provide/vod/?"))
        assertTrue(url.contains("ac=detail"))
        assertTrue(url.contains("pg=1"))
        assertTrue(url.contains("ids=5"))
        assertTrue(url.contains("wd=%E5%B1%B1%E6%B5%B7"))
    }

    @Test
    fun extractDirectMediaFromParseJson() {
        assertTrue(isDirectMediaUrl("https://cdn.example.test/a.m3u8?token=1"))
        assertFalse(isDirectMediaUrl("https://example.test/play/123"))
        val found = extractMediaUrl("""{"code":200,"data":{"url":"https://cdn.example.test/a.m3u8"}}""")
        assertEquals("https://cdn.example.test/a.m3u8", found)
        assertNull(extractMediaUrl("""{"url":"https://example.test/page"}"""))
    }

    private fun DetectedSource.reason(): String = (this as DetectedSource.Unknown).reason

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/$name")) { name }.readText()
}

class MergeRankTest {
    @Test
    fun mergesSameTitleAndKeepsDifferentYears() {
        val first = item("山海灯市", "2024", "甲")
        val second = item("山海灯市 ", "2024", "乙")
        val third = item("山海灯市（2023）", null, "丙")
        val fourth = item("Ｓｈａｎ Ｈａｉ", "2024", "丁")
        val merged = mergeVodItems(listOf(first, second, third, fourth))
        assertEquals(3, merged.size)
        assertEquals(2, merged.first().variants.size)
        assertEquals(listOf("甲", "乙"), merged.first().variants.map { it.sourceName })
        assertEquals(mergeKey("山海灯市", "2024"), merged.first().key)
        assertTrue(normalizeTitle("山海灯市 HD") == normalizeTitle("山海灯市"))
    }

    @Test
    fun homeSummaryCountsSpidersSeparatelyFromFailures() {
        assertEquals(
            "共 53 个站点：9 个可用，44 个是爬虫（JAR/JS）未打开，3 个加载失败",
            HomeSiteSummary.message(53, 9, 44, 3),
        )
        assertEquals(
            "共 47 个站点：0 个可用，47 个是爬虫（JAR/JS）未打开，0 个加载失败",
            HomeSiteSummary.message(47, 0, 47, 0),
        )
        assertEquals("共 2 个站点：2 个可用，0 个加载失败", HomeSiteSummary.message(2, 2, 0, 0))
        val reports = listOf(
            app.jianxia.core.model.SiteReport("a", "cfg", "甲", "可用", "3 条"),
            app.jianxia.core.model.SiteReport("b", "cfg", "乙", "失败", "超时"),
            app.jianxia.core.model.SiteReport("c", "cfg", "虎牙", "已隐藏", "直播"),
        )
        assertEquals(
            "共 3 个站点：1 个可用，1 个直播类已隐藏，1 个加载失败",
            HomeSiteSummary.fromReports(reports),
        )
        assertTrue(app.jianxia.core.spider.LiveSites.matches("🐯虎牙┃直播", "虎牙js", "csp_Huya"))
        assertTrue(!app.jianxia.core.spider.LiveSites.matches("豆豆┃片单", "点我切源", "csp_DouDouGuard"))
        assertEquals(
            "UnsatisfiedLinkError: dlopen failed",
            app.jianxia.core.spider.SpiderFault.explain(IllegalStateException("爬虫执行失败", UnsatisfiedLinkError("dlopen failed"))),
        )
        assertEquals(
            listOf("https://img3.doubanio.com/a.jpg", "https://img1.doubanio.com/a.jpg", "https://img2.doubanio.com/a.jpg", "https://img9.doubanio.com/a.jpg"),
            app.jianxia.core.douban.PosterUrls.candidates("https://img9.doubanio.com/a.jpg").take(4),
        )
        assertEquals("exo", app.jianxia.core.player.preferredEngineWire("vlc", false, listOf("armeabi-v7a")))
        assertEquals("vlc", app.jianxia.core.player.preferredEngineWire("vlc", true, listOf("armeabi-v7a")))
        assertFalse(AppSettings().spiderEnabled)
    }

    @Test
    fun browseTriesListBeforeVideolist() {
        assertEquals(listOf("list", "videolist"), macCmsBrowseActions(SiteKind.MACCMS_JSON))
        assertEquals(listOf("list", "videolist"), macCmsBrowseActions(SiteKind.MACCMS_XML))
        val url = macCmsUrl("https://example.test/api.php/provide/vod/?ac=list", mapOf("ac" to "videolist", "pg" to "1"))
        assertEquals("https://example.test/api.php/provide/vod/?ac=videolist&pg=1", url)
    }

    @Test
    fun categoryMatcher() {
        assertTrue(CategoryMatcher.matches("movie", "动作片"))
        assertFalse(CategoryMatcher.matches("movie", "纪录片"))
        assertTrue(CategoryMatcher.matches("tv", "国产剧"))
        assertTrue(CategoryMatcher.matches("anime", "动画片"))
        assertTrue(CategoryMatcher.matches("doc", "纪录片"))
        assertFalse(CategoryMatcher.matches("variety", "电影"))
    }

    @Test
    fun ranksFastHighLinesAheadOfFailures() {
        val probes = listOf(
            LineProbe("slow", connectMs = 800, firstByteMs = 800, resolutionHeight = 1080, ok = true),
            LineProbe("dead", connectMs = 20, firstByteMs = 20, resolutionHeight = 1080, ok = false),
            LineProbe("fast-sd", connectMs = 200, firstByteMs = 80, resolutionHeight = 480, ok = true),
            LineProbe("fast-hd", connectMs = 220, firstByteMs = 80, resolutionHeight = 1080, ok = true),
        )
        val ranked = rankLines(probes)
        assertEquals(listOf("fast-hd", "fast-sd", "slow", "dead"), ranked.map { it.id })
        assertEquals(Long.MAX_VALUE, lineScore(probes[1]))
    }

    @Test
    fun equalScoresKeepInputOrder() {
        val probes = listOf(
            LineProbe("a", 100, 100, null, true),
            LineProbe("b", 100, 100, null, true),
        )
        assertEquals(listOf("a", "b"), rankLines(probes).map { it.id })
    }

    @Test
    fun settingsSanitizeAndBackupRoundTrip() {
        val settings = AppSettings(
            themeMode = "nope",
            accent = "gold",
            searchTimeoutSec = 99,
            homeRows = listOf(HomeRowSetting("movie", "电影", false)),
            recentSearches = listOf("山海", "山海", "  "),
        ).sanitized()
        assertEquals("dark", settings.themeMode)
        assertEquals("#E2B15A", settings.accent)
        assertEquals(30, settings.searchTimeoutSec)
        assertEquals("movie", settings.homeRows.first().id)
        assertFalse(settings.homeRows.first().visible)
        assertTrue(settings.homeRows.any { it.id == "history" })
        assertEquals(listOf("山海"), settings.recentSearches)
        assertEquals("vlc", AppSettings().sanitized().playerEngine)
        assertEquals("exo", AppSettings(playerEngine = "exo").sanitized().playerEngine)
        assertEquals("vlc", AppSettings(playerEngine = "nope").sanitized().playerEngine)
        val migrated = AppSettings(backgroundType = "gradient", gradientId = "ocean", wallpaperId = "").sanitized()
        assertEquals("builtin", migrated.backgroundType)
        assertEquals("ocean", migrated.wallpaperId)
        assertEquals("system", AppSettings(themeMode = "system").sanitized().themeMode)
        val reset = migrated.copy(fontScale = "xlarge", wallpaperBlur = 20).resetSection("look").sanitized()
        assertEquals("medium", reset.fontScale)
        assertEquals(0, reset.wallpaperBlur)
        assertEquals(12, app.jianxia.core.model.AppearanceCatalog.wallpapers.size)
        assertEquals("cinema", AppSettings().sanitized().homeLayout)
        assertEquals("classic", AppSettings(homeLayout = "classic").sanitized().homeLayout)
        assertEquals("cinema", AppSettings(homeLayout = "unknown").sanitized().homeLayout)
        val homeReset = AppSettings(homeLayout = "classic", reduceMotion = true).resetSection("home").sanitized()
        assertEquals("cinema", homeReset.homeLayout)
        assertEquals(false, homeReset.reduceMotion)
        val raw = BackupCodec.encode(
            app.jianxia.core.model.BackupBundle(
                settings = settings,
                sources = listOf(
                    app.jianxia.core.model.BackupSource("示例", "https://example.test/a", "tvbox"),
                    app.jianxia.core.model.BackupSource("空", "  ", "live"),
                ),
            ),
        )
        val decoded = BackupCodec.decode(raw)
        assertEquals(1, decoded.sources.size)
        assertEquals("https://example.test/a", decoded.sources.single().url)
    }

    @Test
    fun tvboxHeadersStayOnSiteAndLive() {
        val config = TvBoxConfigParser.parse(
            """
            {
              "sites": [
                {
                  "key": "headed",
                  "name": "带请求头",
                  "type": 1,
                  "api": "https://example.test/api",
                  "ua": "DemoUA/1.0",
                  "header": {"Referer": "https://example.test/", "Cookie": "a=b", "User-Agent": "HeaderUA"}
                },
                {"key": "csp_spider", "name": "爬虫源", "type": 3, "api": "csp_Demo"}
              ],
              "lives": [
                {
                  "name": "直播",
                  "type": 0,
                  "url": "https://example.test/live.m3u",
                  "header": "{\"User-Agent\":\"LiveUA\",\"Referer\":\"https://live.example/\",\"X-Token\":\"t\"}"
                }
              ]
            }
            """.trimIndent(),
        )
        val site = config.sites.first { it.key == "headed" }
        assertEquals("HeaderUA", site.userAgent)
        assertEquals("https://example.test/", site.referer)
        assertEquals("a=b", site.headers["Cookie"])
        assertFalse(site.headers.keys.any { it.equals("User-Agent", true) || it.equals("Referer", true) })
        assertEquals(SiteKind.SPIDER, config.sites.first { it.key == "csp_spider" }.kind)
        assertNull(config.sites.first { it.key == "csp_spider" }.unsupportedReason)
        val live = config.lives.single()
        assertEquals("LiveUA", live.userAgent)
        assertEquals("https://live.example/", live.referer)
        assertEquals("t", live.headers["X-Token"])
    }

    @Test
    fun tvboxFiltersAttachToTheNamedSite() {
        val config = TvBoxConfigParser.parse(
            """
            {
              "sites": [
                {"key": "cms", "name": "苹果", "type": 1, "api": "https://example.test/api.php/provide/vod"}
              ],
              "filters": {
                "cms": {
                  "1": [
                    {"key": "area", "name": "地区", "value": [{"n": "全部", "v": ""}, {"n": "大陆", "v": "大陆"}]},
                    {"key": "year", "name": "年份", "value": [{"n": "2024", "v": "2024"}]}
                  ]
                },
                "missing": {"1": [{"key": "class", "name": "类型", "value": [{"n": "喜剧", "v": "喜剧"}]}]}
              }
            }
            """.trimIndent(),
        )
        val groups = config.filters["cms"].orEmpty()["1"].orEmpty()
        assertEquals(listOf("area", "year"), groups.map { it.key })
        assertEquals(listOf("大陆"), groups.first().choices.map { it.value })
        assertEquals("喜剧", config.filters["missing"].orEmpty()["1"].orEmpty().single().choices.single().name)
    }

    @Test
    fun pinyinCandidatesPreferExactSyllable() {
        val ime = PinyinIme.loadDefault()
        assertEquals("山", ime.candidates("shan").first())
        assertTrue(ime.candidates("n").contains("你"))
        assertTrue(ime.candidates("").isEmpty())
    }

    private fun item(title: String, year: String?, source: String) = VodItem(
        sourceKey = source,
        sourceName = source,
        api = "https://example.test",
        siteKind = SiteKind.MACCMS_JSON,
        id = title,
        title = title,
        year = year,
    )
}
