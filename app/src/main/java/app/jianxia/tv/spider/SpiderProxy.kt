package app.jianxia.tv.spider

import app.jianxia.core.spider.PlayText
import com.github.catvod.spider.Proxy
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ProxyPayload(
    val code: Int,
    val mime: String,
    val bytes: ByteArray = ByteArray(0),
    val stream: InputStream? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

class SpiderProxy(
    private val handler: (site: String, params: Map<String, String>) -> ProxyPayload,
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "spider-proxy").apply { isDaemon = true }
    }
    private var socket: ServerSocket? = null
    private var port = 0

    fun base(): String {
        ensure()
        return "http://127.0.0.1:$port/proxy"
    }

    fun start() {
        ensure()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        port = 0
    }

    fun expose(siteKey: String, url: String): String {
        if (!ensure()) return url
        return PlayText.rewriteProxy(siteKey, url, base())
    }

    private fun ensure(): Boolean {
        if (running.get() && socket?.isClosed == false && port > 0) return true
        return try {
            val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            socket = server
            port = server.localPort
            running.set(true)
            Proxy.setUrl("http://127.0.0.1:$port/proxy?")
            Thread({
                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    pool.execute {
                        try {
                            serve(client)
                        } catch (_: Throwable) {
                            // 单次代理失败只结束这次连接。
                        } finally {
                            runCatching { client.close() }
                        }
                    }
                }
            }, "spider-proxy-accept").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 20_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine().orEmpty()
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(":").trim().lowercase()
            val value = line.substringAfter(":", "").trim()
            if (name.isNotEmpty()) headers[name] = value
        }
        client.soTimeout = 0
        val rawQuery = request.split(" ").getOrNull(1).orEmpty().substringAfter("?", "")
        val params = linkedMapOf<String, String>()
        rawQuery.split("&").forEach { part ->
            if (part.isBlank()) return@forEach
            val key = URLDecoder.decode(part.substringBefore("="), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter("=", ""), "UTF-8")
            params[key] = value
        }
        headers["range"]?.let { params.putIfAbsent("Range", it) }
        val site = params.remove("site").orEmpty()
        val payload = if (site.isBlank()) {
            ProxyPayload(404, "text/plain", "missing site".toByteArray())
        } else {
            handler(site, params)
        }
        write(client, payload, headers["range"])
    }

    private fun write(client: Socket, payload: ProxyPayload, range: String?) {
        val output = client.getOutputStream()
        val stream = payload.stream
        if (stream != null) {
            stream.use { input ->
                val status = if (payload.code <= 0) 200 else payload.code
                output.write(head(status, payload.mime, null, null, payload.extraHeaders).toByteArray())
                val start = rangeStart(range)
                if (start > 0) runCatching { input.skip(start) }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            return
        }
        val sliced = PlayText.slice(payload.bytes, range)
        val status = if (payload.bytes.isEmpty()) payload.code else sliced.code
        val rangeLine = if (sliced.code == 206) {
            "Content-Range: bytes ${sliced.start}-${sliced.end}/${sliced.total}\r\n"
        } else {
            null
        }
        output.write(head(status, payload.mime, sliced.body.size, rangeLine, payload.extraHeaders).toByteArray())
        output.write(sliced.body)
        output.flush()
    }

    private fun head(code: Int, mime: String, length: Int?, rangeLine: String?, extra: Map<String, String>): String {
        val reason = when (code) {
            206 -> "Partial Content"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            500, 502 -> "Bad Gateway"
            else -> "OK"
        }
        return buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            if (rangeLine != null) append(rangeLine)
            extra.forEach { (key, value) ->
                if (!key.equals("Content-Length", true) && !key.equals("Transfer-Encoding", true) && !key.equals("Content-Type", true)) {
                    append("$key: $value\r\n")
                }
            }
            append("Connection: close\r\nCache-Control: no-cache\r\n\r\n")
        }
    }

    private fun rangeStart(header: String?): Long {
        if (header.isNullOrBlank()) return 0
        return header.substringAfter("=", "").substringBefore("-").trim().toLongOrNull() ?: 0
    }
}
