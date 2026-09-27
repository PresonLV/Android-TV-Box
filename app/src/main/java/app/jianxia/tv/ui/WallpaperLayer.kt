package app.jianxia.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jianxia.core.model.AppSettings
import coil.compose.AsyncImage

@Composable
fun WallpaperLayer(settings: AppSettings, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Box(modifier) {
        when (settings.backgroundType) {
            "none" -> Box(Modifier.fillMaxSize().background(palette.bg))
            "solid" -> Box(Modifier.fillMaxSize().background(hexColor(settings.solidColor, palette.bg)))
            "image" -> {
                val blurMod = if (Build.VERSION.SDK_INT >= 31 && settings.wallpaperBlur > 0) {
                    Modifier.blur((settings.wallpaperBlur * 1.4f).dp)
                } else {
                    Modifier
                }
                AsyncImage(
                    model = settings.backgroundImageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().then(blurMod),
                    contentScale = ContentScale.Crop,
                )
            }
            else -> {
                val bitmap = remember(settings.wallpaperId, settings.wallpaperBlur) {
                    loadWallpaper(context, settings.wallpaperId.ifBlank { "ink" }, settings.wallpaperBlur)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(palette.gradient))
                }
            }
        }
        if (settings.backgroundType != "none" && settings.wallpaperDim > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.wallpaperDim / 100f)))
        }
    }
}

internal fun loadWallpaper(context: Context, id: String, blur: Int): ImageBitmap? {
    val stream = runCatching { context.assets.open("wallpapers/$id.webp") }.getOrNull() ?: return null
    val decoded = stream.use { BitmapFactory.decodeStream(it) } ?: return null
    val width = 160
    val height = (width * decoded.height / decoded.width).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(decoded, width, height, true)
    if (small != decoded) decoded.recycle()
    val softened = if (blur <= 0) small else boxBlur(small, (blur / 3).coerceIn(1, 8))
    if (softened != small) small.recycle()
    return softened.asImageBitmap()
}

private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val tmp = IntArray(pixels.size)
    blurPass(pixels, tmp, width, height, radius, horizontal = true)
    blurPass(tmp, pixels, width, height, radius, horizontal = false)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

private fun blurPass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    for (y in 0 until height) {
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (offset in -radius..radius) {
                val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val color = input[sy * width + sx]
                alpha += color ushr 24 and 0xff
                red += color ushr 16 and 0xff
                green += color ushr 8 and 0xff
                blue += color and 0xff
                count += 1
            }
            output[y * width + x] = (alpha / count shl 24) or (red / count shl 16) or (green / count shl 8) or (blue / count)
        }
    }
}

internal fun hexColor(hex: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(fallback)
