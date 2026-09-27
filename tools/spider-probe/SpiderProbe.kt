package app.jianxia.tools

import app.cash.quickjs.QuickJs
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.parser.ConfigDecoder
import app.jianxia.core.parser.TvBoxConfigParser
import app.jianxia.core.spider.JsBridge
import app.jianxia.core.spider.JsHost
import app.jianxia.core.spider.JsModules
import app.jianxia.core.spider.SpiderCall
import app.jianxia.core.spider.SpiderJson
import app.jianxia.core.spider.SpiderReply
import java.net.IDN
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 在电脑上用 QuickJS 跑配置里的 JS 爬虫，统计首页、分类、搜索和播放。
 * 地址只从命令行传入，不写进应用。
 */
fun main(args: Array<String>) {
    val urls = args.toList().ifEmpty { listOf("http://www.饭太硬.net/tv", "http://xhztv.top/4k.json") }
    urls.forEach { url ->
        println("======== $url")
        val fetched = fetch(url)
        val text = ConfigDecoder.decode(fetched.bytes, fetched.type)
        val config = TvBoxConfigParser.parse(text, fetched.finalUrl)
        val js = config.sites.filter { it.kind == SiteKind.SPIDER && it.spiderMode == SpiderMode.JS }
        val jar = config.sites.filter { it.kind == SiteKind.SPIDER && it.spiderMode == SpiderMode.JAR }
        println("sites=${config.sites.size} js=${js.size} jar=${jar.size}")
        var home = 0
        var category = 0
        var search = 0
        var play = 0
        js.forEach { site ->
            val result = runSite(site)
            if (result.home) home += 1
            if (result.category) category += 1
            if (result.search) search += 1
            if (result.play) play += 1
            println("${site.name} home=${result.home} category=${result.category} search=${result.search} play=${result.play} ${result.note}")
        }
        println("JS summary home=$home/${js.size} category=$category/${js.size} search=$search/${js.size} play=$play/${js.size}")
    }
}

private data class Outcome(val home: Boolean, val category: Boolean, val search: Boolean, val play: Boolean, val note: String)

private fun runSite(site: VodSiteDef): Outcome {
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(12)).build()
    val host = JsHost(
        http = { call -> httpFetch(http, call) },
        proxyUrl = { "http://127.0.0.1:9/proxy?do=js" },
    )
    val quick = QuickJs.create()
    stepNotes.get().setLength(0)
    return try {
        quick.set("host", JsBridge::class.java, host)
        quick.evaluate(JsModules.PRELUDE, "prelude.js")
        val source = text(http, site.api)
        val script = JsModules.assemble(site.api, source) { url -> text(http, url) }
        quick.evaluate(script, "spider.js")
        step("init") { quick.evaluate("__jx_init(${quote(site.spiderExt)})", "init.js") }
        val homeJson = step("home") { quick.evaluate("__jx_home(true)", "home.js")?.toString().orEmpty() }.orEmpty()
        val homePage = SpiderJson.page(homeJson, site)
        val homeOk = homePage.classes.isNotEmpty() || homePage.items.isNotEmpty()
        val categoryPage = homePage.classes.firstOrNull()?.let { type ->
            val raw = step("category") {
                quick.evaluate("__jx_category(${quote(type.id)}, 1, \"{}\")", "cate.js")?.toString().orEmpty()
            }.orEmpty()
            SpiderJson.page(raw, site)
        }
        val categoryOk = categoryPage?.items?.isNotEmpty() == true
        val keyword = (categoryPage?.items?.firstOrNull()?.title ?: homePage.items.firstOrNull()?.title)
            ?.take(2)?.ifBlank { null } ?: "爱"
        val searchRaw = step("search") { quick.evaluate("__jx_search(${quote(keyword)}, 1)", "search.js")?.toString().orEmpty() }.orEmpty()
        val searchOk = SpiderJson.page(searchRaw, site).items.isNotEmpty()
        val playOk = step("play") { playSample(quick, site, homePage.items.firstOrNull() ?: categoryPage?.items?.firstOrNull()) } == true
        val note = "classes=${homePage.classes.size} homeItems=${homePage.items.size} cateItems=${categoryPage?.items?.size ?: 0}"
        Outcome(homeOk, categoryOk, searchOk, playOk, note + notes())
    } catch (error: Throwable) {
        Outcome(false, false, false, false, blame(error) + notes())
    } finally {
        runCatching { quick.close() }
    }
}

private val stepNotes = ThreadLocal.withInitial { StringBuilder() }

private fun notes(): String = stepNotes.get().toString()

private fun <T> step(name: String, block: () -> T): T? = try {
    block()
} catch (error: Throwable) {
    stepNotes.get().append(" $name=").append(blame(error))
    null
}

private fun playSample(quick: QuickJs, site: VodSiteDef, sample: app.jianxia.core.model.VodItem?): Boolean {
    if (sample == null) return false
    val direct = sample.lines.firstOrNull()?.episodes?.firstOrNull()
    val episode = if (direct != null) {
        sample.lines.first().name to direct.url
    } else {
        val raw = quick.evaluate("__jx_detail(${quote(sample.id)})", "detail.js")?.toString().orEmpty()
        val detail = SpiderJson.detail(raw, site, sample.id)
        val line = detail?.lines?.firstOrNull() ?: return false
        val url = line.episodes.firstOrNull()?.url ?: return false
        line.name to url
    }
    val raw = quick.evaluate("__jx_play(${quote(episode.first)}, ${quote(episode.second)})", "play.js")?.toString().orEmpty()
    return SpiderJson.play(raw).url.isNotBlank()
}

private fun blame(error: Throwable): String {
    val where = error.stackTrace.firstOrNull()?.let { frame ->
        frame.className.substringAfterLast('.') + ":" + frame.lineNumber
    }.orEmpty()
    val message = error.message?.take(120) ?: error.javaClass.simpleName
    return if (where.isBlank()) message else "$message @$where"
}

private fun httpFetch(http: HttpClient, call: SpiderCall): SpiderReply {
    val request = HttpRequest.newBuilder(URI.create(call.url))
        .timeout(Duration.ofMillis(call.timeoutMs.toLong().coerceAtMost(15_000)))
        .header("User-Agent", call.headers["User-Agent"] ?: "Mozilla/5.0")
        .apply {
            call.headers.forEach { (key, value) -> if (!key.equals("User-Agent", true) && value.isNotBlank()) header(key, value) }
            if (call.method == "GET") GET() else method(call.method, HttpRequest.BodyPublishers.ofString(call.body))
        }
        .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    val headers = response.headers().map().mapValues { it.value.joinToString(", ") }
    return SpiderReply(response.statusCode(), response.body(), headers)
}

private val textCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun text(http: HttpClient, url: String): String = textCache.getOrPut(url) {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", "Mozilla/5.0")
        .GET()
        .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() !in 200..299) throw IllegalStateException("脚本下载失败（${response.statusCode()}）$url")
    String(response.body(), Charsets.UTF_8)
}

private fun fetch(url: String): Remote {
    val ascii = puny(url)
    val request = HttpRequest.newBuilder(URI.create(ascii))
        .timeout(Duration.ofSeconds(25))
        .header("User-Agent", "okhttp/3.12.13")
        .GET()
        .build()
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() !in 200..299) throw IllegalStateException("配置下载失败（${response.statusCode()}）")
    return Remote(response.body(), response.headers().firstValue("content-type").orElse(null), response.uri().toString())
}

private fun puny(url: String): String {
    val scheme = url.substringBefore("://")
    val rest = url.substringAfter("://")
    val host = rest.substringBefore("/").substringBefore(":")
    val ascii = IDN.toASCII(host)
    return if (ascii == host) url else "$scheme://${rest.replaceFirst(host, ascii)}"
}

private fun quote(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

private data class Remote(val bytes: ByteArray, val type: String?, val finalUrl: String)
