package app.jianxia.tv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.jianxia.core.model.AppSettings
import app.jianxia.tv.AppContainer

val LocalApp = staticCompositionLocalOf<AppContainer> { error("简匣还没有初始化") }
val LocalPalette = staticCompositionLocalOf { darkPalette(Color(0xFFE2B15A)) }

data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val ok: Color,
    val stroke: Color,
    val gradient: Brush,
)

fun posterSize(name: String): Pair<Dp, Dp> = when (name) {
    "small" -> 112.dp to 158.dp
    "large" -> 168.dp to 236.dp
    else -> 136.dp to 192.dp
}

fun formatClock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

fun kindLabel(kind: String): String = when (kind) {
    "tvbox" -> "TVBox 配置"
    "maccms_json" -> "苹果 CMS JSON"
    "maccms_xml" -> "苹果 CMS XML"
    "live" -> "直播"
    "failed" -> "加载失败，可重试"
    else -> "未知格式"
}

fun navKey(value: String): String =
    android.util.Base64.encodeToString(
        value.toByteArray(),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
    )

fun fromNav(value: String): String = runCatching {
    String(
        android.util.Base64.decode(
            value,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        ),
    )
}.getOrDefault(value)

@Composable
fun JianXiaTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val accent = parseAccent(settings.accent)
    val dark = when (settings.themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val palette = if (dark) darkPalette(accent, settings.gradientId) else lightPalette(accent, settings.gradientId)
    val scheme = if (palette.dark) {
        darkColorScheme(primary = palette.accent, background = palette.bg, surface = palette.surface, onSurface = palette.text)
    } else {
        lightColorScheme(primary = palette.accent, background = palette.bg, surface = palette.surface, onSurface = palette.text)
    }
    val density = LocalDensity.current
    val scale = when (settings.fontScale) {
        "small" -> 0.92f
        "large" -> 1.14f
        "xlarge" -> 1.28f
        else -> 1f
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalDensity provides Density(density.density, density.fontScale * scale),
            content = content,
        )
    }
}

private fun parseAccent(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFFE2B15A))

private fun darkPalette(accent: Color, gradientId: String = "ink") = Palette(
    dark = true,
    bg = Color(0xFF0C0E13),
    surface = Color(0xFF171C27),
    surface2 = Color(0xFF232A3A),
    text = Color(0xFFF6F3EC),
    muted = Color(0xFFA7A092),
    accent = accent,
    onAccent = Color(0xFF1C160C),
    danger = Color(0xFFE07A6A),
    ok = Color(0xFF8FCB7B),
    stroke = Color(0xFF31384A),
    gradient = gradient(gradientId, dark = true),
)

private fun lightPalette(accent: Color, gradientId: String = "ink") = Palette(
    dark = false,
    bg = Color(0xFFF6F3EC),
    surface = Color(0xFFFFFCF7),
    surface2 = Color(0xFFF0E7D8),
    text = Color(0xFF1C1915),
    muted = Color(0xFF6F675C),
    accent = accent,
    onAccent = Color(0xFF1C160C),
    danger = Color(0xFFB94B45),
    ok = Color(0xFF2F7D4A),
    stroke = Color(0xFFE4D8C4),
    gradient = gradient(gradientId, dark = false),
)

private fun gradient(id: String, dark: Boolean): Brush {
    val colors = when (id) {
        "dusk" -> if (dark) listOf(Color(0xFF16121C), Color(0xFF2A1A2E), Color(0xFF100E14))
        else listOf(Color(0xFFF7F1F8), Color(0xFFE9D7EF), Color(0xFFF6F3EC))
        "ocean" -> if (dark) listOf(Color(0xFF0C1418), Color(0xFF12343A), Color(0xFF0C1014))
        else listOf(Color(0xFFF2F7F7), Color(0xFFD5E7E6), Color(0xFFF6F3EC))
        "forest" -> if (dark) listOf(Color(0xFF101510), Color(0xFF1A2E22), Color(0xFF0E1210))
        else listOf(Color(0xFFF3F6F1), Color(0xFFD7E6D4), Color(0xFFF6F3EC))
        "ember" -> if (dark) listOf(Color(0xFF160E0C), Color(0xFF3A2018), Color(0xFF120E0C))
        else listOf(Color(0xFFF8F3EE), Color(0xFFF0D2C2), Color(0xFFF6F3EC))
        else -> if (dark) listOf(Color(0xFF0C0E13), Color(0xFF1A160F), Color(0xFF10131A))
        else listOf(Color(0xFFF7F4EE), Color(0xFFE7D7BE), Color(0xFFF6F3EC))
    }
    return Brush.verticalGradient(colors)
}
