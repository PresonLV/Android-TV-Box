package app.jianxia.core

import app.jianxia.core.danmaku.DanmakuApi
import app.jianxia.core.danmaku.DanmakuAnime
import app.jianxia.core.danmaku.DanmakuEpisode
import app.jianxia.core.danmaku.DanmakuMode
import app.jianxia.core.danmaku.DanmakuParse
import app.jianxia.core.douban.DoubanCard
import app.jianxia.core.douban.DoubanCatalog
import app.jianxia.core.douban.DoubanParse
import app.jianxia.core.douban.DoubanProxy
import app.jianxia.core.hls.HlsAdFilter
import app.jianxia.core.subtitle.SubAlign
import app.jianxia.core.subtitle.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceTest {
    @Test
    fun doubanCategoriesAndProxy() {
        assertTrue(DoubanCatalog.movie.tags.containsAll(listOf("热门", "豆瓣高分", "华语", "动作", "科幻", "悬疑")))
        assertTrue(DoubanCatalog.tv.tags.containsAll(listOf("热门", "美剧", "韩剧", "国产剧", "日本动画", "纪录片")))
        val category = DoubanProxy.categoryUrl("movie", "热门", 0, 20)
        assertTrue(category.startsWith("https://movie.douban.com/j/search_subjects?"))
        assertTrue(category.contains("tag="))
        assertEquals(
            "https://img3.doubanio.com/view/a.jpg",
            DoubanProxy.imageUrl(DoubanProxy.IMG3, "", "https://img9.doubanio.com/view/a.jpg"),
        )
        assertEquals(
            "https://img1.doubanio.com/a.jpg",
            DoubanProxy.imageUrl(DoubanProxy.DIRECT, "", "https://img1.doubanio.com/a.jpg"),
        )
        assertEquals(
            "https://proxy.example.test/?u=https%3A%2F%2Fmovie.douban.com%2Fj%2Fsubject_suggest%3Fq%3D1",
            DoubanProxy.dataUrl(
                DoubanProxy.CUSTOM,
                "https://proxy.example.test/?u={url}",
                "https://movie.douban.com/j/subject_suggest?q=1",
            ),
        )
        assertEquals(
            "https://movie.douban.com/j/search_subjects?type=movie&tag=a",
            DoubanProxy.dataUrl(DoubanProxy.IMG3, "", "https://movie.douban.com/j/search_subjects?type=movie&tag=a"),
        )
    }

    @Test
    fun doubanMatchCommentsAndDetail() {
        val cards = DoubanParse.cards(
            """
            [{"id":"11","title":"山海灯市","year":"2019","img":"https://img1.doubanio.com/a.jpg","type":"movie"},
             {"id":"22","title":"山海灯市","year":"2024","img":"https://img2.doubanio.com/b.jpg","rate":"8.6"}]
            """.trimIndent(),
        )
        assertEquals("22", DoubanParse.match(cards, "山海灯市", "2024")?.id)
        assertEquals("11", DoubanParse.match(cards, "山海灯市", "2019")?.id)
        val listed = DoubanParse.cards(
            """{"subjects":[{"id":"9","title":"晚潮","rate":"7.5","cover":"https://img3.doubanio.com/c.jpg","card_subtitle":"2022 / 中国大陆"}]}""",
        )
        assertEquals("2022", listed.single().year)
        assertEquals("7.5", listed.single().rating)
        val detail = DoubanParse.detail(
            """{"id":"22","title":"山海灯市","year":"2024","intro":"夜里点灯","rating":{"value":8.2},"directors":[{"name":"周宁"}],"actors":[{"name":"林晚"},{"name":"陈穗"}],"pic":{"large":"https://img1.doubanio.com/p.jpg"}}""",
        )
        assertEquals("周宁", detail?.directors?.single())
        assertEquals(listOf("林晚", "陈穗"), detail?.actors)
        assertEquals("8.2", detail?.rating)
        val page = DoubanParse.comments(
            """
            <html><body><h2>全部 30 条</h2>
            <div class="comment-item" data-cid="7">
              <span class="avatar"><a title="甲"></a></span>
              <span class="rating allstar40"></span>
              <span class="short">很好看</span>
              <span class="comment-time" title="2024-01-02"></span>
              <span class="vote-count">3</span>
            </div>
            </body></html>
            """.trimIndent(),
            0,
            20,
        )
        assertEquals("很好看", page.comments.single().text)
        assertEquals(4, page.comments.single().stars)
        assertTrue(page.hasMore)
        assertNull(DoubanParse.match(listOf(DoubanCard("1", "天气预报", "2020")), "山海灯市", "2024"))
    }

    @Test
    fun hlsRemovesInsertedAdsAndKeepsTheFilm() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            http://cdn.example.test/1.ts
            #EXTINF:10.0,
            http://cdn.example.test/2.ts
            #EXTINF:10.0,
            http://cdn.example.test/3.ts
            #EXTINF:10.0,
            http://cdn.example.test/4.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4.0,
            http://ads.example.test/spot.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10.0,
            http://cdn.example.test/5.ts
            #EXTINF:10.0,
            http://cdn.example.test/6.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val rewritten = HlsAdFilter.rewrite(playlist, "http://cdn.example.test/index.m3u8", emptyList())
        assertFalse(rewritten.text.contains("ads.example.test"))
        assertTrue(rewritten.text.contains("http://cdn.example.test/1.ts"))
        assertTrue(rewritten.text.contains("http://cdn.example.test/6.ts"))
        assertEquals(1, rewritten.removed)
        val plain = """
            #EXTM3U
            #EXTINF:6.0,
            http://cdn.example.test/a.ts
            #EXTINF:6.0,
            http://cdn.example.test/b.ts
            #EXTINF:6.0,
            http://cdn.example.test/c.ts
            #EXTINF:6.0,
            http://cdn.example.test/d.ts
        """.trimIndent()
        assertEquals(0, HlsAdFilter.rewrite(plain, "http://cdn.example.test/v.m3u8", emptyList()).removed)
        val ruled = HlsAdFilter.rewrite(
            plain.replace("/b.ts", "/b-ad-slice.ts"),
            "http://cdn.example.test/v.m3u8",
            HlsAdFilter.compileRules("ad-slice\n[bad"),
        )
        assertFalse(ruled.text.contains("ad-slice"))
        assertTrue(ruled.text.contains("/a.ts"))
        val master = HlsAdFilter.rewrite(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2
            https://cdn.example.test/high.m3u8
            """.trimIndent(),
            "https://cdn.example.test/master.m3u8",
            emptyList(),
        ) { "proxy:$it" }
        assertTrue(master.master)
        assertTrue(master.text.contains("proxy:https://cdn.example.test/low.m3u8"))
        assertTrue(master.text.contains("proxy:https://cdn.example.test/high.m3u8"))
    }

    @Test
    fun danmakuApiHasNoBuiltinHostAndPicksEpisode() {
        assertNull(DanmakuApi.url("", "87654321", "/api/v2/search/anime"))
        assertEquals(
            "http://127.0.0.1:9321/secret/api/v2/match",
            DanmakuApi.url("http://127.0.0.1:9321", "secret", DanmakuApi.matchPath()),
        )
        assertEquals(
            "http://box.example.test:9321/api/v2/comment/9?format=xml",
            DanmakuApi.url("http://box.example.test:9321/", "", DanmakuApi.commentPath(9)),
        )
        val cues = DanmakuParse.cues(
            """<i><d p="12.5,1,25,16777215,0,0,0,1">你好</d><d p="3,5,25,255,0,0,0,2">顶</d><d p="4,4,25,16711680,0,0,0,3">广告词</d></i>""",
        )
        assertEquals(DanmakuMode.Scroll, cues.first { it.text == "你好" }.mode)
        assertEquals(DanmakuMode.Top, cues.first { it.text == "顶" }.mode)
        assertEquals(DanmakuMode.Bottom, cues.first { it.text == "广告词" }.mode)
        val kept = cues.filter { !DanmakuParse.blocked(it.text, listOf("广告")) }
        assertEquals(listOf("顶", "你好"), kept.map { it.text })
        val episodes = listOf(DanmakuEpisode(1, "第1集"), DanmakuEpisode(2, "第2集"))
        assertEquals(2L, DanmakuParse.pickEpisode(episodes, "第2集", 0)?.episodeId)
        assertEquals(1L, DanmakuParse.pickEpisode(episodes, "", 0)?.episodeId)
        val animes = DanmakuParse.animes("""{"animes":[{"animeId":7,"animeTitle":"青瓷信札","episodeCount":2}]}""")
        assertEquals(7L, DanmakuParse.pickAnime(animes, "青瓷信札")?.animeId)
        assertNull(DanmakuParse.pickAnime(listOf(DanmakuAnime(1, "别的片子")), "青瓷信札"))
        val hits = DanmakuParse.hits("""{"isMatched":true,"matches":[{"episodeId":5,"animeId":7,"animeTitle":"青瓷信札","episodeTitle":"第1集"}]}""")
        assertEquals(5L, hits.single().episodeId)
    }

    @Test
    fun subtitlesParseSrtVttAndAss() {
        val srt = Subtitles.parse(
            "a.srt",
            "1\n00:00:01,000 --> 00:00:03,000\n第一句\n\n2\n00:00:04,000 --> 00:00:05,000\n第二句\n",
        )
        assertEquals("第一句", Subtitles.visible(srt, 1500, 0).single().text)
        assertEquals("第二句", Subtitles.visible(srt, 4000, 500).single().text)
        val vtt = Subtitles.parse("a.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 line:0%\n顶部\n")
        assertEquals(SubAlign.Top, vtt.single().align)
        val ass = Subtitles.parse(
            "a.ass",
            """
            [V4+ Styles]
            Format: Name, Alignment, PrimaryColour
            Style: Default, 8, &H000000FF
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\an2\c&H00FF00&}你好\N世界
            """.trimIndent(),
        )
        assertEquals("你好\n世界", ass.single().text)
        assertEquals(SubAlign.Bottom, ass.single().align)
        assertEquals(0x00FF00, ass.single().color)
        assertTrue(Subtitles.parse("film.sup", "not text").isEmpty())
    }
}
