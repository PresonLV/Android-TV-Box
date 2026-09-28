package app.jianxia.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import app.jianxia.core.douban.DoubanCard
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.FilterGroup
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodClass
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.spider.LiveSites
import app.jianxia.tv.data.repo.SiteBrowse
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.Poster
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private const val HOME_TAB = "home"

private data class ShelfCard(
    val key: String,
    val title: String,
    val image: String?,
    val badge: String?,
    val corner: String?,
    val score: String?,
    val search: Boolean,
)

@Composable
internal fun WarehouseHome(
    onOpen: (String) -> Unit,
    onSearchTitle: (String) -> Unit,
    onHistory: () -> Unit,
    onLive: () -> Unit,
    onSearchPage: () -> Unit,
    onPush: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    onSites: () -> Unit,
) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val sources by app.sources.observe().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<VodSiteDef>>(emptyList()) }
    var picker by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(HOME_TAB) }
    var classes by remember { mutableStateOf<List<VodClass>>(emptyList()) }
    var filters by remember { mutableStateOf<Map<String, List<FilterGroup>>>(emptyMap()) }
    var chosen by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(1) }
    var cards by remember { mutableStateOf<List<ShelfCard>>(emptyList()) }
    var strips by remember { mutableStateOf<List<Pair<String, List<ShelfCard>>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<ShelfCard?>(null) }
    var now by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clockText()
            delay(30_000)
        }
    }
    val fingerprint = sources.joinToString { "${it.id}:${it.enabled}:${it.url}" }
    val site = sites.firstOrNull { it.key == settings.defaultSourceId } ?: sites.firstOrNull()

    LaunchedEffect(fingerprint, settings.defaultSourceId) {
        try {
            val expanded = app.catalog.expand()
            sites = expanded.sites.filter { def ->
                def.kind != SiteKind.UNSUPPORTED && def.unsupportedReason == null &&
                    !LiveSites.matches(def.name, def.key, def.api)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message = error.message ?: "配置没有加载出来"
        }
    }

    LaunchedEffect(site?.key, tab, chosen, page, settings.homeRecommend, settings.doubanEnabled, settings.homeMultiRow, loadAttempt) {
        val current = site
        if (current == null) {
            loading = false
            cards = emptyList()
            message = null
            return@LaunchedEffect
        }
        loading = cards.isEmpty()
        message = null
        if (tab == HOME_TAB && settings.homeRecommend != "site" && settings.doubanEnabled) {
            val movies = try {
                app.douban.browse(settings, "movie", "热门", 0)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            val shows = try {
                app.douban.browse(settings, "tv", "热门", 0)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            cards = (movies + shows).map { it.toShelf() }
            val home = runCatching { app.catalog.browseSite(current.key, 1, null, emptyMap()) }.getOrNull()
            if (home != null) {
                if (home.classes.isNotEmpty()) classes = home.classes
                if (home.filters.isNotEmpty()) filters = home.filters
            }
            strips = if (settings.homeMultiRow) extraStrips(app, settings, current, classes) else emptyList()
            if (cards.isEmpty()) message = home?.message ?: "豆瓣热播暂时没有内容"
            loading = false
            if (message?.contains("还在加载") == true && loadAttempt < 12) {
                delay(5_000)
                loadAttempt += 1
            }
            return@LaunchedEffect
        }
        val typeId = if (tab == HOME_TAB) null else tab
        val browse = try {
            app.catalog.browseSite(current.key, page, typeId, if (tab == HOME_TAB) emptyMap() else chosen)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SiteBrowse(name = current.name, message = error.message ?: "这个分类没有内容")
        }
        if (browse.classes.isNotEmpty()) classes = browse.classes
        if (browse.filters.isNotEmpty()) filters = browse.filters
        val next = browse.items.map { it.toShelf() }
        cards = if (page <= 1) next else cards + next
        pageCount = browse.pageCount.coerceAtLeast(1)
        strips = if (settings.homeMultiRow && tab == HOME_TAB) extraStrips(app, settings, current, browse.classes.ifEmpty { classes }) else emptyList()
        message = when {
            cards.isNotEmpty() -> browse.message?.takeIf { page <= 1 && browse.items.isEmpty() }
            else -> browse.message ?: "没有找到片源"
        }
        loading = false
        if (message?.contains("还在加载") == true && loadAttempt < 12) {
            delay(5_000)
            loadAttempt += 1
        }
    }

    if (picker) {
        BackHandler { picker = false }
        SitePicker(sites, site?.key, onClose = { picker = false }) { picked ->
            picker = false
            tab = HOME_TAB
            page = 1
            chosen = emptyMap()
            cards = emptyList()
            scope.launch { app.settings.update { it.copy(defaultSourceId = picked.key) } }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && tab != HOME_TAB) {
                    showFilters = !showFilters
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton(site?.name ?: "选择首页站源", primary = true) { picker = true }
            Text(now, color = palette.muted, fontSize = 16.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val homeSelected = tab == HOME_TAB
            SelectChip(if (homeSelected) "主页" else "主页", homeSelected) {
                if (tab != HOME_TAB) {
                    tab = HOME_TAB
                    page = 1
                    cards = emptyList()
                    showFilters = false
                }
            }
            classes.take(12).forEach { item ->
                val selected = tab == item.id
                SelectChip(if (selected) "${item.name}  ▽" else item.name, selected) {
                    if (tab == item.id) {
                        showFilters = !showFilters
                    } else {
                        tab = item.id
                        page = 1
                        chosen = emptyMap()
                        cards = emptyList()
                        showFilters = false
                    }
                }
            }
        }
        Row(
            Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvButton("历史", onClick = onHistory)
            TvButton("直播", onClick = onLive)
            TvButton("搜索", onClick = onSearchPage)
            TvButton("推送", onClick = onPush)
            TvButton("收藏", onClick = onFavorites)
            TvButton("设置", onClick = onSettings)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                sources.none { it.enabled } -> Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("还没有接口", color = palette.text, fontSize = 28.sp)
                        Text("个人影院不内置片源。用手机扫右侧二维码添加配置，或到设置里输入地址。", color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                        TvButton("打开设置", primary = true, onClick = onSettings)
                    }
                    PhoneQrCard()
                }
                loading && cards.isEmpty() -> CircularProgressIndicator(color = palette.accent, modifier = Modifier.align(Alignment.Center))
                else -> Column(Modifier.fillMaxSize()) {
                    message?.let { note ->
                        Row(
                            Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                note,
                                color = palette.muted,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f).semantics { contentDescription = note },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TvButton("站点状态", onClick = onSites)
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = if (showFilters) 180.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (settings.homeMultiRow && strips.isNotEmpty() && tab == HOME_TAB) {
                            strips.forEach { (title, row) ->
                                item(span = { GridItemSpan(5) }) {
                                    Text(title, color = palette.text, fontSize = 16.sp)
                                }
                                items(row.take(5), key = { "strip-$title-${it.key}" }) { card ->
                                    ShelfPoster(
                                        card = card,
                                        modifier = Modifier.fillMaxWidth(),
                                        preview = settings.windowPreview,
                                        onFocus = { focused = it },
                                        onOpen = onOpen,
                                        onSearchTitle = onSearchTitle,
                                    )
                                }
                            }
                        }
                        items(cards, key = { it.key + it.title }) { card ->
                            ShelfPoster(
                                card = card,
                                modifier = Modifier.fillMaxWidth(),
                                preview = settings.windowPreview,
                                onFocus = { focused = it },
                                onOpen = onOpen,
                                onSearchTitle = onSearchTitle,
                            )
                        }
                        if (page < pageCount && tab != HOME_TAB) {
                            item(span = { GridItemSpan(5) }) {
                                TvButton("下一页", primary = true) {
                                    page += 1
                                }
                            }
                        }
                    }
                }
            }
            if (showFilters && tab != HOME_TAB) {
                FilterSheet(
                    groups = filters[tab].orEmpty(),
                    chosen = chosen,
                    onPick = { key, value ->
                        chosen = if (value.isBlank()) chosen - key else chosen + (key to value)
                        page = 1
                        cards = emptyList()
                    },
                    onClose = { showFilters = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (settings.windowPreview && focused != null) {
                Text(
                    listOfNotNull(focused?.badge, focused?.title, focused?.corner).joinToString("  ·  "),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ShelfPoster(
    card: ShelfCard,
    modifier: Modifier,
    preview: Boolean,
    onFocus: (ShelfCard) -> Unit,
    onOpen: (String) -> Unit,
    onSearchTitle: (String) -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = { if (card.search) onSearchTitle(card.title) else onOpen(card.key) },
        modifier = modifier.onFocusChanged { if (it.isFocused && preview) onFocus(card) },
        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface.copy(alpha = 0.35f),
            focusedContainerColor = palette.surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(3.dp, palette.accent),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            Poster(card.image, card.title, Modifier.fillMaxSize(), fade = false)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.82f))),
                ),
            )
            card.badge?.let { badge ->
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            card.corner?.let { mark ->
                Text(
                    mark,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(palette.accent.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            card.score?.let { score ->
                Text(score, color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 28.dp))
            }
            Text(
                card.title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FilterSheet(
    groups: List<FilterGroup>,
    chosen: Map<String, String>,
    onPick: (String, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    BackHandler(onBack = onClose)
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (groups.isEmpty()) {
            Text("这个分类没有筛选", color = palette.muted)
        }
        groups.forEach { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text(group.name, color = palette.muted, modifier = Modifier.padding(end = 8.dp, top = 8.dp))
                SelectChip("全部", chosen[group.key].isNullOrBlank()) { onPick(group.key, "") }
                group.choices.take(16).forEach { choice ->
                    SelectChip(choice.name, chosen[group.key] == choice.value) { onPick(group.key, choice.value) }
                }
            }
        }
    }
}

@Composable
private fun SitePicker(sites: List<VodSiteDef>, current: String?, onClose: () -> Unit, onPick: (VodSiteDef) -> Unit) {
    val palette = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(sites.firstOrNull()?.key) {
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(palette.bg).padding(28.dp)) {
        Text("首页站源", color = palette.text, fontSize = 26.sp)
        Text("按确认键切换。焦点移动不会离开当前页。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(sites.size) { index ->
                val item = sites[index]
                TvButton(
                    item.name,
                    primary = item.key == current,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                ) { onPick(item) }
            }
        }
        TvButton("返回", modifier = Modifier.padding(top = 12.dp), onClick = onClose)
    }
}

private fun DoubanCard.toShelf() = ShelfCard(
    key = "douban:$id",
    title = title,
    image = poster,
    badge = "豆瓣热播",
    corner = year.takeIf { it.isNotBlank() },
    score = rating.takeIf { it.isNotBlank() },
    search = true,
)

private fun MergedVod.toShelf() = ShelfCard(
    key = key,
    title = title,
    image = pic,
    badge = year?.takeIf { it.isNotBlank() } ?: remarks?.takeIf { it.isNotBlank() },
    corner = if (!year.isNullOrBlank()) remarks?.takeIf { it.isNotBlank() } else null,
    score = score?.takeIf { it.isNotBlank() },
    search = false,
)

private suspend fun extraStrips(
    app: app.jianxia.tv.AppContainer,
    settings: AppSettings,
    site: VodSiteDef,
    classes: List<VodClass>,
): List<Pair<String, List<ShelfCard>>> {
    if (!settings.homeMultiRow) return emptyList()
    return classes.take(2).mapNotNull { item ->
        val browse = runCatching { app.catalog.browseSite(site.key, 1, item.id, emptyMap()) }.getOrNull() ?: return@mapNotNull null
        val row = browse.items.take(8).map { it.toShelf() }
        if (row.isEmpty()) null else item.name to row
    }
}

private fun clockText(): String {
    val now = Calendar.getInstance()
    val week = listOf("日", "一", "二", "三", "四", "五", "六")[now.get(Calendar.DAY_OF_WEEK) - 1]
    return "%d/%02d/%02d 周%s  %02d:%02d".format(
        now.get(Calendar.YEAR),
        now.get(Calendar.MONTH) + 1,
        now.get(Calendar.DAY_OF_MONTH),
        week,
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
    )
}
