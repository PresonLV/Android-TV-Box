package app.jianxia.tv

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import app.jianxia.core.crash.BootPolicy
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashStore {
    private const val FILE = "last-crash.txt"
    private const val SUMMARY = "crash-summary.txt"
    private const val ACK = "crash-ack.txt"
    private const val MARKER = "boot.marker"
    private const val COUNT = "boot.count"
    private const val PHASE = "native-phase.txt"

    @Volatile
    var safeMode: Boolean = false
        private set

    private var appRef: Application? = null

    fun install(app: Application) {
        appRef = app
        val dir = app.filesDir
        val previous = readRaw(dir, MARKER)
        val count = readRaw(dir, COUNT).toIntOrNull() ?: 0
        val next = BootPolicy.nextCount(previous, count)
        safeMode = BootPolicy.safeMode(next)
        writeRaw(dir, COUNT, next.toString())
        writeRaw(dir, MARKER, BootPolicy.MARKER_BOOTING)
        if (previous == BootPolicy.MARKER_BOOTING && read(dir).isBlank()) {
            remember(
                dir,
                "启动未完成",
                "上次启动没有走到界面稳定。没有 Java 堆栈，可能是原生崩溃、内存不足，或进程被系统杀掉。\n当时步骤：${readRaw(dir, PHASE).ifBlank { "未知" }}",
            )
        }
        if (readRaw(dir, SUMMARY).isBlank()) {
            val existing = read(dir)
            if (existing.isNotBlank()) {
                remember(dir, "上次闪退", existing)
            }
        }
        captureExits(app)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(app, thread, error)
            val intent = Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
            val pending = PendingIntent.getActivity(
                app,
                1,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = app.getSystemService(AlarmManager::class.java)
            alarm?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 500, pending)
            if (previousHandler != null && previousHandler.javaClass.name.startsWith("com.android")) {
                // 系统默认处理会结束进程。堆栈已经落盘。
            }
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    fun markReady() {
        val dir = appRef?.filesDir ?: return
        writeRaw(dir, MARKER, BootPolicy.MARKER_READY)
        writeRaw(dir, COUNT, "0")
    }

    fun phase(name: String) {
        val dir = appRef?.filesDir ?: return
        writeRaw(dir, PHASE, name.take(200))
    }

    fun leaveSafeMode() {
        safeMode = false
        val dir = appRef?.filesDir ?: return
        writeRaw(dir, COUNT, "0")
        writeRaw(dir, MARKER, BootPolicy.MARKER_READY)
    }

    fun read(app: Application): String = read(app.filesDir)

    fun read(filesDir: File): String = readRaw(filesDir, FILE).take(12_000)

    fun notice(filesDir: File): String {
        val text = readRaw(filesDir, SUMMARY)
        if (text.isBlank()) return ""
        val ack = readRaw(filesDir, ACK)
        if (ack == text.hashCode().toString()) return ""
        return text
    }

    fun acknowledge(filesDir: File) {
        val text = readRaw(filesDir, SUMMARY)
        if (text.isBlank()) return
        writeRaw(filesDir, ACK, text.hashCode().toString())
    }

    fun write(app: Application, thread: Thread, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val text = buildString {
            append("线程 ")
            append(thread.name)
            append("\n步骤 ")
            append(readRaw(app.filesDir, PHASE).ifBlank { "未知" })
            append("\n")
            append(writer.toString())
        }.take(12_000)
        remember(app.filesDir, error.javaClass.simpleName + ": " + (error.message ?: "闪退"), text)
    }

    private fun captureExits(app: Application) {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = app.getSystemService(android.app.ActivityManager::class.java) ?: return
        val seenFile = File(app.filesDir, "exit-seen.txt")
        val seen = readRaw(app.filesDir, "exit-seen.txt").toLongOrNull() ?: 0L
        val method = runCatching {
            manager.javaClass.getMethod(
                "getHistoricalProcessExitReasons",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: return
        val exits = runCatching { method.invoke(manager, app.packageName, 0, 8) as? List<*> }.getOrNull().orEmpty()
        var newest = seen
        val phase = readRaw(app.filesDir, PHASE).ifBlank { "未知" }
        val current = read(app.filesDir)
        exits.forEach { info ->
            if (info == null) return@forEach
            val timestamp = runCatching {
                info.javaClass.getMethod("getTimestamp").invoke(info) as Long
            }.getOrDefault(0L)
            if (timestamp > newest) newest = timestamp
            if (timestamp <= seen) return@forEach
            val reason = runCatching {
                info.javaClass.getMethod("getReason").invoke(info) as Int
            }.getOrDefault(-1)
            val label = when (reason) {
                2 -> "原生信号退出"
                3 -> "内存不足"
                4 -> "Java 崩溃"
                5 -> "原生崩溃"
                6 -> "界面无响应"
                else -> return@forEach
            }
            val trace = runCatching {
                val stream = info.javaClass.getMethod("getTraceInputStream").invoke(info) as? java.io.InputStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val description = runCatching {
                info.javaClass.getMethod("getDescription").invoke(info) as? String
            }.getOrNull().orEmpty()
            val text = buildString {
                append("系统退出记录\n")
                append("原因 ").append(label).append('\n')
                append("说明 ").append(description).append('\n')
                append("当时步骤 ").append(phase).append('\n')
                if (trace.isNotBlank()) append(trace.take(8_000))
            }
            if (current.isBlank() || label.contains("原生") || !current.contains("线程 ")) {
                remember(app.filesDir, label, text)
            }
        }
        if (newest > seen) runCatching { seenFile.writeText(newest.toString()) }
    }

    private fun remember(dir: File, headline: String, detail: String) {
        writeRaw(dir, FILE, detail.take(12_000))
        val summary = buildString {
            append("上次使用时应用闪退了。\n")
            append(headline.take(180)).append('\n')
            if (safeMode) append("已进入安全模式：这次不读首页缓存，也不自动加载爬虫。\n")
            append("完整日志可在手机扫码页点「查看/复制崩溃日志」。")
        }
        writeRaw(dir, SUMMARY, summary)
        writeRaw(dir, ACK, "")
    }

    private fun readRaw(dir: File, name: String): String {
        val file = File(dir, name)
        if (!file.isFile) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    private fun writeRaw(dir: File, name: String, text: String) {
        runCatching { File(dir, name).writeText(text) }
    }
}
