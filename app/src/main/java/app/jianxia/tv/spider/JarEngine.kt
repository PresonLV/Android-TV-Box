package app.jianxia.tv.spider

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import app.jianxia.core.model.VodItem
import app.jianxia.core.spider.ArmElf
import app.jianxia.core.spider.FileInstall
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.spider.PlayText
import app.jianxia.core.spider.SpiderFault
import app.jianxia.core.spider.SpiderJson
import app.jianxia.core.spider.SpiderPlay
import app.jianxia.core.spider.jarClassNames
import app.jianxia.tv.CrashStore
import app.jianxia.tv.data.net.Ua
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import com.github.catvod.spider.Proxy
import java.util.zip.ZipFile

internal class JarEngine(
    private val context: Context,
    private val cache: JarCache,
) {
    private val loaders = java.util.concurrent.ConcurrentHashMap<String, JarClassLoader>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val preparing = AtomicInteger(0)

    fun preparing(): Boolean = preparing.get() > 0

    fun hot(def: VodSiteDef): Boolean = sessions.containsKey(def.key)

    fun cold(def: VodSiteDef): Boolean = !cache.ready(def.spiderJar)

    fun clear() {
        sessions.clear()
        loaders.clear()
    }

    fun dropSessions() {
        sessions.clear()
    }

    fun bindProxy(base: String) {
        val value = if (base.endsWith("?")) base else "$base?"
        Proxy.setUrl(value)
        loaders.values.forEach { loader ->
            val type = runCatching { loader.loadClass("com.github.catvod.spider.Proxy") }.getOrNull() ?: return@forEach
            val set = type.methods.firstOrNull {
                it.name == "setUrl" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
            }
            runCatching {
                set?.isAccessible = true
                set?.invoke(null, value)
            }
            type.declaredFields.firstOrNull { it.name == "url" || it.name == "proxyUrl" }?.let { field ->
                if (!Modifier.isStatic(field.modifiers)) return@let
                field.isAccessible = true
                if (field.type == String::class.java) runCatching { field.set(null, value) }
            }
        }
    }

    fun home(def: VodSiteDef): VodPage {
        val spider = session(def)
        val home = invoke(spider, "homeContent", true)?.toString().orEmpty()
        val extra = runCatching { invokeOptional(spider, "homeVideoContent")?.toString().orEmpty() }.getOrDefault("")
        val page = SpiderJson.merge(SpiderJson.page(home, def), SpiderJson.page(extra, def))
        Log.i("JianXia", "home ${def.key} classes=${page.classes.size} filters=${page.filters.keys} items=${page.items.size}")
        return page
    }

    fun category(def: VodSiteDef, tid: String, page: Int, extend: Map<String, String>): VodPage {
        val spider = session(def)
        val raw = invoke(spider, "categoryContent", tid, page.toString(), false, HashMap(extend))?.toString()
        return SpiderJson.page(raw, def)
    }

    fun detail(def: VodSiteDef, id: String): VodItem? {
        val spider = session(def)
        var last: Throwable? = null
        val raw = listOf<Any>(arrayListOf(id), id).firstNotNullOfOrNull { arg ->
            try {
                val text = invokeOptional(spider, "detailContent", arg)?.toString().orEmpty()
                text.takeIf { it.isNotBlank() && it != "null" && it != "{}" }
            } catch (error: Throwable) {
                last = error
                Log.w("JianXia", "detail ${def.key} ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }
        if (raw == null) {
            val cause = last
            if (cause != null) throw IllegalStateException(SpiderFault.explain(cause), cause)
            return null
        }
        Log.i("JianXia", "detail ${def.key} bytes=${raw.length} head=${raw.take(180).replace('\n', ' ')}")
        return SpiderJson.detail(raw, def, id)
    }

    fun search(def: VodSiteDef, keyword: String): VodPage {
        val spider = session(def)
        val raw = invokeOptional(spider, "searchContent", keyword, false, "1")
            ?: invoke(spider, "searchContent", keyword, false)
        return SpiderJson.page(raw?.toString(), def)
    }

    fun play(def: VodSiteDef, flag: String, id: String): SpiderPlay {
        CrashStore.phase("播放解析 ${def.key}")
        return try {
            val spider = session(def)
            val raw = invoke(spider, "playerContent", flag, id, arrayListOf<String>())?.toString()
            SpiderJson.play(raw)
        } catch (error: Throwable) {
            throw IllegalStateException(SpiderFault.explain(error), error)
        }
    }

    fun proxy(def: VodSiteDef, params: Map<String, String>): Any? {
        val spider = session(def)
        return invokeOptional(spider, "proxyLocal", params)
            ?: invokeOptional(spider, "localProxy", params)
            ?: invokeOptional(spider, "proxy", params)
            ?: guardProxy(params)
    }

    fun guardProxy(params: Map<String, String>): Any? {
        for (loader in loaders.values) {
            val type = runCatching { loader.loadClass("com.github.catvod.spider.Proxy") }.getOrNull() ?: continue
            val method = type.methods.firstOrNull { it.name == "proxy" && Modifier.isStatic(it.modifiers) } ?: continue
            val value = runCatching { method.invoke(null, params) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    fun warmup(raw: String, userAgent: String) {
        CrashStore.phase("加载爬虫")
        preparing.incrementAndGet()
        try {
            val jar = cache.file(raw, userAgent)
            loaders.getOrPut(jar.absolutePath) {
                val created = classLoader(jar)
                boot(created)
                created
            }
        } finally {
            preparing.decrementAndGet()
        }
    }

    private fun session(def: VodSiteDef): Any = sessions.getOrPut(def.key) {
        preparing.incrementAndGet()
        try {
            val jar = cache.file(def.spiderJar, def.userAgent)
            val loader = loaders.getOrPut(jar.absolutePath) {
                val created = classLoader(jar)
                boot(created)
                created
            }
            val type = jarClassNames(def.api).firstNotNullOfOrNull { name ->
                runCatching { loader.loadClass(name) }.getOrNull()
            } ?: throw IllegalStateException("找不到爬虫类 ${def.api}")
            val spider = type.getDeclaredConstructor().newInstance()
            // 扩展原样交给爬虫。站点根地址、JSON 和加密串都不能先下载成正文。
            val ext = PlayText.mergeCookies(def.spiderExt.trim(), DriveCookies.quark, DriveCookies.uc, DriveCookies.ali)
            val app = context.applicationContext
            invokeOptional(spider, "init", app, ext) ?: invokeOptional(spider, "init", app)
            spider
        } finally {
            preparing.decrementAndGet()
        }
    }

    private fun boot(loader: JarClassLoader) {
        ProcessAbi.alignBuildFields()
        // 饭太硬用只读方式打开 databases/tv。文件不存在时会直接失败。
        runCatching {
            val file = context.getDatabasePath("tv")
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).close()
            }
        }
        val init = runCatching { loader.loadClass("com.github.catvod.spider.Init") }.getOrNull() ?: return
        val method = init.methods.firstOrNull {
            it.name == "init" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
        } ?: return
        val app = context.applicationContext
        val arg = if (method.parameterTypes[0].isAssignableFrom(app.javaClass)) app else context
        method.isAccessible = true
        try {
            method.invoke(null, arg)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            if (cause is UnsatisfiedLinkError || cause.cause is UnsatisfiedLinkError) {
                throw IllegalStateException("爬虫原生库和当前 CPU 不匹配", cause)
            }
            throw cause
        }
        // 饭太硬外层 Init 只保存自己的 Application。真正的爬虫在解密后的 dex 里，
        // 静态初始化会调用 InitOrigin.context().getSharedPreferences。必须在创建爬虫之前写入。
        primeInner(init, arg)
    }

    private fun primeInner(outerInit: Class<*>, arg: Context) {
        val inner = runCatching {
            val loaderMethod = outerInit.methods.firstOrNull {
                it.name == "loader" && Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty()
            } ?: return
            loaderMethod.isAccessible = true
            loaderMethod.invoke(null) as? ClassLoader
        }.getOrNull() ?: return
        val origin = runCatching { inner.loadClass("com.github.catvod.spider.InitOrigin") }.getOrNull() ?: return
        val method = origin.methods.firstOrNull {
            it.name == "init" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
        } ?: return
        val app = context.applicationContext
        val value = if (method.parameterTypes[0].isAssignableFrom(app.javaClass)) app else arg
        method.isAccessible = true
        try {
            method.invoke(null, value)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            Log.w("JianXia", "InitOrigin.init: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun classLoader(jar: File): JarClassLoader {
        val root = context.getDir("spider_libs", Context.MODE_PRIVATE)
        val libs = File(root, jar.nameWithoutExtension)
        libs.mkdirs()
        libs.setReadable(true, false)
        libs.setWritable(true, true)
        libs.setExecutable(true, false)
        extractLibs(jar, libs)
        val opt = File(context.codeCacheDir, "spider-opt").apply { mkdirs() }
        return JarClassLoader(jar.absolutePath, opt.absolutePath, libs.absolutePath, context.classLoader)
    }

    private fun extractLibs(jar: File, dest: File) {
        val abi = ProcessAbi.name().lowercase()
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }.forEach { entry ->
                if (!matchesAbi(entry.name, abi)) return@forEach
                val bytes = zip.getInputStream(entry).use { ArmElf.preferHardFloat(it.readBytes()) }
                writeLib(dest, entry.name.substringAfterLast("/"), bytes)
            }
        }
    }

    private fun writeLib(dest: File, name: String, bytes: ByteArray) {
        val targets = if (name.startsWith("lib")) listOf(name) else listOf(name, "lib$name")
        targets.forEach { base ->
            FileInstall.place(dest, base, bytes, executable = true, readOnly = false)
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
        val method = target.javaClass.methods
            .filter { it.name == name && it.parameterTypes.size == args.size }
            .sortedByDescending { candidate ->
                candidate.parameterTypes.indices.count { index -> compatible(candidate.parameterTypes[index], args[index]) }
            }
            .firstOrNull { candidate ->
                candidate.parameterTypes.indices.all { index -> compatible(candidate.parameterTypes[index], args[index]) }
            }
            ?: throw NoSuchMethodException(name)
        method.isAccessible = true
        try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    private fun compatible(type: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !type.isPrimitive
        if (type.isPrimitive) {
            return when (type) {
                java.lang.Boolean.TYPE -> arg is Boolean
                Integer.TYPE -> arg is Int
                java.lang.Long.TYPE -> arg is Long || arg is Int
                else -> true
            }
        }
        if (type.isInstance(arg)) return true
        if (arg is java.util.Collection<*> && java.util.Collection::class.java.isAssignableFrom(type)) return true
        return false
    }

    private fun invokeOptional(target: Any, name: String, vararg args: Any?): Any? = try {
        invoke(target, name, *args)
    } catch (_: NoSuchMethodException) {
        null
    }
}

/**
 * 饭太硬按 Build.CPU_ABI 是否包含 64 选择 v7 或 v8。
 * 32 位进程有时仍读到 arm64-v8a，这里改成当前进程的 ABI。
 */
internal object ProcessAbi {
    fun is64Bit(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Process.is64Bit()
    } else {
        Build.CPU_ABI.orEmpty().contains("64")
    }

    fun name(): String {
        val listed = if (is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return listed.firstOrNull().orEmpty().ifBlank { if (is64Bit()) "arm64-v8a" else "armeabi-v7a" }
    }

    fun alignBuildFields() {
        val abi = name()
        if (!Build.CPU_ABI.equals(abi, true)) setStatic("CPU_ABI", abi)
        val second = (if (is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).getOrNull(1).orEmpty()
        if (Build.CPU_ABI2 != second) setStatic("CPU_ABI2", second)
    }

    private fun setStatic(fieldName: String, value: String) {
        runCatching {
            val field = Build::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            val flags = runCatching { java.lang.reflect.Field::class.java.getDeclaredField("accessFlags") }
                .getOrElse { java.lang.reflect.Field::class.java.getDeclaredField("modifiers") }
            flags.isAccessible = true
            flags.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
            field.set(null, value)
        }
    }
}
