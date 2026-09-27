package app.jianxia.core.spider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class SpiderCall(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutMs: Int,
    val binary: Boolean,
)

data class SpiderReply(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
)

fun interface SpiderFetcher {
    fun fetch(call: SpiderCall): SpiderReply
}

/** QuickJS 绑定需要接口。方法名和脚本里的 host.xxx 对应。 */
interface JsBridge {
    fun req(url: String, optionsJson: String): String
    fun pdfh(html: String, rule: String): String
    fun pdfa(html: String, rule: String): String
    fun pd(html: String, rule: String, base: String): String
    fun joinUrl(base: String, ref: String): String
    fun localGet(scope: String, key: String): String
    fun localSet(scope: String, key: String, value: String)
    fun localDelete(scope: String, key: String)
    fun proxyUrl(): String
    fun log(message: String)
    fun md5(text: String): String
    fun b64enc(text: String): String
    fun b64dec(text: String): String
    fun jsonPath(path: String, payload: String): String
    fun jinja(template: String, payload: String): String
}

interface SpiderStore {
    fun get(scope: String, key: String): String
    fun set(scope: String, key: String, value: String)
    fun delete(scope: String, key: String)
}

class MemorySpiderStore : SpiderStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(scope: String, key: String): String = map["$scope\u0000$key"].orEmpty()
    override fun set(scope: String, key: String, value: String) {
        map["$scope\u0000$key"] = value
    }
    override fun delete(scope: String, key: String) {
        map.remove("$scope\u0000$key")
    }
}

/**
 * JS 爬虫能调用的宿主方法。QuickJS 只负责求值，网页、HTML 和摘要都在这里完成。
 * 不支持：OCR 验证码、本地文件读写、Worker 线程、以及脚本自己再起的原生模块。
 */
class JsHost(
    private val http: SpiderFetcher,
    private val store: SpiderStore = MemorySpiderStore(),
    private val proxyUrl: () -> String = { "http://127.0.0.1:0/proxy?do=js" },
    private val enabled: () -> Boolean = { true },
    private val logger: (String) -> Unit = {},
) : JsBridge {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun req(url: String, optionsJson: String): String {
        if (!enabled()) throw IllegalStateException("爬虫已关闭")
        val options = runCatching { json.parseToJsonElement(optionsJson) as? JsonObject }.getOrNull()
        val method = options.text("method").ifBlank { "GET" }.uppercase()
        val headers = linkedMapOf<String, String>()
        (options?.get("headers") as? JsonObject)?.forEach { (key, value) ->
            value.asString()?.let { headers[key] = it }
        }
        val body = when {
            options?.get("body") is JsonPrimitive -> options["body"].asString().orEmpty()
            options?.get("body") is JsonObject || options?.get("body") is JsonArray -> options["body"].toString()
            options?.get("data") is JsonPrimitive -> options["data"].asString().orEmpty()
            options?.get("data") is JsonObject || options?.get("data") is JsonArray -> options["data"].toString()
            else -> ""
        }
        val timeout = options.text("timeout").toIntOrNull()?.coerceIn(1000, 20_000) ?: 12_000
        val binary = options.text("buffer") == "2"
        val reply = try {
            http.fetch(SpiderCall(url, method, headers, body, timeout, binary))
        } catch (error: Exception) {
            SpiderReply(0, "", emptyMap())
        }
        val headerJson = reply.headers.entries.joinToString(",") { (key, value) ->
            "${quote(key)}:${quote(value)}"
        }
        return """{"code":${reply.code},"content":${quote(reply.body)},"headers":{$headerJson}}"""
    }

    override fun pdfh(html: String, rule: String): String = HtmlRules.pdfh(html, rule)

    override fun pdfa(html: String, rule: String): String {
        val items = HtmlRules.pdfa(html, rule)
        return items.joinToString(prefix = "[", postfix = "]") { quote(it) }
    }

    override fun pd(html: String, rule: String, base: String): String = HtmlRules.pd(html, rule, base)

    override fun joinUrl(base: String, ref: String): String = HtmlRules.joinUrl(base, ref)

    override fun localGet(scope: String, key: String): String = store.get(scope, key)

    override fun localSet(scope: String, key: String, value: String) {
        store.set(scope, key, value)
    }

    override fun localDelete(scope: String, key: String) {
        store.delete(scope, key)
    }

    override fun proxyUrl(): String = proxyUrl.invoke()

    override fun log(message: String) {
        logger(message.take(500))
    }

    override fun md5(text: String): String = md5Bytes(text.toByteArray())

    override fun b64enc(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    override fun b64dec(text: String): String = runCatching {
        String(Base64.getDecoder().decode(text))
    }.getOrDefault("")

    override fun jsonPath(path: String, payload: String): String {
        val found = JsonPath.read(payload, path) ?: return "null"
        return found.toString()
    }

    override fun jinja(template: String, payload: String): String = JsonPath.jinja(template, payload)

    private fun JsonObject?.text(key: String): String = this?.get(key).asString().orEmpty()

    private fun JsonElement?.asString(): String? = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

fun md5Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

object JsonPath {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val token = Regex("""\.([A-Za-z0-9_\u0080-\uFFFF]+)|\[(\d+|\*)\]|\[['"]([^'"]+)['"]\]""")

    fun read(payload: String, path: String): JsonElement? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return null
        val expr = path.trim().removePrefix("$")
        return walk(root, expr)
    }

    fun jinja(template: String, payload: String): String {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        return Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*\}\}""").replace(template) { match ->
            val path = match.groupValues[1]
            val value = if (root == null) null else walk(root, ".$path")
            when (value) {
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                null, is JsonNull -> ""
                else -> value.toString()
            }
        }
    }

    private fun walk(start: JsonElement, expr: String): JsonElement? {
        var current = listOf(start)
        token.findAll(if (expr.startsWith(".") || expr.startsWith("[")) expr else ".$expr").forEach { match ->
            val field = match.groupValues[1].ifBlank { match.groupValues[3] }
            val index = match.groupValues[2]
            current = when {
                index == "*" -> current.flatMap { element ->
                    (element as? JsonArray)?.toList().orEmpty()
                }
                index.isNotEmpty() -> current.mapNotNull { element ->
                    (element as? JsonArray)?.getOrNull(index.toInt())
                }
                else -> current.mapNotNull { element ->
                    (element as? JsonObject)?.get(field)
                }
            }
        }
        return when (current.size) {
            0 -> null
            1 -> current.first()
            else -> JsonArray(current)
        }
    }
}
