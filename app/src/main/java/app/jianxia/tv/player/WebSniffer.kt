package app.jianxia.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.jianxia.core.parser.isDirectMediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 解析页没有直接媒体地址时，用隐藏 WebView 截获第一个 m3u8/mp4 请求。 */
object WebSniffer {
    suspend fun sniff(context: Context, pageUrl: String, headers: Map<String, String>, timeoutMs: Long = 15_000): String? {
        val target = pageUrl.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://")) return null
        if (isDirectMediaUrl(target)) return target
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val web = try {
                        WebView(context)
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val handler = Handler(Looper.getMainLooper())
                    fun finish(url: String?) {
                        if (!cont.isActive) return
                        cont.resume(url)
                        handler.post {
                            runCatching {
                                web.stopLoading()
                                web.destroy()
                            }
                        }
                    }
                    cont.invokeOnCancellation {
                        handler.post { runCatching { web.destroy() } }
                    }
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.mediaPlaybackRequiresUserGesture = false
                    headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.takeIf { it.isNotBlank() }?.let {
                        web.settings.userAgentString = it
                    }
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url?.toString().orEmpty()
                            if (playable(url)) finish(url)
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    val extra = headers.filterKeys { key ->
                        !key.equals("User-Agent", true) && key.isNotBlank()
                    }
                    web.loadUrl(target, extra)
                }
            }
        }
    }

    private fun playable(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (isDirectMediaUrl(url)) return true
        val path = url.lowercase().substringBefore('?')
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".gif") || path.endsWith(".html")) {
            return false
        }
        return path.contains(".m3u8") || path.contains(".mp4") || path.contains(".flv") || path.contains(".mkv")
    }
}
