package app.jianxia.tv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jianxia.core.danmaku.DanmakuCue
import app.jianxia.core.danmaku.DanmakuMode
import app.jianxia.core.subtitle.Subtitles
import app.jianxia.tv.player.OverlayUi
import kotlin.math.roundToInt

@Composable
fun PlaybackOverlay(overlay: OverlayUi, positionMs: Long, playing: Boolean) {
    val clock = rememberPlaybackClock(positionMs, playing)
    Box(Modifier.fillMaxSize()) {
        if (overlay.danmakuOn && overlay.danmaku.isNotEmpty()) {
            DanmakuLayer(overlay, clock)
        }
        if (!overlay.nativeSubtitle && overlay.subtitles.isNotEmpty()) {
            SubtitleLayer(overlay, clock)
        }
    }
}

@Composable
private fun rememberPlaybackClock(positionMs: Long, playing: Boolean): Long {
    var anchorPosition by remember { mutableLongStateOf(positionMs) }
    var anchorAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(positionMs) {
        anchorPosition = positionMs
        anchorAt = System.currentTimeMillis()
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameMillis { frame -> now = frame }
        }
    }
    return if (playing) anchorPosition + (now - anchorAt).coerceAtLeast(0) else positionMs
}

@Composable
private fun DanmakuLayer(overlay: OverlayUi, clockMs: Long) {
    val travel = when (overlay.speed) {
        "slow" -> 14_000.0
        "fast" -> 6_000.0
        else -> 9_000.0
    }
    val fraction = when (overlay.area) {
        "quarter" -> 0.28f
        "full" -> 0.9f
        else -> 0.5f
    }
    val font = when (overlay.font) {
        "small" -> 16.sp
        "large" -> 28.sp
        else -> 22.sp
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val height = constraints.maxHeight.toFloat()
        val laneHeight = font.value * 3.2f
        val lanes = (height * fraction / laneHeight).toInt().coerceIn(1, 12)
        val visible = overlay.danmaku.mapNotNull { cue ->
            val start = cue.timeSec * 1000
            val age = clockMs - start
            if (cue.mode == DanmakuMode.Scroll) {
                if (age < 0 || age > travel) return@mapNotNull null
            } else if (age < 0 || age > 4_000) {
                return@mapNotNull null
            }
            cue to age
        }.take(36)
        visible.forEach { (cue, age) ->
            val lane = kotlin.math.abs((cue.text.hashCode() * 31 + cue.timeSec.hashCode()) % lanes)
            val color = Color(cue.color or 0xFF000000.toInt()).copy(alpha = overlay.opacity / 100f)
            val style = androidx.compose.ui.text.TextStyle(
                color = color,
                fontSize = font,
                shadow = Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 6f),
            )
            when (cue.mode) {
                DanmakuMode.Scroll -> {
                    val progress = (age / travel).toFloat().coerceIn(0f, 1f)
                    val textWidth = (cue.text.length * font.value * 1.15f).coerceAtLeast(40f)
                    val x = width - progress * (width + textWidth)
                    Text(
                        cue.text,
                        style = style,
                        maxLines = 1,
                        modifier = Modifier.offset { IntOffset(x.roundToInt(), (lane * laneHeight).roundToInt()) },
                    )
                }
                DanmakuMode.Top -> Text(
                    cue.text,
                    style = style,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = (8 + lane * 4).dp),
                )
                DanmakuMode.Bottom -> Text(
                    cue.text,
                    style = style,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (72 + lane * 4).dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleLayer(overlay: OverlayUi, clockMs: Long) {
    val shown = Subtitles.visible(overlay.subtitles, clockMs, overlay.offsetMs.toLong())
    if (shown.isEmpty()) return
    val align = when (overlay.position) {
        "top" -> Alignment.TopCenter
        "middle" -> Alignment.Center
        else -> Alignment.BottomCenter
    }
    val padding = when (overlay.position) {
        "top" -> Modifier.padding(top = 48.dp, start = 48.dp, end = 48.dp)
        "middle" -> Modifier.padding(horizontal = 48.dp)
        else -> Modifier.padding(bottom = 72.dp, start = 48.dp, end = 48.dp)
    }
    val font = when (overlay.size) {
        "small" -> 18.sp
        "large" -> 36.sp
        else -> 26.sp
    }
    Box(Modifier.fillMaxSize(), contentAlignment = align) {
        Text(
            shown.joinToString("\n") { it.text },
            color = Color((shown.first().color ?: 0xFFFFFF) or 0xFF000000.toInt()),
            fontSize = font,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(
                shadow = Shadow(Color.Black, blurRadius = 8f),
            ),
            modifier = padding,
        )
    }
}
