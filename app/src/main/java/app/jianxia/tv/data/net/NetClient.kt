package app.jianxia.tv.data.net

import app.jianxia.core.UserFacingError
import app.jianxia.core.parser.ConfigDecoder
import app.jianxia.core.parser.isDirectMediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class RemoteDocument(
    val text: String,
    val finalUrl: String,
)

data class ProbeMeasure(
    val connectMs: Long,
    val firstByteMs: Long,
    val resolutionHeight: Int?,
    val ok: Boolean,
)

class NetClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .dns(ResilientDns())
        .build()

    suspend fun text(url: String, maxBytes: Int = 8_000_000): String = withContext(Dispatchers.IO) {
        textBlocking(url, maxBytes)
    }

    fun textBlocking(url: String, maxBytes: Int = 8_000_000): String = fetchConfig(url, maxBytes).text

    fun fetchConfig(url: String, maxBytes: Int = 8_000_000): RemoteDocument {
        try {
            val response = execute(url, maxBytes, range = null, callTimeoutMs = 25_000, userAgent = Ua.CONFIG)
            if (response.code !in 200..299) {
                throw IllegalStateException("请求失败（${response.code}）")
            }
            val text = ConfigDecoder.decode(response.bytes, response.contentType)
            if (text.isBlank()) throw IllegalStateException("接口没有返回内容")
            return RemoteDocument(text, response.finalUrl.ifBlank { url })
        } catch (error: Exception) {
            val message = UserFacingError.message(error)
            if (error is IllegalStateException && error.message == message) throw error
            throw IllegalStateException(message, error)
        }
    }

    fun probe(url: String): ProbeMeasure {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ProbeMeasure(0, 0, null, false)
        }
        val started = System.nanoTime()
        return try {
            val ranged = url.contains(".m3u8", ignoreCase = true).not()
            val first = execute(
                url = url,
                maxBytes = 32_768,
                range = if (ranged) "bytes=0-4095" else null,
                callTimeoutMs = 4_000,
                userAgent = Ua.MEDIA,
            )
            val elapsed = elapsedMs(started)
            val playlist = first.bodyText
            val master = playlist.contains("#EXT-X-STREAM-INF", ignoreCase = true)
            val height = Regex("RESOLUTION=\\d+x(\\d+)")
                .findAll(playlist)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull()
            var firstByte = elapsed
            if (master) {
                val variant = playlist.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (!variant.isNullOrBlank()) {
                    val variantUrl = java.net.URI(first.finalUrl).resolve(variant).toString()
                    val variantStarted = System.nanoTime()
                    val second = execute(variantUrl, 8_192, range = null, callTimeoutMs = 3_000, userAgent = Ua.MEDIA)
                    if (second.code in 200..299 || second.code == 206) {
                        firstByte = elapsed + elapsedMs(variantStarted)
                    }
                }
            }
            val ok = (first.code in 200..299 || first.code == 206) &&
                (first.bytes.isNotEmpty() || isDirectMediaUrl(url))
            ProbeMeasure(
                connectMs = first.connectMs.takeIf { it > 0 } ?: elapsed,
                firstByteMs = firstByte,
                resolutionHeight = height,
                ok = ok,
            )
        } catch (_: Exception) {
            ProbeMeasure(elapsedMs(started), elapsedMs(started), null, false)
        }
    }

    private fun execute(url: String, maxBytes: Int, range: String?, callTimeoutMs: Long, userAgent: String): RawResponse {
        val listener = TimingListener()
        val client = http.newBuilder()
            .eventListener(listener)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        if (range != null) builder.header("Range", range)
        client.newCall(builder.build()).execute().use { response ->
            val stream = response.body?.byteStream()
            val buffer = ByteArray(maxBytes)
            var size = 0
            if (stream != null) {
                while (size < maxBytes) {
                    val read = stream.read(buffer, size, maxBytes - size)
                    if (read < 0) break
                    size += read
                }
            }
            val bytes = buffer.copyOf(size)
            return RawResponse(
                code = response.code,
                bytes = bytes,
                bodyText = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault(""),
                contentType = response.header("Content-Type"),
                finalUrl = response.request.url.toString(),
                connectMs = listener.connectMs,
            )
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}

private class TimingListener : okhttp3.EventListener() {
    private val started = System.nanoTime()
    var connectMs: Long = 0
    override fun responseHeadersStart(call: okhttp3.Call) {
        connectMs = (System.nanoTime() - started) / 1_000_000
    }
}

private data class RawResponse(
    val code: Int,
    val bytes: ByteArray,
    val bodyText: String,
    val contentType: String?,
    val finalUrl: String,
    val connectMs: Long,
)

object Ua {
    /** 很多配置站只对 TVBox 常见的 OkHttp 标识返回正文。 */
    const val CONFIG = "okhttp/3.12.13"
    const val MEDIA =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 JianXia/1.0"
}
