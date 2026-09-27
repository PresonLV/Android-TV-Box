package app.jianxia.tv.spider

import android.util.Base64
import app.jianxia.core.spider.SpiderCall
import app.jianxia.core.spider.SpiderReply
import app.jianxia.core.spider.md5Bytes
import app.jianxia.core.spider.parseJarRef
import app.jianxia.tv.data.net.Ua
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

internal data class HttpBody(val code: Int, val bytes: ByteArray, val headers: Map<String, String>)

internal fun OkHttpClient.bytes(
    url: String,
    agents: List<String>,
    maxBytes: Int,
    timeoutMs: Long,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): HttpBody {
    var last = HttpBody(0, ByteArray(0), emptyMap())
    agents.filter { it.isNotBlank() }.distinct().forEach { agent ->
        val result = runCatching { exchange(url, agent, maxBytes, timeoutMs, method, headers, body) }.getOrNull()
        if (result != null) {
            last = result
            if (result.code in 200..299 && result.bytes.isNotEmpty()) return result
            if (result.code != 403 && result.code != 401) return result
        }
    }
    return last
}

private fun OkHttpClient.exchange(
    url: String,
    agent: String,
    maxBytes: Int,
    timeoutMs: Long,
    method: String,
    headers: Map<String, String>,
    body: String,
): HttpBody {
    val client = newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    val builder = Request.Builder().url(url)
    val headerAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
    builder.header("User-Agent", headerAgent?.ifBlank { agent } ?: agent)
    headers.forEach { (key, value) ->
        if (value.isNotBlank() && !key.equals("User-Agent", true)) builder.header(key, value)
    }
    if (method != "GET" && method != "HEAD") {
        val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            ?: if (body.trim().startsWith("{") || body.trim().startsWith("[")) "application/json; charset=utf-8"
            else "application/x-www-form-urlencoded"
        builder.method(method, body.toRequestBody(type.toMediaType()))
    }
    client.newCall(builder.build()).execute().use { response ->
        val stream = response.body?.byteStream()
        val buffer = ByteArray(maxBytes)
        var size = 0
        if (stream != null) {
            while (size < maxBytes) {
                val read = stream.read(buffer, size, maxBytes - size)
                if (read < 0) break
                size += read
            }
        }
        val headersOut = linkedMapOf<String, String>()
        for (index in 0 until response.headers.size) {
            val name = response.headers.name(index)
            val value = response.headers.value(index)
            val previous = headersOut[name]
            headersOut[name] = when {
                previous == null -> value
                name.equals("Set-Cookie", true) -> "$previous; $value"
                else -> "$previous, $value"
            }
        }
        return HttpBody(response.code, buffer.copyOf(size), headersOut)
    }
}

internal fun OkHttpClient.spiderReply(call: SpiderCall): SpiderReply {
    val agents = listOf(
        call.headers["User-Agent"].orEmpty(),
        Ua.CONFIG,
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
    )
    val result = bytes(call.url, agents, 2_000_000, call.timeoutMs.toLong(), call.method, call.headers, call.body)
    val text = if (call.binary) Base64.encodeToString(result.bytes, Base64.NO_WRAP) else decodeText(result.bytes)
    return SpiderReply(result.code, text, result.headers)
}

private fun decodeText(bytes: ByteArray): String {
    return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
}

internal class JarCache(private val dir: File, private val http: OkHttpClient) {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun ready(raw: String): Boolean {
        val ref = parseJarRef(raw) ?: return false
        val name = (ref.md5 ?: md5Bytes(ref.url.toByteArray())) + ".jar"
        val target = File(dir, name)
        return target.isFile && target.length() > 4
    }

    fun text(url: String, userAgent: String): String {
        val loaded = http.bytes(url, listOf(userAgent, Ua.CONFIG, "Mozilla/5.0"), 1_500_000, 20_000)
        if (loaded.code !in 200..299 || loaded.bytes.isEmpty()) return ""
        return runCatching { String(loaded.bytes, Charsets.UTF_8) }.getOrDefault("")
    }

    fun file(raw: String, userAgent: String): File {
        val ref = parseJarRef(raw) ?: throw IllegalStateException("爬虫 JAR 地址无效")
        val lock = locks.getOrPut(ref.md5 ?: ref.url) { Any() }
        synchronized(lock) {
            dir.mkdirs()
            val name = (ref.md5 ?: md5Bytes(ref.url.toByteArray())) + ".jar"
            val target = File(dir, name)
            if (target.isFile && target.length() > 0 && (ref.md5 == null || md5Bytes(target.readBytes()) == ref.md5)) {
                return target
            }
            val loaded = http.bytes(
                ref.url,
                listOf(userAgent, Ua.CONFIG, "Mozilla/5.0"),
                32_000_000,
                45_000,
            )
            if (loaded.code !in 200..299 || loaded.bytes.size < 4 || loaded.bytes[0] != 'P'.code.toByte() || loaded.bytes[1] != 'K'.code.toByte()) {
                throw IllegalStateException("爬虫 JAR 下载失败（${loaded.code}）")
            }
            val bytes = loaded.bytes
            val digest = md5Bytes(bytes)
            if (ref.md5 != null && digest != ref.md5) throw IllegalStateException("爬虫 JAR 校验不一致")
            val named = if (ref.md5 != null) target else File(dir, "$digest.jar")
            named.writeBytes(bytes)
            return named
        }
    }
}
