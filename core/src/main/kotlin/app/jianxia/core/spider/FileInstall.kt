package app.jianxia.core.spider

import java.io.File

/**
 * 把字节写到应用私有目录。
 * 已经存在的文件先删除；删不掉就换一个新名字。
 * 内容只写进临时文件，再改名，避免再次打开一个只读文件。
 * Android 14 起动态加载的 dex/jar 必须只读，.so 保持可写。
 */
object FileInstall {
    fun place(
        directory: File,
        name: String,
        bytes: ByteArray,
        executable: Boolean,
        readOnly: Boolean,
    ): File {
        if (!directory.exists()) directory.mkdirs()
        directory.setReadable(true, false)
        directory.setWritable(true, true)
        directory.setExecutable(true, false)
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "blob.bin" }
        val preferred = File(directory, safeName)
        val target = if (!preferred.exists() || preferred.delete()) {
            preferred
        } else {
            File(directory, "${System.nanoTime()}-$safeName")
        }
        val tmp = File.createTempFile(".part-", ".tmp", directory)
        try {
            tmp.writeBytes(bytes)
            if (target.exists() && !target.delete()) {
                val alt = File(directory, "${System.nanoTime()}-$safeName")
                if (!tmp.renameTo(alt)) alt.writeBytes(bytes)
                finish(alt, executable, readOnly)
                return alt
            }
            if (!tmp.renameTo(target)) target.writeBytes(bytes)
            finish(target, executable, readOnly)
            return target
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun finish(file: File, executable: Boolean, readOnly: Boolean) {
        file.setReadable(true, false)
        if (executable) file.setExecutable(true, false)
        if (readOnly) file.setWritable(false, false) else file.setWritable(true, true)
    }
}
