@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.jianxia.tv.ui.home

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.displayTitle
import app.jianxia.tv.AppContainer
import app.jianxia.tv.PlayRequest
import app.jianxia.tv.data.db.FavoriteEntity
import app.jianxia.tv.data.db.HistoryEntity
import app.jianxia.tv.data.repo.HomeCatalog
import app.jianxia.tv.ui.douban.DoubanHeroLine
import app.jianxia.tv.ui.douban.DoubanHomeRows
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ProvideCinema
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.limitedImage
import app.jianxia.tv.ui.posterSize
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class CinemaBus {
    var current by mutableStateOf<CinemaPick?>(null)
}

internal class CinemaPick(
    val key: String,
    val title: String,
    val image: String?,
    val year: String?,
    val area: String?,
    val typeName: String?,
    val remarks: String?,
    val blurb: String?,
    val playLabel: String,
    val play: suspend () -> Unit,
    val open: () -> Unit,
)

@Composable
internal fun CinemaHome(
    app: AppContainer,
    settings: AppSettings,
    sourcesEmpty: Boolean,
    history: List<HistoryEntity>,
    favorites: List<FavoriteEntity>,
    catalog: HomeCatalog?,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onSites: () -> Unit,
    showEmpty: Boolean,
    onDouban: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val bus = remember { CinemaBus() }
    val playScope = rememberCoroutineScope()
    fun playMerged(block: suspend () -> Boolean) {
        playScope.launch {
            onBusy(true)
            try {
                if (block()) onPlay()
            } finally {
                onBusy(false)
            }
        }
    }
    ProvideCinema {
        val palette = LocalPalette.current
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            if (sourcesEmpty) {
                CinemaEmpty(onSettings)
            } else {
                Column(Modifier.fillMaxSize()) {
                    CinemaHero(bus, settings.reduceMotion)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
                        if (loading && catalog == null) {
                            CircularProgressIndicator(color = palette.accent, modifier = Modifier.padding(start = 28.dp, top = 16.dp))
                        }
                        DoubanHomeRows(onDouban, onSearch)
                        settings.homeRows.filter { it.visible }.forEach { row ->
                            when (row.id) {
                                "history" -> if (history.isNotEmpty()) {
                                    CinemaStrip("继续观看") {
                                        items(history.take(20), key = { it.titleKey }) { item ->
                                            val pick = historyPick(app, item, onOpen, ::playMerged)
                                            WideCard(item.title, item.pic, item.episodeName, progressOf(item), settings.reduceMotion, { bus.current = pick }, pick.open)
                                        }
                                    }
                                }
                                "favorite" -> if (favorites.isNotEmpty()) {
                                    val (w, h) = posterSize(settings.posterSize)
                                    CinemaStrip(row.displayTitle()) {
                                        items(favorites.take(24), key = { it.titleKey }) { item ->
                                            val pick = favoritePick(app, item, onOpen, ::playMerged)
                                            PosterTile(item.title, item.pic, item.typeName, w, h, settings.reduceMotion, { bus.current = pick }, pick.open)
                                        }
                                    }
                                }
                                else -> {
                                    val items = catalog?.rows?.get(row.id).orEmpty()
                                    if (items.isNotEmpty()) {
                                        val (w, h) = posterSize(settings.posterSize)
                                        CinemaStrip(row.displayTitle()) {
                                            items(items, key = { it.key }) { item ->
                                                val pick = vodPick(app, item, onOpen, ::playMerged)
                                                PosterTile(
                                                    item.title,
                                                    item.pic,
                                                    listOfNotNull(item.year, item.typeName).joinToString(" · "),
                                                    w,
                                                    h,
                                                    settings.reduceMotion,
                                                    { bus.current = pick },
                                                    pick.open,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (showEmpty) {
                            Text(
                                error ?: catalog?.message ?: "这些接口暂时没有返回点播内容。",
                                color = palette.muted,
                                modifier = Modifier.padding(start = 28.dp, top = 12.dp, end = 28.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 28.dp, top = 12.dp)) {
                                TvButton("重试", onClick = onRetry)
                                TvButton("查看站点状态", onClick = onSites)
                            }
                        }
                        if (!showEmpty) catalog?.message?.let { note ->
                            Text(note, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp, top = 8.dp, end = 28.dp))
                            if (catalog.reports.isNotEmpty()) {
                                TvButton("查看站点状态", modifier = Modifier.padding(start = 28.dp, top = 8.dp), onClick = onSites)
                            }
                        }
                    }
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            }
        }
    }
    LaunchedEffect(history, favorites, catalog) {
        if (bus.current != null) return@LaunchedEffect
        bus.current = when {
            history.isNotEmpty() -> historyPick(app, history.first(), onOpen, ::playMerged)
            favorites.isNotEmpty() -> favoritePick(app, favorites.first(), onOpen, ::playMerged)
            else -> catalog?.rows?.values?.firstOrNull()?.firstOrNull()?.let { vodPick(app, it, onOpen, ::playMerged) }
        }
    }
}

private fun progressOf(item: HistoryEntity): Float? =
    if (item.durationMs > 0) item.positionMs / item.durationMs.toFloat() else null

private fun historyPick(
    app: AppContainer,
    item: HistoryEntity,
    onOpen: (String) -> Unit,
    play: (suspend () -> Boolean) -> Unit,
): CinemaPick = CinemaPick(
    key = item.titleKey,
    title = item.title,
    image = item.pic,
    year = item.year,
    area = null,
    typeName = item.episodeName,
    remarks = null,
    blurb = item.episodeName,
    playLabel = "继续播放",
    play = {
        play {
            val merged = app.library.findMerged(item.titleKey) ?: return@play false
            val full = runCatching { app.catalog.hydrate(merged) }.getOrDefault(merged)
            app.session.request = PlayRequest(full, item.episodeIndex, item.positionMs, item.lineId)
            true
        }
    },
    open = { onOpen(item.titleKey) },
)

private fun favoritePick(
    app: AppContainer,
    item: FavoriteEntity,
    onOpen: (String) -> Unit,
    play: (suspend () -> Boolean) -> Unit,
): CinemaPick = vodLike(app, item.titleKey, item.title, item.pic, item.year, null, item.typeName, null, null, onOpen, play)

private fun vodPick(
    app: AppContainer,
    item: MergedVod,
    onOpen: (String) -> Unit,
    play: (suspend () -> Boolean) -> Unit,
): CinemaPick = vodLike(app, item.key, item.title, item.pic, item.year, item.area, item.typeName, item.remarks, item.content, onOpen, play)

private fun vodLike(
    app: AppContainer,
    key: String,
    title: String,
    image: String?,
    year: String?,
    area: String?,
    typeName: String?,
    remarks: String?,
    content: String?,
    onOpen: (String) -> Unit,
    play: (suspend () -> Boolean) -> Unit,
): CinemaPick = CinemaPick(
    key = key,
    title = title,
    image = image,
    year = year,
    area = area,
    typeName = typeName,
    remarks = remarks,
    blurb = content?.replace("\n", " ")?.take(90),
    playLabel = "播放",
    play = {
        play {
            val cached = app.catalog.store.get(key) ?: app.library.findMerged(key) ?: return@play false
            val full = runCatching { app.catalog.hydrate(cached) }.getOrDefault(cached)
            app.session.request = PlayRequest(full, 0, 0, null)
            true
        }
    },
    open = { onOpen(key) },
)

@Composable
private fun CinemaHero(bus: CinemaBus, reduceMotion: Boolean) {
    val palette = LocalPalette.current
    val target = bus.current
    var shown by remember { mutableStateOf(target) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target?.key) {
        if (!reduceMotion && shown != null && shown?.key != target?.key) delay(180)
        shown = target
    }
    Box(Modifier.fillMaxWidth().height(312.dp)) {
        Crossfade(
            targetState = shown?.image,
            animationSpec = if (reduceMotion) snap() else tween(280),
            label = "hero-image",
        ) { image ->
            HeroBackdrop(image, shown?.title.orEmpty(), reduceMotion)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.84f), Color.Black.copy(alpha = 0.3f), Color.Transparent),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, palette.bg)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 200.dp, bottom = 18.dp)) {
            Crossfade(
                targetState = shown,
                animationSpec = if (reduceMotion) snap() else tween(280),
                label = "hero-copy",
            ) { item ->
                Column {
                    Text(
                        item?.title ?: "个人影院",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DoubanHeroLine(item?.title, item?.year)
                    val tags = listOfNotNull(item?.year, item?.area, item?.typeName, item?.remarks).filter { it.isNotBlank() }
                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            tags.take(4).forEach { tag ->
                                Text(
                                    tag,
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    if (!item?.blurb.isNullOrBlank()) {
                        Text(
                            item?.blurb.orEmpty(),
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        )
                    }
                }
            }
            if (shown != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                    TvButton(shown?.playLabel ?: "播放", primary = true) {
                        val action = shown?.play ?: return@TvButton
                        scope.launch { action() }
                    }
                    TvButton("详情") { shown?.open?.invoke() }
                }
            }
        }
    }
}

@Composable
private fun HeroBackdrop(url: String?, title: String, reduceMotion: Boolean) {
    val context = LocalContext.current
    val blur = !reduceMotion && Build.VERSION.SDK_INT >= 31 && !url.isNullOrBlank()
    Box(Modifier.fillMaxSize().background(Color(0xFF10131A)), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = remember(url) { limitedImage(context, url, 960, 540, fade = false) },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = if (blur) 1.08f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(if (blur) Modifier.blur(16.dp) else Modifier),
            )
        } else if (title.isNotBlank()) {
            Text(title.take(1), color = Color.White.copy(alpha = 0.45f), fontSize = 72.sp)
        }
    }
}

@Composable
private fun CinemaStrip(title: String, content: LazyListScope.() -> Unit) {
    val palette = LocalPalette.current
    Text(
        title,
        color = palette.text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 28.dp, end = 36.dp, bottom = 12.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        content()
    }
}

@Composable
private fun WideCard(
    title: String,
    image: String?,
    subtitle: String?,
    progress: Float?,
    reduceMotion: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier.width(228.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = palette.surface, focusedContainerColor = palette.surface2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduceMotion) 1f else 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = palette.accent.copy(alpha = 0.55f), elevation = if (reduceMotion) 0.dp else 14.dp),
        ),
    ) {
        Column {
            app.jianxia.tv.ui.Poster(image, title, Modifier.fillMaxWidth().height(128.dp), maxWidthPx = 456, maxHeightPx = 256, fade = !reduceMotion)
            Text(title, color = palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
            Text(subtitle.orEmpty(), color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(99.dp)).background(palette.stroke)) {
                if (progress != null) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(palette.accent))
                }
            }
        }
    }
}

@Composable
private fun PosterTile(
    title: String,
    image: String?,
    subtitle: String?,
    width: Dp,
    height: Dp,
    reduceMotion: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier.width(width).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = palette.surface, focusedContainerColor = palette.surface2),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (reduceMotion) 1f else 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = palette.accent.copy(alpha = 0.55f), elevation = if (reduceMotion) 0.dp else 16.dp),
        ),
    ) {
        Column {
            app.jianxia.tv.ui.Poster(image, title, Modifier.fillMaxWidth().height(height), fade = !reduceMotion)
            Text(title, color = palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
            Text(subtitle.orEmpty(), color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun CinemaEmpty(onSettings: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxSize().padding(36.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f)) {
            Text("还没有接口", color = palette.text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
            Text(
                "个人影院不内置任何片源。推荐用手机扫描右侧二维码添加。也可以用电视上的屏幕键盘输入网址。",
                color = palette.muted,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 22.dp),
            )
            TvButton("在电视上添加", primary = true, onClick = onSettings)
        }
        PhoneQrCard()
    }
}
