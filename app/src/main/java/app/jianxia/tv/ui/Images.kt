package app.jianxia.tv.ui

import android.content.Context
import app.jianxia.tv.data.net.ResilientDns
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val BROWSER =
    "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

fun installImageLoader(context: Context) {
    val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(ResilientDns())
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val builder = request.newBuilder()
            if (request.header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", BROWSER)
            }
            if (request.header("Referer").isNullOrBlank() && (host.contains("doubanio.com") || host.contains("douban.com"))) {
                builder.header("Referer", "https://movie.douban.com/")
            }
            chain.proceed(builder.build())
        }
        .build()
    val loader = ImageLoader.Builder(context)
        .okHttpClient(http)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(24 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("posters"))
                .maxSizeBytes(48L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .build()
    Coil.setImageLoader(loader)
}

fun limitedImage(
    context: Context,
    url: String,
    widthPx: Int,
    heightPx: Int,
    fade: Boolean,
    headers: Map<String, String> = emptyMap(),
): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, heightPx)
        .precision(Precision.INEXACT)
        .crossfade(if (fade) 180 else 0)
        .apply {
            headers.forEach { (key, value) ->
                if (value.isNotBlank()) addHeader(key, value)
            }
        }
        .build()
