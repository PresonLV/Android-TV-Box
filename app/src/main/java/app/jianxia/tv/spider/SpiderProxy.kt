package app.jianxia.tv.spider

import com.github.catvod.spider.Proxy
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ProxyPayload(val code: Int, val mime: String, val bytes: ByteArray)

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
        if (!url.startsWith("proxy://")) return url
        if (!ensure()) return url
        val query = url.removePrefix("proxy://").trimStart('?')
        val site = java.net.URLEncoder.encode(siteKey, "UTF-8")
        val joiner = if (query.isBlank()) "" else "&"
        return "http://127.0.0.1:$port/proxy?site=$site$joiner$query"
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
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val request = reader.readLine().orEmpty()
        val rawQuery = request.split(" ").getOrNull(1).orEmpty().substringAfter("?", "")
        val params = linkedMapOf<String, String>()
        rawQuery.split("&").forEach { part ->
            if (part.isBlank()) return@forEach
            val key = URLDecoder.decode(part.substringBefore("="), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter("=", ""), "UTF-8")
            params[key] = value
        }
        val site = params.remove("site").orEmpty()
        val payload = if (site.isBlank()) {
            ProxyPayload(404, "text/plain", "missing".toByteArray())
        } else {
            handler(site, params)
        }
        write(client, payload)
    }

    private fun write(client: Socket, payload: ProxyPayload) {
        val head = "HTTP/1.1 ${payload.code} OK\r\nContent-Type: ${payload.mime}\r\nContent-Length: ${payload.bytes.size}\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n"
        val output = client.getOutputStream()
        output.write(head.toByteArray())
        output.write(payload.bytes)
        output.flush()
    }
}
