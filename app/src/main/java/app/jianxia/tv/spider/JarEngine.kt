package app.jianxia.tv.spider

import android.content.Context
import android.os.Build
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.spider.SpiderJson
import app.jianxia.core.spider.SpiderPlay
import app.jianxia.core.spider.jarClassNames
import com.github.catvod.spider.Init
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.zip.ZipFile

internal class JarEngine(
    private val context: Context,
    private val cache: JarCache,
) {
    private val loaders = java.util.concurrent.ConcurrentHashMap<String, DexClassLoader>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun clear() {
        sessions.clear()
        loaders.clear()
    }

    fun home(def: VodSiteDef): VodPage {
        val spider = session(def)
        val home = invoke(spider, "homeContent", true)?.toString().orEmpty()
        val extra = runCatching { invokeOptional(spider, "homeVideoContent")?.toString().orEmpty() }.getOrDefault("")
        return SpiderJson.merge(SpiderJson.page(home, def), SpiderJson.page(extra, def))
    }

    fun category(def: VodSiteDef, tid: String, page: Int, extend: Map<String, String>): VodPage {
        val spider = session(def)
        val raw = invoke(spider, "categoryContent", tid, page.toString(), false, HashMap(extend))?.toString()
        return SpiderJson.page(raw, def)
    }

    fun detail(def: VodSiteDef, id: String): VodItem? {
        val spider = session(def)
        val raw = invoke(spider, "detailContent", arrayListOf(id))?.toString()
        return SpiderJson.detail(raw, def, id)
    }

    fun search(def: VodSiteDef, keyword: String): VodPage {
        val spider = session(def)
        val raw = invokeOptional(spider, "searchContent", keyword, false, "1")
            ?: invoke(spider, "searchContent", keyword, false)
        return SpiderJson.page(raw?.toString(), def)
    }

    fun play(def: VodSiteDef, flag: String, id: String): SpiderPlay {
        val spider = session(def)
        val raw = invoke(spider, "playerContent", flag, id, arrayListOf<String>())?.toString()
        return SpiderJson.play(raw)
    }

    fun proxy(def: VodSiteDef, params: Map<String, String>): Any? {
        val spider = session(def)
        return invokeOptional(spider, "proxyLocal", params)
            ?: invokeOptional(spider, "localProxy", params)
            ?: invokeOptional(spider, "proxy", params)
    }

    private fun session(def: VodSiteDef): Any = sessions.getOrPut(def.key) {
        Init.init(context)
        val jar = cache.file(def.spiderJar, def.userAgent)
        val loader = loaders.getOrPut(jar.absolutePath) { classLoader(jar) }
        val type = jarClassNames(def.api).firstNotNullOfOrNull { name ->
            runCatching { loader.loadClass(name) }.getOrNull()
        } ?: throw IllegalStateException("找不到爬虫类 ${def.api}")
        val spider = type.getDeclaredConstructor().newInstance()
        invokeOptional(spider, "init", context, def.spiderExt) ?: invokeOptional(spider, "init", context)
        spider
    }

    private fun classLoader(jar: File): DexClassLoader {
        val libs = File(jar.parentFile, jar.nameWithoutExtension + "-lib")
        libs.mkdirs()
        extractLibs(jar, libs)
        val opt = File(context.codeCacheDir, "spider-opt").apply { mkdirs() }
        return DexClassLoader(jar.absolutePath, opt.absolutePath, libs.absolutePath, context.classLoader)
    }

    private fun extractLibs(jar: File, dest: File) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase()
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }.forEach { entry ->
                if (!matchesAbi(entry.name, abi)) return@forEach
                val base = entry.name.substringAfterLast("/")
                zip.getInputStream(entry).use { input ->
                    File(dest, base).outputStream().use { input.copyTo(it) }
                }
                if (!base.startsWith("lib")) {
                    zip.getInputStream(entry).use { input ->
                        File(dest, "lib$base").outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun matchesAbi(path: String, abi: String): Boolean {
        val name = path.lowercase()
        val arm64 = abi.contains("arm64")
        val v7 = abi.contains("armeabi")
        return when {
            name.contains("arm64") || name.contains("aarch64") || name.contains("_v8") || name.contains("v8a") -> arm64
            name.contains("armeabi") || name.contains("_v7") || name.contains("v7a") -> v7
            name.contains("x86_64") -> abi.contains("x86_64")
            name.contains("x86") -> abi.contains("x86") && !abi.contains("64")
            else -> true
        }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? = synchronized(target) {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == args.size
        } ?: throw NoSuchMethodException(name)
        method.isAccessible = true
        try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private fun invokeOptional(target: Any, name: String, vararg args: Any?): Any? = try {
        invoke(target, name, *args)
    } catch (_: NoSuchMethodException) {
        null
    }
}
