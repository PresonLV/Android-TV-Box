package app.jianxia.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) palette.accent else palette.surface,
            contentColor = if (primary) palette.onAccent else palette.text,
            focusedContainerColor = if (primary) palette.accent else palette.surface2,
            focusedContentColor = if (primary) palette.onAccent else palette.text,
            pressedContainerColor = palette.accent,
            pressedContentColor = palette.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, palette.accent),
                shape = RoundedCornerShape(12.dp),
            ),
            focusedDisabledBorder = Border.None,
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (primary) palette.onAccent else palette.text,
            fontSize = 15.sp,
        )
    }
}

@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val idle = if (dimmed) palette.muted else palette.text
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.accent else palette.surface,
            contentColor = if (selected) palette.onAccent else idle,
            focusedContainerColor = if (selected) palette.accent else palette.surface2,
            focusedContentColor = if (selected) palette.onAccent else idle,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, palette.accent), shape = RoundedCornerShape(999.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(999.dp)),
            focusedDisabledBorder = Border.None,
        ),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (selected) palette.onAccent else idle, fontSize = 14.sp)
    }
}

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    subtitle: String?,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    rating: String? = null,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.width(width),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface,
            contentColor = palette.text,
            focusedContainerColor = palette.surface2,
            focusedContentColor = palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(16.dp)),
            focusedDisabledBorder = Border.None,
        ),
    ) {
        Column {
            Box {
                Poster(imageUrl, title, Modifier.fillMaxWidth().height(height))
                if (!rating.isNullOrBlank()) {
                    Text(
                        rating,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                title,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = palette.text,
                fontSize = 14.sp,
            )
            if (!subtitle.isNullOrBlank() || progress != null) {
                Text(
                    subtitle.orEmpty(),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.muted,
                    fontSize = 12.sp,
                )
            }
            if (progress != null) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(palette.stroke),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(palette.accent),
                    )
                }
            } else {
                Box(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun Poster(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    maxWidthPx: Int = 420,
    maxHeightPx: Int = 600,
    fade: Boolean = true,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Box(modifier.background(palette.surface2), contentAlignment = Alignment.Center) {
        Text(title.take(1).ifBlank { "片" }, color = palette.accent, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = remember(url, maxWidthPx, maxHeightPx, fade) {
                    limitedImage(context, url, maxWidthPx, maxHeightPx, fade)
                },
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = LocalPalette.current.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun EmptyHint(
    title: String,
    body: String,
    action: String,
    modifier: Modifier = Modifier,
    onAction: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(modifier = modifier.padding(top = 36.dp)) {
        Text(title, color = palette.text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Text(body, modifier = Modifier.padding(top = 12.dp, bottom = 22.dp).width(560.dp), color = palette.muted, fontSize = 16.sp, lineHeight = 24.sp)
        TvButton(action, primary = true, onClick = onAction)
    }
}

@Composable
fun QrImage(content: String, modifier: Modifier = Modifier) {
    val image = remember(content) {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.asImageBitmap()
    }
    Image(image, contentDescription = "二维码", modifier = modifier.clip(RoundedCornerShape(12.dp)))
}

@Composable
fun Keycap(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvButton(text, modifier = modifier.width(52.dp), onClick = onClick)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(palette.surface.copy(alpha = 0.92f))
            .border(1.dp, palette.stroke, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun FocusOutline(focused: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier
            .onFocusChanged { }
            .border(if (focused) 2.dp else 0.dp, palette.accent, RoundedCornerShape(12.dp)),
        content = content,
    )
}

val ScreenPadding = PaddingValues(start = 28.dp, end = 36.dp, top = 22.dp, bottom = 22.dp)

@Composable
fun PhoneQrCard(onRefreshPin: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val lan by app.lan.status.collectAsStateWithLifecycle()
    val pageUrl = if (lan.running && !lan.host.isNullOrBlank()) {
        "http://${lan.host}:${lan.port}/?pin=${lan.pin}"
    } else {
        null
    }
    Panel(modifier.width(300.dp)) {
        Text("手机扫码添加", color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            "这是添加接口的主要方式。手机和电视连同一个网络，扫码后粘贴地址。",
            color = palette.muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        if (pageUrl != null) {
            QrImage(pageUrl, Modifier.width(220.dp).height(220.dp))
            Text(pageUrl, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("口令 ${lan.pin}", color = palette.accent, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(lan.error ?: "正在启动局域网页面…", color = palette.muted, modifier = Modifier.padding(top = 8.dp))
        }
        if (onRefreshPin != null) {
            TvButton("刷新口令", modifier = Modifier.padding(top = 12.dp), onClick = onRefreshPin)
        }
    }
}
