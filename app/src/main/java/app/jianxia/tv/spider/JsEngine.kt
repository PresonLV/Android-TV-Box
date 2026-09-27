package app.jianxia.tv.spider

import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.spider.JsHost
import app.jianxia.core.spider.JsModules
import app.jianxia.core.spider.SpiderJson
import app.jianxia.core.spider.SpiderPlay
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.QuickJS
import okhttp3.OkHttpClient
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class JsEngine(
    private val http: OkHttpClient,
    private val host: JsHost,
) {
    private val texts = ConcurrentHashMap<String, String>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spider-js").apply { isDaemon = true }
    }
    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private var readyKey: String? = null

    fun close() {
        worker.submit {
            runCatching { context?.close() }
            runCatching { runtime?.close() }
            context = null
            runtime = null
            readyKey = null
        }
        worker.shutdown()
    }

    fun home(def: VodSiteDef, timeoutMs: Long): VodPage = use(def, timeoutMs) { ctx ->
        val home = SpiderJson.page(call(ctx, "__jx_home", true), def)
        val extra = runCatching { SpiderJson.page(call(ctx, "__jx_home_vod"), def) }.getOrDefault(app.jianxia.core.model.VodPage.empty())
        SpiderJson.merge(home, extra)
    }

    fun category(def: VodSiteDef, tid: String, page: Int, extend: String, timeoutMs: Long): VodPage =
        use(def, timeoutMs) { ctx -> SpiderJson.page(call(ctx, "__jx_category", tid, page, extend), def) }

    fun detail(def: VodSiteDef, id: String, timeoutMs: Long): VodItem? =
        use(def, timeoutMs) { ctx -> SpiderJson.detail(call(ctx, "__jx_detail", id), def, id) }

    fun search(def: VodSiteDef, keyword: String, timeoutMs: Long): VodPage =
        use(def, timeoutMs) { ctx -> SpiderJson.page(call(ctx, "__jx_search", keyword, 1), def) }

    fun play(def: VodSiteDef, flag: String, id: String, timeoutMs: Long): SpiderPlay =
        use(def, timeoutMs) { ctx -> SpiderJson.play(call(ctx, "__jx_play", flag, id)) }

    fun proxy(def: VodSiteDef, paramsJson: String, timeoutMs: Long): String =
        use(def, timeoutMs) { ctx -> call(ctx, "__jx_proxy", paramsJson) }

    private fun <T> use(def: VodSiteDef, timeoutMs: Long, block: (JSContext) -> T): T {
        val future = worker.submit(Callable {
            val ctx = ensure(def)
            block(ctx)
        })
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw IllegalStateException("爬虫超时")
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun ensure(def: VodSiteDef): JSContext {
        val key = def.api + "\u0000" + def.spiderExt
        val existing = context
        if (existing != null && readyKey == key) return existing
        runCatching { context?.close() }
        runCatching { runtime?.close() }
        val runtime = QuickJS.createRuntime()
        val ctx = runtime.createContext()
        this.runtime = runtime
        context = ctx
        val bridge = JSObject(ctx)
        bind(ctx, bridge, "req") { args -> host.req(args.text(0), args.text(1)) }
        bind(ctx, bridge, "pdfh") { args -> host.pdfh(args.text(0), args.text(1)) }
        bind(ctx, bridge, "pdfa") { args -> host.pdfa(args.text(0), args.text(1)) }
        bind(ctx, bridge, "pd") { args -> host.pd(args.text(0), args.text(1), args.text(2)) }
        bind(ctx, bridge, "joinUrl") { args -> host.joinUrl(args.text(0), args.text(1)) }
        bind(ctx, bridge, "localGet") { args -> host.localGet(args.text(0), args.text(1)) }
        bind(ctx, bridge, "localSet") { args -> host.localSet(args.text(0), args.text(1), args.text(2)); null }
        bind(ctx, bridge, "localDelete") { args -> host.localDelete(args.text(0), args.text(1)); null }
        bind(ctx, bridge, "proxyUrl") { _ -> host.proxyUrl() }
        bind(ctx, bridge, "log") { args -> host.log(args.text(0)); null }
        bind(ctx, bridge, "jsonPath") { args -> host.jsonPath(args.text(0), args.text(1)) }
        bind(ctx, bridge, "jinja") { args -> host.jinja(args.text(0), args.text(1)) }
        bind(ctx, bridge, "md5") { args -> host.md5(args.text(0)) }
        ctx.set("host", bridge)
        ctx.executeVoidScript(JsModules.PRELUDE, "prelude.js")
        val source = text(def.api)
        val script = JsModules.assemble(def.api, source) { url -> text(url) }
        ctx.executeVoidScript(script, "spider.js")
        val args = JSArray(ctx).push(def.spiderExt)
        runCatching { ctx.executeVoidFunction("__jx_init", args) }
        readyKey = key
        return ctx
    }

    private fun text(url: String): String = texts.getOrPut(url) {
        val loaded = http.bytes(url, listOf("Mozilla/5.0", app.jianxia.tv.data.net.Ua.CONFIG), 2_000_000, 20_000)
        if (loaded.code !in 200..299 || loaded.bytes.isEmpty()) throw IllegalStateException("脚本下载失败（${loaded.code}）")
        String(loaded.bytes, Charsets.UTF_8)
    }

    private fun call(ctx: JSContext, name: String, vararg values: Any?): String {
        val args = JSArray(ctx)
        values.forEach { value ->
            when (value) {
                is Boolean -> args.push(value)
                is Int -> args.push(value)
                is Double -> args.push(value)
                else -> args.push(value?.toString().orEmpty())
            }
        }
        return runCatching { ctx.executeStringFunction(name, args) }.getOrElse {
            ctx.executeFunction(name, args)?.toString().orEmpty()
        }
    }

    private fun bind(ctx: JSContext, target: JSObject, name: String, call: (JSArray) -> Any?) {
        target.registerJavaMethod(JavaCallback { _, args -> call(args) }, name)
    }

    private fun JSArray.text(index: Int): String = if (index < 0 || index >= length()) "" else get(index)?.toString().orEmpty()
}
