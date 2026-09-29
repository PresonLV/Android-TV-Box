package app.jianxia.core.cache

import java.io.File
import java.security.MessageDigest

/** 先读内存，没有再读磁盘。首页返回时用它避免重新请求。 */
class TextCache(private val dir: File, private val memoryLimit: Int = 48) {
    private val memory = object : LinkedHashMap<String, String>(memoryLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > memoryLimit
    }

    fun read(key: String): String? {
        synchronized(memory) { memory[key] }?.let { return it }
        val file = file(key)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        synchronized(memory) { memory[key] = text }
        return text
    }

    fun write(key: String, text: String) {
        synchronized(memory) { memory[key] = text }
        runCatching {
            dir.mkdirs()
            val target = file(key)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(32) + ".json"
        return File(dir, name)
    }
}
