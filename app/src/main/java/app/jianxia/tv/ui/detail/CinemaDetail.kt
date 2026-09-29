@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.jianxia.tv.ui.detail

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import app.jianxia.tv.ui.douban.DoubanComments
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.ProvideCinema
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.cornerShape
import app.jianxia.tv.ui.focusBorder
import app.jianxia.tv.ui.focusGlow
import app.jianxia.tv.ui.focusScale
import app.jianxia.tv.ui.limitedImage
import coil.compose.AsyncImage
import androidx.compose.runtime.remember

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CinemaDetail(
    vm: DetailViewModel,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val showParse = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val parses = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<app.jianxia.core.model.ParseDef>>(emptyList()) }
    LaunchedEffect(Unit) {
        parses.value = runCatching { app.catalog.parses() }.getOrDefault(emptyList())
    }
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
                    val expanded = androidx.compose.runtime.remember(item.key) { androidx.compose.runtime.mutableStateOf(false) }
                    val safe = ScreenPadding()
                    Box(Modifier.fillMaxSize()) {
                        DetailBackdrop(item.pic, item.title, settings.reduceMotion)
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.72f), palette.bg),
                                ),
                            ),
                        )
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(safe)) {
                            Row(verticalAlignment = Alignment.Top) {
                                PosterFrame(item.pic, item.title)
                                Column(Modifier.padding(start = 24.dp).weight(1f)) {
                                    Text(item.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val tags = listOfNotNull(
                                        shown(state.doubanRating)?.let { "豆瓣 $it" },
                                        shown(item.year),
                                        shown(item.typeName),
                                        shown(item.area),
                                        shown(item.remarks),
                                    )
                                    if (tags.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(top = 16.dp),
                                        ) {
                                            tags.forEach { MetaTag(it) }
                                        }
                                    }
                                    val blurb = item.content?.replace("\n", " ")?.trim().orEmpty()
                                    if (blurb.isNotBlank()) {
                                        Text(
                                            blurb,
                                            color = Color.White.copy(alpha = 0.82f),
                                            fontSize = 16.sp,
                                            lineHeight = 24.sp,
                                            maxLines = if (expanded.value) 8 else 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 16.dp),
                                        )
                                        if (blurb.length > 72) {
                                            TvButton(
                                                if (expanded.value) "收起简介" else "展开简介",
                                                modifier = Modifier.padding(top = 8.dp),
                                            ) { expanded.value = !expanded.value }
                                        }
                                    }
                                    val director = shown(item.director)
                                    val actorSource = when {
                                        state.doubanPeople.contains("演员") -> state.doubanPeople.substringAfter("演员")
                                        item.actor.isNullOrBlank() -> state.doubanPeople
                                        else -> item.actor
                                    }
                                    val actor = shown(actorSource)
                                    if (director != null) {
                                        Text("导演  $director", color = palette.muted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                                    }
                                    if (actor != null) {
                                        Text("演员  $actor", color = palette.muted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    }
                                    if (state.doubanNote.isNotBlank() && state.doubanRating.isBlank()) {
                                        Text(state.doubanNote, color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                                    }
                                    if (!state.notice.isNullOrBlank()) {
                                        Text(state.notice.orEmpty(), color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 16.dp),
                                    ) {
                                        TvButton(if (state.resumeMs > 10_000) "继续播放" else "播放", primary = true) { vm.play(onPlay) }
                                        TvButton(if (state.favorite) "已收藏" else "收藏", onClick = vm::toggleFavorite)
                                        TvButton("换源") {
                                            val count = item.variants.size
                                            if (count > 1) vm.source((state.sourceIndex + 1) % count)
                                        }
                                        TvButton("搜索") { onSearch(item.title) }
                                        TvButton("解析") { showParse.value = !showParse.value }
                                        TvButton("返回", onClick = onBack)
                                    }
                                    if (showParse.value) {
                                        Text(
                                            if (parses.value.isEmpty()) "没有配置解析接口" else parses.value.joinToString("、") { it.name },
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }
                            }
                            DoubanComments(state.comments, state.commentsMore, vm::moreComments)
                            Text(
                                "共 ${item.variants.size} 个来源，${item.variants.sumOf { it.lines.size }} 条线路",
                                color = palette.accent,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                            if (item.variants.size > 1) {
                                Text("来源", color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusRestorer()) {
                                    itemsIndexed(item.variants, key = { index, source -> "$index-${source.sourceKey}" }) { index, source ->
                                        LineChip(source.sourceName, null, index == state.sourceIndex) { vm.source(index) }
                                    }
                                }
                            }
                            Text("线路", color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                            if (variant == null || variant.lines.isEmpty()) {
                                Text("这个来源没有可播放的线路。", color = palette.muted, fontSize = 16.sp)
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusRestorer()) {
                                    itemsIndexed(variant.lines, key = { index, playLine -> "$index-${playLine.name}" }) { index, playLine ->
                                        val id = "${variant.sourceKey}::${playLine.name}"
                                        LineChip(playLine.name, speeds[id] ?: "测速中", index == state.lineIndex) { vm.line(index) }
                                    }
                                }
                                EpisodePager(
                                    episodes = line?.episodes.orEmpty().map { it.name },
                                    selected = state.episodeIndex,
                                    reversed = state.reversed,
                                    onToggleOrder = vm::toggleOrder,
                                    onSelect = { index ->
                                        vm.episode(index)
                                        vm.play(onPlay)
                                    },
                                )
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
                    val scale = if (blur) 1.12f else 1.04f
                    scaleX = scale
                    scaleY = scale
                }.then(if (blur) Modifier.blur(28.dp) else Modifier),
            )
        }
    }
}

@Composable
private fun PosterFrame(url: String?, title: String) {
    val shape = cornerShape()
    Box(
        Modifier
            .width(240.dp)
            .height(344.dp)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
    ) {
        app.jianxia.tv.ui.Poster(url, title, Modifier.fillMaxSize(), maxWidthPx = 480, maxHeightPx = 688, fade = false)
    }
}

@Composable
private fun MetaTag(text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private fun shown(value: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isBlank() || text == "0" || text == "0.0" || text == "0000" || text.equals("null", true) || text.equals("undefined", true)) {
        return null
    }
    return text
}

@Composable
private fun LineChip(name: String, speed: String?, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = cornerShape()
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.accent else palette.surface,
            contentColor = if (selected) palette.onAccent else palette.text,
            focusedContainerColor = if (selected) palette.accent else palette.surface2,
        ),
        border = if (selected) {
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(2.dp, palette.accent), shape = shape),
                focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = shape),
            )
        } else {
            focusBorder(shape)
        },
        scale = focusScale(),
        glow = focusGlow(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(name, color = if (selected) palette.onAccent else palette.text, fontSize = 15.sp, maxLines = 1)
            if (!speed.isNullOrBlank()) {
                val tone = when {
                    speed.startsWith("不可用") || speed.startsWith("未测通") -> palette.danger
                    speed == "网盘" || speed == "本地代理" -> if (selected) palette.onAccent else palette.muted
                    selected -> palette.onAccent
                    else -> palette.ok
                }
                Text(speed, color = tone, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
