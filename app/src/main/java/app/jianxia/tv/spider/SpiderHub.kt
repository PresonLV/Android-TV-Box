package app.jianxia.tv.spider

import android.content.Context
import android.util.Log
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.spider.JsHost
import app.jianxia.core.spider.MemorySpiderStore
import app.jianxia.core.spider.SpiderJson
import app.jianxia.core.spider.SpiderPlay
import app.jianxia.tv.data.net.NetClient
import com.github.catvod.net.OkHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SpiderHub(
    context: Context,
    private val http: NetClient,
    private val timeoutMs: () -> Long,
) {
    private val appContext = context.applicationContext
    @Volatile
    var enabled: Boolean = false
        private set

    private val jars = JarEngine(appContext, JarCache(java.io.File(appContext.cacheDir, "spiders"), http.http))
    private val scripts = ConcurrentHashMap<String, JsEngine>()
    private val sites = ConcurrentHashMap<String, VodSiteDef>()
    private val store = MemorySpiderStore()
    private val pool = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "spider").apply { isDaemon = true }
    }
    private val proxy = SpiderProxy { site, params -> proxy(site, params) }

    init {
        OkHttp.bind(http.http)
    }

    fun warmup(defs: List<VodSiteDef>) {
        if (!enabled) return
        val jars = defs.filter { it.spiderMode != SpiderMode.JS && it.spiderJar.isNotBlank() }
            .distinctBy { it.spiderJar }
        jars.forEach { def ->
            runCatching { this.jars.warmup(def.spiderJar, def.userAgent) }
        }
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun wipe() {
        jars.clear()
        scripts.values.forEach { runCatching { it.close() } }
        scripts.clear()
        sites.clear()
        runCatching { java.io.File(appContext.cacheDir, "spiders").deleteRecursively() }
        runCatching {
            appContext.getDir("spider_libs", Context.MODE_PRIVATE).listFiles()?.forEach { it.deleteRecursively() }
        }
        runCatching { java.io.File(appContext.codeCacheDir, "spider-opt").deleteRecursively() }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            jars.clear()
            scripts.values.forEach { runCatching { it.close() } }
            scripts.clear()
            sites.clear()
            proxy.stop()
        } else {
            proxy.start()
        }
    }

    fun home(def: VodSiteDef): VodPage = call(def) {
        if (engine(def) == Engine.JAR) jars.home(def) else script(def).home(def, timeoutMs())
    }

    fun category(def: VodSiteDef, tid: String, page: Int, extend: Map<String, String> = emptyMap()): VodPage = call(def) {
        if (engine(def) == Engine.JAR) jars.category(def, tid, page, extend)
        else script(def).category(def, tid, page, extendJson(extend), timeoutMs())
    }

    fun detail(def: VodSiteDef, id: String): VodItem? = call(def) {
        if (engine(def) == Engine.JAR) jars.detail(def, id) else script(def).detail(def, id, timeoutMs())
    }

    fun search(def: VodSiteDef, keyword: String): VodPage = call(def) {
        if (engine(def) == Engine.JAR) jars.search(def, keyword) else script(def).search(def, keyword, timeoutMs())
    }

    fun play(def: VodSiteDef, flag: String, id: String): SpiderPlay = call(def) {
        val raw = if (engine(def) == Engine.JAR) jars.play(def, flag, id) else script(def).play(def, flag, id, timeoutMs())
        raw.copy(url = proxy.expose(def.key, raw.url))
    }

    private fun proxy(site: String, params: Map<String, String>): ProxyPayload {
        if (!enabled) return ProxyPayload(403, "text/plain", "closed".toByteArray())
        val def = sites[site] ?: return ProxyPayload(404, "text/plain", "missing".toByteArray())
        return try {
            val value = if (engine(def) == Engine.JAR) {
                jars.proxy(def, params)
            } else {
                script(def).proxy(def, json.encodeToString(params), timeoutMs())
            }
            payload(value)
        } catch (error: Throwable) {
            ProxyPayload(500, "text/plain", (error.message ?: "error").toByteArray())
        }
    }

    private fun extendJson(extend: Map<String, String>): String =
        JsonObject(extend.mapValues { JsonPrimitive(it.value) }).toString()

    private fun payload(value: Any?): ProxyPayload {
        if (value is String && value.trim().startsWith("[")) {
            val array = runCatching { json.parseToJsonElement(value).jsonArray }.getOrNull()
            if (array != null) return fromJson(array)
        }
        val array = value as? Array<*> ?: return ProxyPayload(502, "text/plain", "empty".toByteArray())
        val code = (array.getOrNull(0) as? Number)?.toInt() ?: 200
        val mime = array.getOrNull(1)?.toString() ?: "application/octet-stream"
        val body = array.getOrNull(2)
        val bytes = when (body) {
            is ByteArray -> body
            is String -> body.toByteArray()
            is java.io.InputStream -> body.use { stream ->
                val buffer = ByteArray(8_000_000)
                var size = 0
                while (size < buffer.size) {
                    val read = stream.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                buffer.copyOf(size)
            }
            else -> body?.toString()?.toByteArray() ?: ByteArray(0)
        }
        return ProxyPayload(code, mime, bytes)
    }

    private fun fromJson(array: JsonArray): ProxyPayload {
        val code = (array.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 200
        val mime = (array.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: "text/plain"
        val body = (array.getOrNull(2) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ProxyPayload(code, mime, body.toByteArray())
    }

    private fun script(def: VodSiteDef): JsEngine = scripts.getOrPut(def.key) {
        JsEngine(http.http, JsHost(
            http = { call -> http.http.spiderReply(call) },
            store = store,
            proxyUrl = { proxy.base() + "?do=js&site=" + java.net.URLEncoder.encode(def.key, "UTF-8") },
            enabled = { enabled },
            logger = { message -> Log.d("JianXia", message) },
        ))
    }

    private fun engine(def: VodSiteDef): Engine {
        if (!enabled) throw IllegalStateException("爬虫已关闭")
        sites[def.key] = def
        return if (def.spiderMode == SpiderMode.JS) Engine.JS else Engine.JAR
    }

    private fun <T> call(def: VodSiteDef, block: () -> T): T {
        if (!enabled) throw IllegalStateException("爬虫已关闭")
        val cold = def.spiderMode != SpiderMode.JS && (jars.preparing() || !jars.hot(def))
        val wait = if (cold) 180_000L else timeoutMs().coerceIn(8_000L, 20_000L)
        val future = pool.submit(Callable {
            if (!enabled) throw IllegalStateException("爬虫已关闭")
            try {
                block()
            } catch (error: Exception) {
                throw error
            } catch (error: Throwable) {
                throw IllegalStateException(error.javaClass.simpleName + ": " + (error.message ?: "爬虫执行失败"), error)
            }
        })
        try {
            return future.get(wait, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            if (cold || jars.preparing() || !jars.hot(def)) {
                throw IllegalStateException("爬虫 JAR 还在加载")
            }
            future.cancel(false)
            throw IllegalStateException("爬虫超时")
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause
            if (cause is Exception) throw cause
            throw IllegalStateException(cause?.javaClass?.simpleName + ": " + (cause?.message ?: "爬虫执行失败"), cause)
        }
    }

    private enum class Engine { JAR, JS }
}

private fun Json.encodeToString(values: Map<String, String>): String {
    val body = values.entries.joinToString(",") { (key, value) ->
        "\"${key.replace("\"", "")}\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
    return "{$body}"
}
