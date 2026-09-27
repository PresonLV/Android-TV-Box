@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.jianxia.tv.ui.detail

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import app.jianxia.core.model.AppSettings
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.ProvideCinema
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.limitedImage
import coil.compose.AsyncImage
import androidx.compose.runtime.remember

@Composable
internal fun CinemaDetail(
    vm: DetailViewModel,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val settings by LocalApp.current.settings.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.item?.key, state.sourceIndex) {
        if (state.item != null) vm.refreshSpeeds()
    }
    BackHandler(onBack = onBack)
    ProvideCinema {
        val palette = LocalPalette.current
        val item = state.item
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            when {
                state.loading -> CircularProgressIndicator(color = palette.accent, modifier = Modifier.align(Alignment.Center))
                item == null -> Text(state.error ?: "无法打开详情", color = palette.text, fontSize = 22.sp, modifier = Modifier.padding(28.dp))
                else -> {
                    val variant = item.variants.getOrNull(state.sourceIndex) ?: item.variants.firstOrNull()
                    val line = variant?.lines?.getOrNull(state.lineIndex)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().height(280.dp)) {
                            DetailBackdrop(item.pic, item.title, settings.reduceMotion)
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(0.86f), Color.Transparent))))
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, palette.bg))))
                            Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 180.dp, bottom = 16.dp)) {
                                Text(item.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val tags = listOfNotNull(item.year, item.area, item.typeName, item.remarks).filter { it.isNotBlank() }
                                if (tags.isNotEmpty()) {
                                    Text(tags.joinToString("   ·   "), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp))
                                }
                                if (!item.content.isNullOrBlank()) {
                                    Text(item.content.orEmpty().replace("\n", " "), color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                                    TvButton(if (state.resumeMs > 10_000) "继续播放" else "播放", primary = true) { vm.play(onPlay) }
                                    TvButton(if (state.favorite) "已收藏" else "收藏", onClick = vm::toggleFavorite)
                                    TvButton("返回", onClick = onBack)
                                }
                            }
                        }
                        if (!item.actor.isNullOrBlank()) {
                            Text("演员  ${item.actor}", color = palette.muted, modifier = Modifier.padding(start = 28.dp, top = 8.dp))
                        }
                        Text(
                            "共 ${item.variants.size} 个来源，${item.variants.sumOf { it.lines.size }} 条线路",
                            color = palette.accent,
                            modifier = Modifier.padding(start = 28.dp, top = 8.dp),
                        )
                        if (item.variants.size > 1) {
                            Text("来源", color = palette.text, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp),
                                modifier = Modifier.focusRestorer(),
                            ) {
                                itemsIndexed(item.variants, key = { index, source -> "$index-${source.sourceKey}" }) { index, source ->
                                    LineChip(source.sourceName, null, index == state.sourceIndex) { vm.source(index) }
                                }
                            }
                        }
                        Text("线路", color = palette.text, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp))
                        if (variant == null || variant.lines.isEmpty()) {
                            Text("这个来源没有可播放的线路。", color = palette.muted, modifier = Modifier.padding(start = 28.dp))
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp),
                                modifier = Modifier.focusRestorer(),
                            ) {
                                itemsIndexed(variant.lines, key = { index, playLine -> "$index-${playLine.name}" }) { index, playLine ->
                                    val id = "${variant.sourceKey}::${playLine.name}"
                                    LineChip(playLine.name, speeds[id] ?: "测速中", index == state.lineIndex) { vm.line(index) }
                                }
                            }
                            Text("选集", color = palette.text, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp),
                                modifier = Modifier.focusRestorer(),
                            ) {
                                itemsIndexed(line?.episodes.orEmpty(), key = { index, episode -> "$index-${episode.name}" }) { index, episode ->
                                    EpisodeCard(episode.name, index == state.episodeIndex, settings.reduceMotion) { vm.episode(index) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBackdrop(url: String?, title: String, reduceMotion: Boolean) {
    val context = LocalContext.current
    val blur = !reduceMotion && Build.VERSION.SDK_INT >= 31 && !url.isNullOrBlank()
    Box(Modifier.fillMaxSize().background(Color(0xFF10131A))) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = remember(url) { limitedImage(context, url, 960, 540, fade = false) },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val scale = if (blur) 1.06f else 1f
                    scaleX = scale
                    scaleY = scale
                }.then(if (blur) Modifier.blur(12.dp) else Modifier),
            )
        }
    }
}

@Composable
private fun LineChip(name: String, speed: String?, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.accent else palette.surface,
            contentColor = if (selected) palette.onAccent else palette.text,
            focusedContainerColor = if (selected) palette.accent else palette.surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(name, color = if (selected) palette.onAccent else palette.text, fontSize = 15.sp, maxLines = 1)
            if (!speed.isNullOrBlank()) {
                val tone = when {
                    speed.startsWith("不可用") -> palette.danger
                    selected -> palette.onAccent
                    else -> palette.ok
                }
                Text(speed, color = tone, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun EpisodeCard(name: String, selected: Boolean, reduceMotion: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier.width(148.dp).height(72.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.accent.copy(alpha = 0.22f) else palette.surface,
            focusedContainerColor = palette.surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(12.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(12.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduceMotion) 1f else 1.06f),
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.CenterStart) {
            Text(name, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        }
    }
}
