package app.jianxia.tv.player

import app.jianxia.core.hls.HlsAdFilter
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HlsRewriteProxy(private val http: OkHttpClient) {
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val pool = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null
    private var port = 0

    fun wrap(url: String, headers: Map<String, String>, rules: List<Regex>): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        if (!url.contains("m3u8", ignoreCase = true)) return url
        if (!ensure()) return url
        val id = remember(Session(url, headers, rules))
        return "http://127.0.0.1:$port/p/$id.m3u8"
    }

    private fun ensure(): Boolean {
        if (running.get() && socket?.isClosed == false) return true
        return try {
            val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            socket = server
            port = server.localPort
            running.set(true)
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
                        } catch (_: Exception) {
                            // 单次改写失败不影响播放器的下一次请求。
                        } finally {
                            runCatching { client.close() }
                        }
                    }
                }
            }, "hls-rewrite").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 12_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine() ?: return
        val path = request.split(" ").getOrNull(1).orEmpty().substringBefore("?")
        val id = path.substringAfterLast("/").removeSuffix(".m3u8")
        val session = sessions[id]
        if (session == null) {
            write(client, 404, "text/plain", "missing")
            return
        }
        val upstream = fetch(session.url, session.headers)
        if (upstream == null) {
            write(client, 502, "text/plain", "upstream")
            return
        }
        val body = if (HlsAdFilter.looksLikePlaylist(upstream.text)) {
            HlsAdFilter.rewrite(upstream.text, upstream.finalUrl, session.rules) { nested ->
                val nestedId = remember(session.copy(url = nested))
                "http://127.0.0.1:$port/p/$nestedId.m3u8"
            }.text
        } else {
            upstream.text
        }
        write(client, 200, "application/vnd.apple.mpegurl", body)
    }

    private fun fetch(url: String, headers: Map<String, String>): Remote? {
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", headers["User-Agent"] ?: app.jianxia.tv.data.net.Ua.MEDIA)
            headers.forEach { (key, value) ->
                if (value.isNotBlank() && !key.equals("User-Agent", true)) builder.header(key, value)
            }
            http.newCall(builder.build()).execute().use { response ->
                if (response.code !in 200..299) return null
                val text = response.body?.byteStream()?.use { stream ->
                    val buffer = ByteArray(2_000_000)
                    var size = 0
                    while (size < buffer.size) {
                        val read = stream.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                    String(buffer, 0, size, Charsets.UTF_8)
                }.orEmpty()
                Remote(text, response.request.url.toString())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun write(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n"
        val output = client.getOutputStream()
        output.write(head.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun remember(session: Session): String {
        if (sessions.size > 48) sessions.keys.take(16).forEach { sessions.remove(it) }
        val id = UUID.randomUUID().toString().replace("-", "")
        sessions[id] = session
        return id
    }

    private data class Session(val url: String, val headers: Map<String, String>, val rules: List<Regex>)
    private data class Remote(val text: String, val finalUrl: String)
}
