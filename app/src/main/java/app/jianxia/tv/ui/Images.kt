package app.jianxia.tv.ui

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision

fun installImageLoader(context: Context) {
    val loader = ImageLoader.Builder(context)
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

fun limitedImage(context: Context, url: String, widthPx: Int, heightPx: Int, fade: Boolean): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, heightPx)
        .precision(Precision.INEXACT)
        .crossfade(if (fade) 180 else 0)
        .build()
