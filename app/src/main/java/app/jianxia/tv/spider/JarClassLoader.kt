package app.jianxia.tv.spider

import app.jianxia.core.spider.ArmElf
import dalvik.system.DexClassLoader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * JAR 里若自带 com.github.catvod.spider.Init / Proxy，必须用 JAR 里的那份。
 * 系统的 DexClassLoader 会先找宿主，饭太硬的原生库因此永远不会被它自己的 Init 拉起来。
 * 部分电视上 DexClassLoader 也读不到 JAR 里的 assets，这里直接从压缩包取出。
 */
internal class JarClassLoader(
    private val dexPath: String,
    optimizedDirectory: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("com.github.catvod.spider.")) {
            findLoadedClass(name)?.let { return it }
            try {
                val found = findClass(name)
                if (resolve) resolveClass(found)
                return found
            } catch (_: ClassNotFoundException) {
                // JAR 没有这一个类时，再用宿主提供的空实现。
            }
        }
        return super.loadClass(name, resolve)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        zipBytes(name)?.let { return ByteArrayInputStream(ArmElf.preferHardFloat(it)) }
        return super.getResourceAsStream(name)
    }

    private fun zipBytes(name: String): ByteArray? {
        val entryName = name.trim().removePrefix("/")
        if (entryName.isEmpty()) return null
        return try {
            ZipFile(dexPath).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
