@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.jianxia.tv.ui.douban

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.core.douban.DoubanCard
import app.jianxia.core.douban.DoubanComment
import app.jianxia.core.douban.DoubanFilter
import app.jianxia.core.douban.DoubanParse
import app.jianxia.core.model.FilterGroup
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.VodClass
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PosterCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.posterSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private data class PosterHit(
    val key: String,
    val title: String,
    val image: String?,
    val rating: String,
    val subtitle: String,
    val fromDouban: Boolean,
)

@Composable
fun DoubanScreen(
    onOpen: (String) -> Unit,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    siteKey: String? = null,
) {
    val palette = LocalPalette.current
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val siteMode = !siteKey.isNullOrBlank()
    val kind = "movie"
    var featured by remember(siteKey) { mutableStateOf("") }
    var year by remember(siteKey) { mutableStateOf("") }
    var area by remember(siteKey) { mutableStateOf("") }
    var genre by remember(siteKey) { mutableStateOf("") }
    var sort by remember(siteKey) { mutableStateOf("U") }
    var typeId by remember(siteKey) { mutableStateOf("") }
    var picked by remember(siteKey) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var classes by remember(siteKey) { mutableStateOf<List<VodClass>>(emptyList()) }
    var siteFilters by remember(siteKey) { mutableStateOf<Map<String, List<FilterGroup>>>(emptyMap()) }
    var siteName by remember(siteKey) { mutableStateOf("") }
    var collapsed by remember(siteKey) { mutableStateOf(false) }
    var expandFocus by remember(siteKey) { mutableStateOf(false) }
    var focusedIndex by remember(siteKey) { mutableIntStateOf(-1) }
    var page by remember(siteKey) { mutableIntStateOf(0) }
    var loading by remember(siteKey) { mutableStateOf(false) }
    var loadingMore by remember(siteKey) { mutableStateOf(false) }
    var hasMore by remember(siteKey) { mutableStateOf(false) }
    var note by remember(siteKey) { mutableStateOf("") }
    var opening by remember(siteKey) { mutableStateOf(false) }
    var items by remember(siteKey) { mutableStateOf<List<PosterHit>>(emptyList()) }
    val years = remember { DoubanFilter.years(Calendar.getInstance().get(Calendar.YEAR)) }
    val lastRow = remember(siteKey) { FocusRequester() }
    val gridState = rememberLazyGridState()
    val featuredTags = DoubanFilter.featured(kind)
    val genreTags = DoubanFilter.genres(kind)
    val activeGroups = siteFilters[typeId].orEmpty().ifEmpty {
        if (typeId.isBlank()) siteFilters[""].orEmpty() else emptyList()
    }
    val signature = if (siteMode) {
        "site|$siteKey|$typeId|${picked.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}|${settings.searchTimeoutSec}"
    } else {
        "filter|$featured|$year|$area|$genre|$sort|${settings.doubanEnabled}|${settings.doubanDataProxy}|${settings.doubanDataProxyUrl}"
    }
    var debounced by remember(siteKey) { mutableStateOf(signature) }
    LaunchedEffect(signature) {
        if (signature != debounced) delay(340)
        page = 0
        debounced = signature
    }
    LaunchedEffect(debounced, page, siteKey) {
        val replace = page == 0
        if (replace) loading = true else loadingMore = true
        val start = page * 20
        val loaded = if (siteMode) {
            val result = runCatching {
                app.catalog.browseSite(siteKey.orEmpty(), page + 1, typeId, picked.filterValues { it.isNotBlank() })
            }.getOrNull()
            if (result == null) {
                note = "这个站点暂时不能筛选"
                emptyList()
            } else {
                if (result.name.isNotBlank()) siteName = result.name
                if (result.classes.isNotEmpty()) classes = result.classes
                if (result.filters.isNotEmpty()) siteFilters = result.filters
                note = result.message ?: if (result.items.isEmpty() && replace) "没有符合的节目" else "选中海报后直接打开这一部。"
                hasMore = when {
                    result.pageCount > 1 -> page + 1 < result.pageCount
                    result.items.size >= 20 -> true
                    else -> false
                }
                result.items.map { it.toPoster() }
            }
        } else if (!settings.doubanEnabled) {
            note = "豆瓣已关闭"
            hasMore = false
            emptyList()
        } else {
            val cards = runCatching {
                app.douban.filter(settings, kind, featured, year, area, genre, sort, start)
            }.getOrDefault(emptyList())
            hasMore = cards.size >= 20
            note = when {
                cards.isEmpty() && replace -> "没有符合的条目。可以换一组条件，或稍后再试。"
                else -> "选中海报后，会在已添加的接口里搜索并打开。不播放豆瓣上的片源。"
            }
            cards.map { it.toPoster() }
        }
        items = if (replace) loaded else items + loaded.filter { hit -> items.none { it.key == hit.key } }
        if (!replace && loaded.isEmpty()) hasMore = false
        loading = false
        loadingMore = false
    }
    LaunchedEffect(expandFocus, collapsed) {
        if (expandFocus && !collapsed) {
            runCatching { lastRow.requestFocus() }
            expandFocus = false
        }
    }
    BackHandler(onBack = onBack)
    val summary = if (siteMode) {
        val bits = mutableListOf(siteName.ifBlank { "站点" })
        classes.firstOrNull { it.id == typeId }?.name?.let { bits += it }
        activeGroups.forEach { group ->
            group.choices.firstOrNull { it.value == picked[group.key] }?.name?.let { bits += it }
        }
        bits.joinToString(" · ")
    } else {
        listOfNotNull(
            featured.takeIf { it.isNotBlank() },
            years.firstOrNull { it.range == year }?.label,
            DoubanFilter.areas.firstOrNull { it.second == area }?.first,
            genre.takeIf { it.isNotBlank() },
            DoubanFilter.sorts.firstOrNull { it.first == sort }?.second,
        ).joinToString(" · ")
    }
    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        if (siteMode) {
            Text(siteName.ifBlank { "站点筛选" }, color = palette.text, fontSize = 22.sp)
            Text(note, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        if (collapsed) {
            Text(summary, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
        } else if (siteMode) {
            if (classes.isNotEmpty()) {
                ChipRow(
                    label = "分类",
                    options = listOf("" to "全部") + classes.map { it.id to it.name },
                    selected = typeId,
                    restore = activeGroups.isEmpty(),
                    requester = if (activeGroups.isEmpty()) lastRow else null,
                    onSelect = {
                        typeId = it
                        picked = emptyMap()
                    },
                )
            }
            activeGroups.forEachIndexed { index, group ->
                val last = index == activeGroups.lastIndex
                ChipRow(
                    label = group.name,
                    options = listOf("" to "全部") + group.choices.map { it.value to it.name },
                    selected = picked[group.key].orEmpty(),
                    restore = true,
                    requester = if (last) lastRow else null,
                    onSelect = { value ->
                        picked = if (value.isBlank()) picked - group.key else picked + (group.key to value)
                    },
                )
            }
        } else {
            ChipRow("精选标签", listOf("" to "全部") + featuredTags.map { it to it }, featured, restore = true, onSelect = { featured = it })
            ChipRow("年代", listOf("" to "全部") + years.map { it.range to it.label }, year, restore = true, onSelect = { year = it })
            ChipRow("地区", listOf("" to "全部") + DoubanFilter.areas.map { it.second to it.first }, area, restore = true, onSelect = { area = it })
            ChipRow("类型", listOf("" to "全部") + genreTags.map { it to it }, genre, restore = true, onSelect = { genre = it })
            ChipRow("排序", DoubanFilter.sorts, sort, restore = true, requester = lastRow, onSelect = { sort = it })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && items.isEmpty()) {
                CircularProgressIndicator(color = palette.accent, modifier = Modifier.align(Alignment.Center))
            } else if (items.isEmpty()) {
                Text(note.ifBlank { "没有符合的条目" }, color = palette.muted, modifier = Modifier.align(Alignment.Center))
            } else {
                val (posterW, posterH) = posterSize(settings.posterSize)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(posterW + 28.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                        val canExpand = !siteMode || classes.isNotEmpty() || activeGroups.isNotEmpty()
                        if (!canExpand || !collapsed || event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) {
                            return@onPreviewKeyEvent false
                        }
                        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
                        val top = gridState.layoutInfo.visibleItemsInfo.minOfOrNull { it.offset.y }
                        if (focusedIndex >= 0 && (info == null || info.offset.y == top)) {
                            collapsed = false
                            expandFocus = true
                            true
                        } else {
                            false
                        }
                    },
                ) {
                    gridItemsIndexed(items, key = { _, hit -> hit.key }) { index, hit ->
                        PosterCard(
                            title = hit.title,
                            imageUrl = hit.image,
                            subtitle = hit.subtitle,
                            width = posterW,
                            height = posterH,
                            rating = hit.rating,
                            modifier = Modifier.onFocusChanged { state ->
                                if (state.isFocused) {
                                    focusedIndex = index
                                    collapsed = true
                                    if (index >= items.lastIndex && hasMore && !loading && !loadingMore && signature == debounced) {
                                        loadingMore = true
                                        page += 1
                                    }
                                }
                            },
                            onClick = {
                                if (opening) return@PosterCard
                                if (!hit.fromDouban) {
                                    onOpen(hit.key)
                                } else {
                                    scope.launch {
                                        opening = true
                                        val found = runCatching { app.catalog.search(settings, hit.title) }.getOrNull()
                                        val hitItem = found?.items?.bestTitle(hit.title)
                                        if (hitItem != null) onOpen(hitItem.key) else onSearch(hit.title)
                                        opening = false
                                    }
                                }
                            },
                        )
                    }
                }
                if (loading || opening) {
                    CircularProgressIndicator(color = palette.accent, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    restore: Boolean,
    requester: FocusRequester? = null,
    onSelect: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(
            label,
            color = palette.muted,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).then(if (restore) Modifier.focusRestorer() else Modifier),
        ) {
            rowItemsIndexed(options, key = { index, option -> "$index:${option.first}:${option.second}" }) { _, option ->
                val (value, name) = option
                val on = value == selected
                SelectChip(
                    text = name,
                    selected = on,
                    modifier = if (requester != null && on) Modifier.focusRequester(requester) else Modifier,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

private fun DoubanCard.toPoster(): PosterHit = PosterHit(
    key = "douban:$id",
    title = title,
    image = poster,
    rating = rating,
    subtitle = subtitle.ifBlank { year },
    fromDouban = true,
)

private fun MergedVod.toPoster(): PosterHit {
    val line = listOfNotNull(
        actor?.takeIf { it.isNotBlank() },
        content?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() },
        remarks?.takeIf { it.isNotBlank() },
    ).firstOrNull().orEmpty()
    return PosterHit(
        key = key,
        title = title,
        image = pic,
        rating = score.orEmpty(),
        subtitle = line,
        fromDouban = false,
    )
}

private fun List<MergedVod>.bestTitle(title: String): MergedVod? {
    val want = DoubanParse.normalize(title)
    if (want.isEmpty()) return null
    return firstOrNull { DoubanParse.normalize(it.title) == want }
        ?: firstOrNull {
            val name = DoubanParse.normalize(it.title)
            name.contains(want) || want.contains(name)
        }
}

@Composable
fun DoubanHomeRows(onOpenPage: () -> Unit, onSearch: (String) -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var movie by remember { mutableStateOf<List<DoubanCard>>(emptyList()) }
    var tv by remember { mutableStateOf<List<DoubanCard>>(emptyList()) }
    LaunchedEffect(settings.doubanEnabled, settings.doubanDataProxy, settings.doubanImageProxy) {
        if (!settings.doubanEnabled) {
            movie = emptyList()
            tv = emptyList()
            return@LaunchedEffect
        }
        movie = runCatching { app.douban.browse(settings, "movie", "热门", 0) }.getOrDefault(emptyList())
        tv = runCatching { app.douban.browse(settings, "tv", "热门", 0) }.getOrDefault(emptyList())
    }
    if (!settings.doubanEnabled) return
    Column(Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 28.dp, bottom = 8.dp)) {
            TvButton("筛选", primary = true, onClick = onOpenPage)
        }
        DoubanStrip("豆瓣电影 · 热门", movie, onSearch)
        DoubanStrip("豆瓣剧集 · 热门", tv, onSearch)
        if (movie.isEmpty() && tv.isEmpty()) {
            Text("豆瓣暂时不可用", color = palette.muted, modifier = Modifier.padding(start = 28.dp, bottom = 8.dp))
        }
    }
}

@Composable
fun DoubanHeroLine(title: String?, year: String?) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var line by remember(title, year) { mutableStateOf("") }
    LaunchedEffect(title, year, settings.doubanEnabled) {
        line = ""
        if (!settings.doubanEnabled || title.isNullOrBlank()) return@LaunchedEffect
        val detail = runCatching { app.douban.match(settings, title, year) }.getOrNull() ?: return@LaunchedEffect
        line = listOfNotNull(
            detail.rating.takeIf { it.isNotBlank() }?.let { "豆瓣 $it" },
            detail.directors.take(2).joinToString("、").takeIf { it.isNotBlank() }?.let { "导演 $it" },
            detail.actors.take(3).joinToString("、").takeIf { it.isNotBlank() },
        ).joinToString("    ")
    }
    if (line.isNotBlank()) {
        Text(line, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.86f), modifier = Modifier.padding(top = 6.dp), fontSize = 16.sp)
    }
}

@Composable
fun DoubanComments(comments: List<DoubanComment>, more: Boolean, onMore: () -> Unit) {
    val palette = LocalPalette.current
    if (comments.isEmpty() && !more) return
    Text("豆瓣短评", color = palette.text, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp))
    comments.take(12).forEach { comment ->
        val stars = comment.stars?.let { "★".repeat(it.coerceIn(1, 5)) + "  " }.orEmpty()
        Text(
            "$stars${comment.author}  ${comment.time}",
            color = palette.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp),
        )
        Text(comment.text, color = palette.text, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 8.dp))
    }
    if (more) {
        TvButton("更多短评", modifier = Modifier.padding(start = 28.dp, bottom = 8.dp), onClick = onMore)
    }
}

@Composable
private fun DoubanStrip(title: String, cards: List<DoubanCard>, onSearch: (String) -> Unit) {
    val palette = LocalPalette.current
    if (cards.isEmpty()) return
    Text(title, color = palette.text, modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 8.dp)) {
        items(cards.take(18), key = { it.id + it.title }) { card ->
            PosterCard(
                title = card.title,
                imageUrl = card.poster,
                subtitle = card.rating.takeIf { it.isNotBlank() }?.let { "豆瓣 $it" },
                width = 132.dp,
                height = 196.dp,
                onClick = { onSearch(card.title) },
            )
        }
    }
}
