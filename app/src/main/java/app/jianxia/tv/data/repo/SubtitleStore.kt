package app.jianxia.tv.data.repo

import java.io.File

class SubtitleStore(filesDir: File) {
    private val dir = File(filesDir, "subtitles").apply { mkdirs() }

    fun save(name: String, bytes: ByteArray): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "subtitle.srt" }
            .replace(Regex("""[^\w.\-\u4e00-\u9fff]"""), "_")
            .take(80)
        val target = File(dir, "${System.currentTimeMillis()}-$safe")
        target.writeBytes(bytes.copyOf(bytes.size.coerceAtMost(MAX_BYTES)))
        val extra = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty().drop(20)
        extra.forEach { runCatching { it.delete() } }
        return target
    }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    companion object {
        const val MAX_BYTES = 2_000_000
    }
}
