package app.jianxia.tv

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashStore {
    private const val FILE = "last-crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
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
            if (previous != null && previous.javaClass.name.startsWith("com.android")) {
                // 系统默认处理会直接结束进程。先记下堆栈，再交给它收尾。
            }
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    fun read(app: Application): String = read(app.filesDir)

    fun read(filesDir: File): String {
        val file = File(filesDir, FILE)
        if (!file.isFile) return ""
        return runCatching { file.readText().take(12_000) }.getOrDefault("")
    }

    fun write(app: Application, thread: Thread, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val text = buildString {
            append("线程 ")
            append(thread.name)
            append("\n")
            append(writer.toString())
        }.take(12_000)
        runCatching { File(app.filesDir, FILE).writeText(text) }
    }
}
