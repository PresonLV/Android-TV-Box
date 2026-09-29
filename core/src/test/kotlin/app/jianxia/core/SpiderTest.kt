package app.jianxia.core

import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.parser.TvBoxConfigParser
import app.jianxia.core.spider.HtmlRules
import app.jianxia.core.spider.JsHost
import app.jianxia.core.spider.JsModules
import app.jianxia.core.spider.JsonPath
import app.jianxia.core.spider.SpiderCall
import app.jianxia.core.spider.SpiderJson
import app.jianxia.core.spider.SpiderReply
import app.jianxia.core.spider.jarClassNames
import app.jianxia.core.spider.parseJarRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderTest {
    private val site = VodSiteDef("s", "示例", SiteKind.SPIDER, "csp_Demo", spiderMode = SpiderMode.JAR)

    @Test
    fun jarRefReadsLabeledMd5() {
        val ref = parseJarRef("https://example.test/a.jar;md5;e959d9455533a04af01a15c4eb1b78ea")
        assertEquals("https://example.test/a.jar", ref?.url)
        assertEquals("e959d9455533a04af01a15c4eb1b78ea", ref?.md5)
        assertNull(parseJarRef("https://example.test/b.jar;abc")?.md5)
        assertEquals(
            "aabbccddeeff00112233445566778899",
            parseJarRef("https://example.test/b.jar;AABBCCDDEEFF00112233445566778899")?.md5,
        )
        assertNull(parseJarRef("csp_Demo"))
        assertEquals(
            listOf("com.github.catvod.spider.Demo", "com.github.catvod.spider.csp_Demo", "csp_Demo"),
            jarClassNames("csp_Demo"),
        )
    }

    @Test
    fun htmlRulesReadTextAndLinks() {
        val html = """<div class="box"><a href="/play/1">标题</a><img data-src="/p.jpg"><ul><li>一</li><li>二</li></ul></div>"""
        assertEquals("标题", HtmlRules.pdfh(html, "a&&Text"))
        assertEquals("/p.jpg", HtmlRules.pdfh(html, ".box&&img&&data-src"))
        assertEquals("二", HtmlRules.pdfh(html, "li:eq(1)&&Text"))
        assertTrue(HtmlRules.pdfa(html, "li").size == 2)
        assertEquals("https://example.test/play/1", HtmlRules.pd(html, "a&&href", "https://example.test/dir/"))
        assertEquals("https://cdn.example/a.jpg", HtmlRules.joinUrl("https://example.test/a", "//cdn.example/a.jpg"))
    }

    @Test
    fun spiderJsonMapsListsAndPlays() {
        val page = SpiderJson.page(
            """{"class":[{"type_id":"1","type_name":"电影"}],"list":[{"vod_id":"9","vod_name":"山海","vod_pic":"https://img.example/a.jpg","vod_remarks":"更新"}]}""",
            site,
        )
        assertEquals("电影", page.classes.single().name)
        assertEquals("山海", page.items.single().title)
        val filtered = SpiderJson.page(
            """{"class":[{"type_id":"1","type_name":"电影"}],"filter":{"1":[{"key":"class","name":"类型","value":[{"n":"全部","v":""},{"n":"喜剧","v":"喜剧"}]}]},"list":[{"vod_id":"9","vod_name":"山海","vod_score":"8.6"}]}""",
            site,
        )
        assertEquals("喜剧", filtered.filters["1"].orEmpty().single().choices.single().name)
        assertEquals("8.6", filtered.items.single().score)
        val skipped = SpiderJson.page("""{"list":[{"vod_id":"no_data","vod_name":"无数据"}]}""", site)
        assertTrue(skipped.items.isEmpty())
        val detail = SpiderJson.detail(
            "{\"list\":[{\"vod_id\":\"9\",\"vod_name\":\"山海\",\"vod_play_from\":\"线1\$\$\$线2\",\"vod_play_url\":\"第1集\$http://a/1.m3u8#第2集\$http://a/2.m3u8\$\$\$正片\$http://b/x.mp4\"}]}",
            site,
            "9",
        )
        assertEquals(2, detail?.lines?.size)
        assertEquals("http://a/2.m3u8", detail?.lines?.first()?.episodes?.get(1)?.url)
        val play = SpiderJson.play("""{"parse":0,"jx":0,"url":"proxy://do=js","header":{"User-Agent":"UA","Referer":"https://example.test/"}}""")
        assertEquals("proxy://do=js", play.url)
        assertEquals("UA", play.userAgent)
        assertEquals("https://example.test/", play.referer)
        val stringHeader = SpiderJson.play("""{"parse":1,"jx":1,"url":"https://jx.example/p","header":"{\"User-Agent\":\"UA2\",\"Referer\":\"https://r.example/\"}"}""")
        assertEquals(1, stringHeader.parse)
        assertEquals(1, stringHeader.jx)
        assertEquals("UA2", stringHeader.userAgent)
        assertEquals("https://r.example/", stringHeader.referer)
        val withPic = SpiderJson.page(
            """{"list":[{"vod_id":"1","vod_name":"海报","vod_pic":"/a.jpg@Referer=https://pic.example/"}]}""",
            site.copy(api = "https://api.example/vod", referer = "https://site.example/"),
        )
        assertEquals("https://api.example/a.jpg@Referer=https://pic.example/", withPic.items.single().pic)
        val zeroYear = SpiderJson.detail(
            """{"list":[{"vod_id":3,"vod_name":"无可替代","vod_year":0,"vod_play_from":null,"vod_play_url":""}]}""",
            site,
            "3",
        )
        assertEquals("无可替代", zeroYear?.title)
        assertNull(zeroYear?.year)
        assertTrue(zeroYear?.lines.orEmpty().isEmpty())
        val numeric = SpiderJson.detail(
            "{\"list\":[{\"vod_id\":8,\"vod_name\":\"数字\",\"vod_year\":\"2026\",\"vod_play_from\":\"夸父原1\",\"vod_play_url\":\"[1.96GB] 01.mkv\$share-1#02.mkv\$share-2\"}]}",
            site,
            "8",
        )
        assertEquals("2026", numeric?.year)
        assertEquals("share-1", numeric?.lines?.single()?.episodes?.first()?.url)
        assertEquals("网盘", app.jianxia.core.spider.PlayText.pendingLabel("夸父原1", "share-1"))
        assertNull(app.jianxia.core.spider.PlayText.pendingLabel("线路1", "https://cdn.example/a.m3u8"))
        val rooted = SpiderJson.detail(
            "{\"vod_id\":\"9\",\"vod_name\":\"根对象\",\"vod_play_from\":\"线A\$\$\$线B\",\"vod_play_url\":\"1\$http://a/1#2\$http://a/2\$\$\$正片\$http://b/x.mkv\"}",
            site,
            "9",
        )
        assertEquals(2, rooted?.lines?.size)
        assertEquals("http://b/x.mkv", rooted?.lines?.get(1)?.episodes?.single()?.url)
        val objects = SpiderJson.detail(
            """{"list":[{"vod_id":"1","vod_name":"数组","vod_play_list":[{"flag":"夸父","urls":[{"name":"01.mkv","url":"fid"}]}]}]}""",
            site,
            "1",
        )
        assertEquals("fid", objects?.lines?.single()?.episodes?.single()?.url)
        val prefixed = SpiderJson.play("""{"parse":true,"jx":false,"url":"page","playUrl":"https://jx.example/?url="}""")
        assertEquals("https://jx.example/?url=page", prefixed.url)
        assertEquals(1, prefixed.parse)
        assertEquals(0, prefixed.jx)
        val wrapped = SpiderJson.page("\"{\\\"list\\\":[{\\\"vod_id\\\":\\\"1\\\",\\\"vod_name\\\":\\\"转义\\\"}]}\"", site)
        assertEquals("转义", wrapped.items.single().title)
    }

    @Test
    fun proxyRewriteRangeAndCookies() {
        val base = "http://127.0.0.1:9978/proxy"
        val rewritten = app.jianxia.core.spider.PlayText.rewriteProxy(
            "玩偶",
            "http://127.0.0.1:1111/proxy?do=quark&url=abc",
            base,
        )
        assertTrue(rewritten.startsWith("http://127.0.0.1:9978/proxy?site="))
        assertTrue(rewritten.contains("do=quark"))
        assertTrue(rewritten.contains("url=abc"))
        val kept = app.jianxia.core.spider.PlayText.rewriteProxy("s", "https://cdn.example/a.mkv", base)
        assertEquals("https://cdn.example/a.mkv", kept)
        val bytes = "0123456789".toByteArray()
        val slice = app.jianxia.core.spider.PlayText.slice(bytes, "bytes=2-5")
        assertEquals(206, slice.code)
        assertEquals("2345", slice.body.toString(Charsets.UTF_8))
        assertEquals(416, app.jianxia.core.spider.PlayText.slice(bytes, "bytes=20-").code)
        val merged = app.jianxia.core.spider.PlayText.mergeCookies(
            """{"Cloud-drive":"http://example.test/a.txt","token":"keep"}""",
            "qcookie",
            "",
            "atok",
        )
        assertTrue(merged.contains("\"token\":\"keep\""))
        assertTrue(merged.contains("qcookie"))
        assertTrue(merged.contains("atok"))
        assertEquals("https://site.example/ext", app.jianxia.core.spider.PlayText.mergeCookies("https://site.example/ext", "q", "u", "a"))
        val cacheDir = java.nio.file.Files.createTempDirectory("jx-cache").toFile()
        val cache = app.jianxia.core.cache.TextCache(cacheDir)
        cache.write("home", "cached-home")
        val started = System.nanoTime()
        assertEquals("cached-home", cache.read("home"))
        val hitMs = (System.nanoTime() - started) / 1_000_000
        val slow = System.nanoTime()
        Thread.sleep(40)
        val missMs = (System.nanoTime() - slow) / 1_000_000
        assertTrue("cache hit ${hitMs}ms should beat a fresh 40ms load", hitMs < missMs)
        cacheDir.deleteRecursively()
    }

    @Test
    fun type3KeepsJarExtAndJs() {
        val config = TvBoxConfigParser.parse(
            """
            {"spider":"./base.jar;md5;aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "sites":[
               {"key":"j","name":"JAR","type":3,"api":"csp_Demo","ext":{"token":"t"},"jar":"https://example.test/own.jar"},
               {"key":"s","name":"JS","type":3,"api":"./drpy2.min.js","ext":"./rule.js"},
               {"key":"plain","name":"苹果","type":1,"api":"https://example.test/vod","jar":"https://example.test/unused.jar"}
             ]}
            """.trimIndent(),
            "https://example.test/box/cfg.json",
        )
        val jar = config.sites.first { it.key == "j" }
        assertEquals(SiteKind.SPIDER, jar.kind)
        assertEquals(SpiderMode.JAR, jar.spiderMode)
        assertEquals("https://example.test/own.jar", jar.spiderJar)
        assertTrue(jar.spiderExt.contains("token"))
        assertNull(jar.unsupportedReason)
        val js = config.sites.first { it.key == "s" }
        assertEquals(SpiderMode.JS, js.spiderMode)
        assertEquals("https://example.test/box/drpy2.min.js", js.api)
        assertEquals("https://example.test/box/rule.js", js.spiderExt)
        val drive = TvBoxConfigParser.parse(
            """{"sites":[{"key":"w","name":"玩偶","type":3,"api":"csp_WoGG","ext":{"Cloud-drive":"tvfan/Cloud-drive.txt","token":"t"}}]}""",
            "http://www.example.test/tv",
        ).sites.single()
        assertTrue(drive.spiderExt.contains("http://www.example.test/tvfan/Cloud-drive.txt"))
        assertTrue(drive.spiderExt.contains("\"token\":\"t\""))
        assertEquals(SiteKind.MACCMS_JSON, config.sites.first { it.key == "plain" }.kind)
        assertEquals("https://example.test/box/base.jar;md5;aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", config.spider)
    }

    @Test
    fun modulesStripImportsAndKeepBindings() {
        val entry = """import cheerio from"https://example.test/cheerio.js";import"https://example.test/crypto.js";function home(){return "ok"}export default{home:home};"""
        val script = JsModules.assemble("https://example.test/drpy.js", entry) { url ->
            when {
                url.endsWith("cheerio.js") -> "export{box as default,path as jp}; function box(){return 1} function path(){return 2}"
                else -> "var CryptoJS = {MD5:function(){return {toString:function(){return 'm'}}}};"
            }
        }
        assertFalse(script.contains("import "))
        assertFalse(script.contains("export "))
        val assets = JsModules.assemble("https://example.test/main/drpy.js", """import cheerio from"assets://js/lib/cheerio.min.js";export default{home:home};function home(){return "{}"}""") { url ->
            assertEquals("https://example.test/main/cheerio.min.js", url)
            "export{box as default}; function box(){return 1}"
        }
        assertFalse(Regex("""\bexport\b""").containsMatchIn(assets))
        assertFalse(assets.contains("https://example.test/main/cheerio.min.js"))
        assertTrue(assets.contains("var cheerio ="))
        assertTrue(script.contains("var cheerio ="))
        assertTrue(script.contains("var CryptoJS ="))
        assertTrue(script.contains("function home()"))
        assertTrue(script.contains("__jx_home"))
        assertEquals("https://example.test/?c=t", JsonPath.jinja("https://example.test/?c={{fl.area}}", """{"fl":{"area":"t"}}"""))
    }

    @Test
    fun hostRequestStopsWhenDisabled() {
        var hits = 0
        val host = JsHost(
            http = { hits += 1; SpiderReply(200, "x", emptyMap()) },
            enabled = { false },
        )
        val failed = runCatching { host.req("https://example.test", "{}") }
        assertTrue(failed.isFailure)
        assertEquals(0, hits)
        val open = JsHost(http = { call: SpiderCall -> SpiderReply(200, call.url, mapOf("A" to "b")) })
        val raw = open.req("https://example.test/a", """{"method":"GET"}""")
        assertTrue(raw.contains("example.test"))
        assertTrue(open.proxyUrl().startsWith("http://127.0.0.1"))
        assertEquals("标题", open.pdfh("<a>标题</a>", "a&&Text"))
    }
}
