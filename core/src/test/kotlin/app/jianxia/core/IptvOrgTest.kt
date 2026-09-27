package app.jianxia.core

import app.jianxia.core.backup.BatchAdd
import app.jianxia.core.backup.BatchPlan
import app.jianxia.core.backup.UrlList
import app.jianxia.core.live.IptvOrg
import app.jianxia.core.model.AppSettings
import app.jianxia.core.parser.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOrgTest {
    @Test
    fun defaultPlaylistIsChinaAndNotBundledText() {
        assertEquals(
            "https://iptv-org.github.io/iptv/countries/cn.m3u",
            IptvOrg.playlist("country", "cn"),
        )
        assertEquals(
            "https://iptv-org.github.io/iptv/categories/news.m3u",
            IptvOrg.playlist("category", "news"),
        )
        assertEquals(
            "https://iptv-org.github.io/iptv/languages/zho.m3u",
            IptvOrg.playlist("language", "ZHO"),
        )
        val escaped = IptvOrg.playlist("country", "../etc/passwd")
        assertFalse(escaped.contains(".."))
        assertTrue(escaped.endsWith("/etcpasswd.m3u"))
        assertEquals("中国", IptvOrg.label("country", "cn"))
        assertEquals("新闻", IptvOrg.label("category", "news"))
        assertEquals("中文", IptvOrg.label("language", "zho"))
        assertTrue(IptvOrg.ATTRIBUTION == "频道列表来自 iptv-org")
        assertFalse(IptvOrg.fallback("category").any { it.code == "xxx" })
        val fresh = AppSettings().sanitized()
        assertFalse(fresh.iptvOrgSeeded)
        assertEquals("country", fresh.iptvOrgKind)
        assertEquals("cn", fresh.iptvOrgCode)
        assertEquals("country", AppSettings(iptvOrgKind = "vod").sanitized().iptvOrgKind)
    }

    @Test
    fun indexParserReadsPublishedPlaylistsAndSkipsAdult() {
        val markdown = """
            <tr><td>News</td><td align="right">3</td><td nowrap><code>https://iptv-org.github.io/iptv/categories/news.m3u</code></td></tr>
            <tr><td>Kids</td><td align="right">1</td><td nowrap><code>https://iptv-org.github.io/iptv/categories/kids.m3u</code></td></tr>
            <tr><td>XXX</td><td align="right">0</td><td nowrap><code>https://iptv-org.github.io/iptv/categories/xxx.m3u</code></td></tr>
            <tr><td align="left">Chinese</td><td align="right">2</td><td align="left" nowrap><code>https://iptv-org.github.io/iptv/languages/zho.m3u</code></td></tr>
            - 🇨🇳 China <code>https://iptv-org.github.io/iptv/countries/cn.m3u</code>
            - 🇺🇸 United States <code>https://iptv-org.github.io/iptv/countries/us.m3u</code>
        """.trimIndent()
        val entries = IptvOrg.parseIndex(markdown)
        assertEquals(listOf("category", "category", "language", "country", "country"), entries.map { it.kind })
        assertFalse(entries.any { it.code == "xxx" })
        val news = entries.first { it.code == "news" }
        assertEquals("新闻", news.label)
        assertEquals("https://iptv-org.github.io/iptv/categories/news.m3u", news.url)
        assertEquals("少儿", entries.first { it.code == "kids" }.label)
        assertEquals("中文", entries.first { it.code == "zho" }.label)
        assertEquals("中国", entries.first { it.code == "cn" }.label)
        assertEquals("美国", entries.first { it.code == "us" }.label)
    }

    @Test
    fun guidePrefersChineseXmlAndHeaderDeclaration() {
        val raw = """
            [
              {"channel":"CCTV1.cn","feed":"SD","lang":"fr","sources":[{"url":"https://example.test/fr.xml","format":"XML"}]},
              {"channel":"CCTV1.cn","feed":"SD","lang":"zh","sources":[{"url":"https://example.test/zh.xml","format":"XML"}]},
              {"channel":"Other.us","feed":"SD","lang":"en","sources":[{"url":"https://example.test/en.xml","format":"XML"}]}
            ]
        """.trimIndent()
        assertEquals(listOf("https://example.test/zh.xml"), IptvOrg.guideUrls(raw, listOf("CCTV1.cn@SD")))
        assertEquals(emptyList<String>(), IptvOrg.guideUrls("""[{"channel":"CCTV1.cn","sources":[]}]""", listOf("CCTV1.cn")))
        val header = """
            #EXTM3U url-tvg="https://example.test/guide.xml" x-tvg-url="https://ignored.test/no.xml"
            #EXTINF:-1 tvg-id="Demo.example@SD",演示频道
            https://cdn.example.test/demo.m3u8
        """.trimIndent()
        assertEquals("https://example.test/guide.xml", M3uParser.declaredGuide(header))
        assertNull(M3uParser.declaredGuide("#EXTM3U\n#EXTINF:-1,空\nhttps://cdn.example.test/a.m3u8"))
        assertTrue(IptvOrg.listedAsBlocked("Baicheng TV [Geo-blocked]"))
        assertFalse(IptvOrg.listedAsBlocked("Beijing Satellite TV [Not 24/7]"))
    }

    @Test
    fun batchAddExtractsMixedTextAndSkipsDuplicates() {
        val mixed = "请看 https://example.test/a.json ，还有 http://example.test/b.m3u。再写一遍 https://example.test/a.json"
        assertEquals(
            listOf("https://example.test/a.json", "http://example.test/b.m3u"),
            UrlList.findAll(mixed),
        )
        assertEquals(emptyList<String>(), UrlList.findAll("""{"sites":[{"api":"https://example.test/hidden"}]}"""))
        val planned = BatchAdd.plan(mixed, listOf("https://example.test/a.json/"))
        assertTrue(planned[0] is BatchPlan.Exists)
        assertTrue(planned[1] is BatchPlan.Fresh)
        assertEquals(2, planned.size)
        val message = BatchAdd.message(
            listOf(
                app.jianxia.core.backup.BatchLine("https://example.test/a.json", "exists", "已存在"),
                app.jianxia.core.backup.BatchLine("http://example.test/b.m3u", "ok", "成功"),
                app.jianxia.core.backup.BatchLine("http://example.test/c", "failed", "连不上服务器"),
            ),
        )
        assertTrue(message.contains("https://example.test/a.json  已存在"))
        assertTrue(message.contains("http://example.test/b.m3u  成功"))
        assertTrue(message.contains("http://example.test/c  连不上服务器"))
    }
}
