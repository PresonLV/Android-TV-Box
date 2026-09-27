package app.jianxia.tools

import app.jianxia.core.model.SiteKind
import app.jianxia.core.parser.ConfigDecoder
import app.jianxia.core.parser.MacCmsJsonParser
import app.jianxia.core.parser.MacCmsXmlParser
import app.jianxia.core.parser.TvBoxConfigParser
import app.jianxia.core.parser.macCmsUrl
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

/**
 * 用和电视端相同的解码器检查配置。只打印统计，不把地址写进应用。
 * 拉取时使用 okhttp/3.12.13，系统 DNS 失败后再走 DNS-over-HTTPS。
 */
fun main(args: Array<String>) {
    val urls = listOf("http://饭太硬.net/tv", "http://www.饭太硬.net/tv", "http://xhztv.top/4k.json")
    urls.forEach { url ->
        println("======== $url")
        try {
            val fetched = fetch(url)
            println("fetch ok bytes=${fetched.bytes.size} final=${fetched.finalUrl} via=${fetched.via} code=${fetched.code} type=${fetched.contentType}")
            analyze(fetched.finalUrl, fetched.bytes, fetched.contentType)
        } catch (error: Exception) {
            println("CONFIG FAIL ${error.javaClass.simpleName}: ${error.message}")
        }
    }
    args.forEach { path ->
        println("======== file $path")
        val bytes = java.io.File(path).readBytes()
        analyze(path, bytes, "image/jpeg")
    }
}

private fun analyze(finalUrl: String, bytes: ByteArray, contentType: String?) {
    try {
            val text = ConfigDecoder.decode(bytes, contentType)
            println("decoded chars=${text.length} head=${text.take(120).replace("\n", " ")}")
            val config = TvBoxConfigParser.parse(text, finalUrl)
            val byType = linkedMapOf<String, Int>()
            config.sites.forEach { site ->
                val label = when {
                    site.kind == SiteKind.SPIDER -> "type3-${site.spiderMode?.name ?: "?"}"
                    site.unsupportedReason?.contains("爬虫") == true -> "type3-off"
                    site.kind == SiteKind.MACCMS_XML -> "type0"
                    site.kind == SiteKind.MACCMS_JSON -> "type1"
                    else -> site.unsupportedReason ?: site.kind.name
                }
                byType[label] = (byType[label] ?: 0) + 1
            }
            val usable = config.sites.filter {
                it.unsupportedReason == null && it.kind != SiteKind.UNSUPPORTED && it.kind != SiteKind.SPIDER
            }
            println("sites=${config.sites.size} lives=${config.lives.size} parses=${config.parses.size} usable=${usable.size}")
            println("breakdown=$byType")
            val reasons = config.sites.mapNotNull { it.unsupportedReason }.groupingBy { it }.eachCount()
            println("unsupportedReasons=$reasons")
            usable.groupBy { it.kind }.forEach { (kind, sites) ->
                println("usable $kind=${sites.size}")
            }
            var withClass = 0
            var withItems = 0
            var listFail = 0
            var videoClass = 0
            var videoItems = 0
            usable.forEach { site ->
                val list = runCatching { sample(site.api, site.kind, mapOf("ac" to "list", "pg" to "1")) }
                val listPage = list.getOrNull()
                if (list.isFailure) {
                    listFail += 1
                    println("LIST FAIL ${site.name} ${site.kind} ${site.api} :: ${list.exceptionOrNull()?.message}")
                } else if (listPage != null) {
                    if (listPage.classes.isNotEmpty()) withClass += 1
                    if (listPage.items.isNotEmpty()) withItems += 1
                    println(
                        "LIST ${site.name} classes=${listPage.classes.size} items=${listPage.items.size} " +
                            "total=${listPage.total} sample=${listPage.classes.take(3).map { it.name }}",
                    )
                }
                val action = if (site.kind == SiteKind.MACCMS_XML) "videolist" else "detail"
                val video = runCatching { sample(site.api, site.kind, mapOf("ac" to action, "pg" to "1")) }
                val videoPage = video.getOrNull()
                if (video.isFailure) {
                    println("VIDEO FAIL ${site.name} ac=$action :: ${video.exceptionOrNull()?.message}")
                } else if (videoPage != null) {
                    if (videoPage.classes.isNotEmpty()) videoClass += 1
                    if (videoPage.items.isNotEmpty()) videoItems += 1
                    println("VIDEO ac=$action ${site.name} classes=${videoPage.classes.size} items=${videoPage.items.size}")
                }
            }
            println(
                "summary usable=${usable.size} listWithClass=$withClass listWithItems=$withItems listFail=$listFail " +
                    "videoWithClass=$videoClass videoWithItems=$videoItems",
            )
    } catch (error: Exception) {
        println("CONFIG FAIL ${error.javaClass.simpleName}: ${error.message}")
        error.printStackTrace(System.out)
    }
}

private data class Fetched(
    val bytes: ByteArray,
    val contentType: String?,
    val finalUrl: String,
    val code: Int,
    val via: String,
)

private fun fetch(rawUrl: String): Fetched {
    val system = runCatching { open(rawUrl, null) }
    if (system.isSuccess) return system.getOrThrow().copy(via = "system-dns")
    val ascii = punyHost(rawUrl)
    println("system dns failed for $ascii: ${system.exceptionOrNull()?.javaClass?.simpleName}: ${system.exceptionOrNull()?.message}")
    val addresses = doh(ascii)
    if (addresses.isEmpty()) throw system.exceptionOrNull() ?: UnknownHostException(ascii)
    var last: Exception = system.exceptionOrNull() as? Exception ?: UnknownHostException(ascii)
    for (address in addresses) {
        try {
            return open(rawUrl, address).copy(via = "doh:$address")
        } catch (error: Exception) {
            last = error
        }
    }
    throw last
}

private fun open(rawUrl: String, address: InetAddress?): Fetched {
    var current = rawUrl
    var hops = 0
    while (hops < 5) {
        hops += 1
        val url = URL(current)
        val connection = (if (address == null) {
            url.openConnection()
        } else {
            URL(url.protocol, address.hostAddress, url.port, url.file).openConnection()
        }) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "okhttp/3.12.13")
        if (address != null) connection.setRequestProperty("Host", punyHost(rawUrl))
        connection.connect()
        val code = connection.responseCode
        if (code in 300..399) {
            val next = connection.getHeaderField("Location") ?: throw IllegalStateException("重定向没有 Location（$code）")
            connection.disconnect()
            current = URL(url, next).toString()
            continue
        }
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readNBytes(2_000_000) } ?: ByteArray(0)
        val type = connection.contentType
        val finalUrl = connection.url.toString()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("请求失败（$code） body=${String(bytes, Charsets.UTF_8).take(120)}")
        return Fetched(bytes, type, finalUrl, code, "")
    }
    throw IllegalStateException("重定向次数过多")
}

private fun punyHost(rawUrl: String): String {
    val authority = rawUrl.substringAfter("://").substringBefore("/").substringBefore("?")
    val host = authority.substringAfter("@").substringBefore(":")
    return IDN.toASCII(host)
}

private fun doh(host: String): List<InetAddress> {
    val endpoints = listOf(
        "https://dns.alidns.com/resolve?name=$host&type=A",
        "https://cloudflare-dns.com/dns-query?name=$host&type=A",
    )
    val found = mutableListOf<InetAddress>()
    for (endpoint in endpoints) {
        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.setRequestProperty("User-Agent", "okhttp/3.12.13")
            val text = connection.inputStream.use { String(it.readNBytes(20_000), Charsets.UTF_8) }
            Regex(""""data":"(\d+\.\d+\.\d+\.\d+)"""").findAll(text).forEach { match ->
                found += InetAddress.getByName(match.groupValues[1])
            }
            if (found.isNotEmpty()) return found.distinctBy { it.hostAddress }
        } catch (_: Exception) {
        }
    }
    return found
}

private fun sample(api: String, kind: SiteKind, params: Map<String, String>): app.jianxia.core.model.VodPage {
    val url = macCmsUrl(api, params)
    val fetched = fetch(url)
    val text = ConfigDecoder.decode(fetched.bytes, fetched.contentType)
    return if (kind == SiteKind.MACCMS_XML) {
        MacCmsXmlParser.parse(text, "probe", "probe", api)
    } else {
        MacCmsJsonParser.parse(text, "probe", "probe", api)
    }
}
