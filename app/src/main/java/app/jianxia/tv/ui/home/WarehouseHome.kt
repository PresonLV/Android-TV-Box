@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.focus.focusProperties
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import app.jianxia.core.douban.DoubanCard
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.FilterGroup
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.UiDiy
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
import app.jianxia.tv.ui.cornerShape
import app.jianxia.tv.ui.focusBorder
import app.jianxia.tv.ui.focusGlow
import app.jianxia.tv.ui.focusScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private const val HOME_TAB = "home"

private class WarehouseHold : ViewModel() {
    val tab = mutableStateOf(HOME_TAB)
    val classes = mutableStateOf<List<VodClass>>(emptyList())
    val filters = mutableStateOf<Map<String, List<FilterGroup>>>(emptyMap())
    val chosen = mutableStateOf<Map<String, String>>(emptyMap())
    val page = mutableIntStateOf(1)
    val pageCount = mutableIntStateOf(1)
    val cards = mutableStateOf<List<ShelfCard>>(emptyList())
    val strips = mutableStateOf<List<Pair<String, List<ShelfCard>>>>(emptyList())
    val message = mutableStateOf<String?>(null)
    val loadedKey = mutableStateOf("")
}

private data class ShelfCard(
    val key: String,
    val title: String,
    val image: String?,
    val hot: String?,
    val year: String?,
    val quality: String?,
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
    val hold = viewModel { WarehouseHold() }
    var tab by hold.tab
    var classes by hold.classes
    var filters by hold.filters
    var chosen by hold.chosen
    var page by hold.page
    var pageCount by hold.pageCount
    var cards by hold.cards
    var strips by hold.strips
    var message by hold.message
    var loadedKey by hold.loadedKey
    var sites by remember { mutableStateOf<List<VodSiteDef>>(emptyList()) }
    var picker by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(cards.isEmpty()) }
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
    LaunchedEffect(classes) {
        if (classes.isEmpty()) return@LaunchedEffect
        val incoming = classes.map { it.id to it.name }
        val merged = UiDiy.mergeTabs(app.settings.state.value.homeTabs, incoming)
        if (merged.map { it.id } != app.settings.state.value.homeTabs.map { it.id }) {
            app.settings.update { it.copy(homeTabs = UiDiy.mergeTabs(it.homeTabs, incoming)) }
        }
    }
    val columns = settings.posterColumns
    val tabs = UiDiy.visibleTabs(settings.homeTabs, classes.map { it.id to it.name })
    val actions = UiDiy.actionsOf(settings).filter { it.visible }
    LaunchedEffect(tabs.map { it.id }.joinToString(), tab) {
        val ids = tabs.map { if (it.id == "home") HOME_TAB else it.id }
        if (tab !in ids) {
            tab = ids.firstOrNull() ?: HOME_TAB
            page = 1
            cards = emptyList()
            showFilters = false
        }
    }

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
        val stamp = "${current.key}|$tab|$page|$chosen|${settings.homeRecommend}|${settings.doubanEnabled}"
        val sameScreen = loadedKey == stamp && cards.isNotEmpty()
        if (!sameScreen) {
            val typeForCache = if (tab == HOME_TAB) null else tab
            val peeked = app.catalog.peekBrowse(current.key, page, typeForCache, if (tab == HOME_TAB) emptyMap() else chosen)
            if (peeked != null && (peeked.items.isNotEmpty() || peeked.classes.isNotEmpty())) {
                if (peeked.classes.isNotEmpty()) classes = peeked.classes
                if (peeked.filters.isNotEmpty()) filters = peeked.filters
                cards = peeked.items.map { it.toShelf() }
                pageCount = peeked.pageCount.coerceAtLeast(1)
                loading = false
            } else if (page <= 1) {
                cards = emptyList()
                loading = true
            } else {
                loading = cards.isEmpty()
            }
        } else {
            loading = false
        }
        message = null
        val started = android.os.SystemClock.elapsedRealtime()
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
            val doubanCards = (movies + shows).map { it.toShelf() }
            val home = runCatching { app.catalog.browseSite(current.key, 1, null, emptyMap()) }.getOrNull()
            if (home != null) {
                if (home.classes.isNotEmpty()) classes = home.classes
                if (home.filters.isNotEmpty()) filters = home.filters
            }
            cards = when {
                doubanCards.isNotEmpty() -> doubanCards
                home != null && home.items.isNotEmpty() -> home.items.map { it.toShelf() }
                cards.isNotEmpty() -> cards
                else -> emptyList()
            }
            strips = if (settings.homeMultiRow) extraStrips(app, settings, current, classes) else emptyList()
            if (cards.isEmpty()) message = home?.message ?: "豆瓣热播暂时没有内容"
            loading = false
            loadedKey = stamp
            android.util.Log.i("JianXia", "browse ${current.key} home ${android.os.SystemClock.elapsedRealtime() - started}ms cached=$sameScreen")
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
        if (next.isNotEmpty()) {
            cards = if (page <= 1) next else cards + next
        }
        if (browse.pageCount > 1 || next.isNotEmpty()) pageCount = browse.pageCount.coerceAtLeast(1)
        strips = if (settings.homeMultiRow && tab == HOME_TAB) extraStrips(app, settings, current, browse.classes.ifEmpty { classes }) else strips
        message = when {
            cards.isNotEmpty() && next.isEmpty() -> browse.message
            cards.isNotEmpty() -> null
            else -> browse.message ?: "没有找到片源"
        }
        loading = false
        loadedKey = stamp
        android.util.Log.i("JianXia", "browse ${current.key} $tab ${android.os.SystemClock.elapsedRealtime() - started}ms cached=$sameScreen")
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
            loadedKey = ""
            scope.launch { app.settings.update { it.copy(defaultSourceId = picked.key) } }
        }
        return
    }

    val leftRail = settings.homeRail != "top"
    val configuration = LocalConfiguration.current
    val safeH = (configuration.screenWidthDp * 0.05f).coerceIn(32f, 96f).dp
    val safeV = (configuration.screenHeightDp * 0.05f).coerceIn(24f, 64f).dp
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var contentAttached by remember { mutableStateOf(false) }
    var railAttached by remember { mutableStateOf(false) }
    LaunchedEffect(actions.size) {
        contentAttached = true
        railAttached = actions.isNotEmpty()
    }
    LaunchedEffect(focused?.key) {
        val card = focused ?: return@LaunchedEffect
        if (card.search) return@LaunchedEffect
        delay(280)
        val item = app.catalog.store.get(card.key) ?: return@LaunchedEffect
        if (item.variants.any { it.lines.isEmpty() && it.id.isNotBlank() }) {
            runCatching { app.catalog.hydrate(item) }
        }
    }
    Row(Modifier.fillMaxSize().padding(start = safeH, top = safeV, end = safeH, bottom = safeV)) {
    if (leftRail) {
        Column(
            Modifier
                .padding(end = 16.dp)
                .focusProperties {
                    exit = {
                        if (it == androidx.compose.ui.focus.FocusDirection.Right && contentAttached) {
                            contentFocus
                        } else {
                            androidx.compose.ui.focus.FocusRequester.Cancel
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEachIndexed { index, action ->
                RailButton(
                    action.title,
                    railIcon(action.id),
                    modifier = if (index == 0 && railAttached) Modifier.focusRequester(railFocus) else Modifier,
                ) { runAction(action.id, onHistory, onLive, onSearchPage, onPush, onFavorites, onSettings) }
            }
        }
    }
    Column(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .then(if (contentAttached) Modifier.focusRequester(contentFocus) else Modifier)
            .focusProperties {
                exit = {
                    if (leftRail && railAttached && it == androidx.compose.ui.focus.FocusDirection.Left) railFocus
                    else androidx.compose.ui.focus.FocusRequester.Default
                }
            }
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
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvButton(site?.name ?: "选择首页站源", primary = true) { picker = true }
            if (settings.showClock) Text(now, color = palette.muted, fontSize = 16.sp)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { item ->
                val selected = if (item.id == "home") tab == HOME_TAB else tab == item.id
                val label = if (selected && item.id != "home") "${item.title}  ▽" else item.title
                SelectChip(label, selected) {
                    if (item.id == "home") {
                        if (tab != HOME_TAB) {
                            tab = HOME_TAB
                            page = 1
                            cards = emptyList()
                            showFilters = false
                        }
                    } else if (tab == item.id) {
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
        if (!leftRail) {
            Row(
                Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { action ->
                    TvButton(action.title) {
                        runAction(action.id, onHistory, onLive, onSearchPage, onPush, onFavorites, onSettings)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                sources.none { it.enabled } -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(bottom = if (showFilters) 180.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (settings.homeMultiRow && strips.isNotEmpty() && tab == HOME_TAB) {
                            strips.forEach { (title, row) ->
                                item(span = { GridItemSpan(columns) }) {
                                    Text(title, color = palette.text, fontSize = 16.sp)
                                }
                                items(row.take(columns), key = { "strip-$title-${it.key}" }) { card ->
                                    ShelfPoster(
                                        card = card,
                                        settings = settings,
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
                                settings = settings,
                                modifier = Modifier.fillMaxWidth(),
                                preview = settings.windowPreview,
                                onFocus = { focused = it },
                                onOpen = onOpen,
                                onSearchTitle = onSearchTitle,
                            )
                        }
                        if (page < pageCount && tab != HOME_TAB) {
                            item(span = { GridItemSpan(columns) }) {
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
                    listOfNotNull(focused?.hot, focused?.year, focused?.quality, focused?.title).joinToString("  ·  "),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 8.dp),
                )
            }
        }
    }
    }
}

private fun runAction(
    id: String,
    onHistory: () -> Unit,
    onLive: () -> Unit,
    onSearchPage: () -> Unit,
    onPush: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
) {
    when (id) {
        "history" -> onHistory()
        "live" -> onLive()
        "search" -> onSearchPage()
        "push" -> onPush()
        "favorite" -> onFavorites()
        "settings" -> onSettings()
    }
}

@Composable
private fun ShelfPoster(
    card: ShelfCard,
    settings: AppSettings,
    modifier: Modifier,
    preview: Boolean,
    onFocus: (ShelfCard) -> Unit,
    onOpen: (String) -> Unit,
    onSearchTitle: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val shape = cornerShape()
    val ratio = when (settings.posterSize) {
        "small" -> 0.82f
        "large" -> 0.62f
        else -> 0.72f
    }
    Surface(
        onClick = { if (card.search) onSearchTitle(card.title) else onOpen(card.key) },
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus(card) },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface.copy(alpha = settings.tileAlpha.coerceIn(30, 100) / 100f),
            focusedContainerColor = palette.surface2,
        ),
        border = focusBorder(shape),
        scale = focusScale(),
        glow = focusGlow(),
    ) {
        val posterPx = when (settings.posterColumns) {
            6 -> 180
            4 -> 280
            else -> 220
        }
        Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
            Poster(card.image, card.title, Modifier.fillMaxSize(), maxWidthPx = posterPx, maxHeightPx = (posterPx * 1.45f).toInt(), fade = false)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.82f))),
                ),
            )
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.showDoubanBadge) card.hot?.let { Badge(it, Color.Black.copy(alpha = 0.62f)) }
                if (settings.showYear) card.year?.let { Badge(it, Color.Black.copy(alpha = 0.62f)) }
            }
            if (settings.showQuality) {
                card.quality?.let { mark ->
                    Text(
                        mark,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(palette.accent.copy(alpha = 0.92f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (settings.showRating) {
                card.score?.let { score ->
                    Text(score, color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 28.dp))
                }
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
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RailButton(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = cornerShape()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface.copy(alpha = 0.72f),
            contentColor = palette.text,
            focusedContainerColor = palette.accent,
            focusedContentColor = palette.onAccent,
        ),
        scale = focusScale(),
        glow = focusGlow(),
        border = focusBorder(shape),
    ) {
        Row(
            Modifier.width(168.dp).height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, title, modifier = Modifier.size(20.dp))
            Text(title, modifier = Modifier.padding(start = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun railIcon(id: String): ImageVector = when (id) {
    "history" -> Icons.Filled.List
    "live" -> Icons.Filled.PlayArrow
    "search" -> Icons.Filled.Search
    "push" -> Icons.Filled.Send
    "favorite" -> Icons.Filled.Favorite
    else -> Icons.Filled.Settings
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
        if (sites.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(48)
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
    hot = "豆瓣热播",
    year = year.takeIf { it.isNotBlank() },
    quality = null,
    score = rating.takeIf { it.isNotBlank() },
    search = true,
)

private fun MergedVod.toShelf() = ShelfCard(
    key = key,
    title = title,
    image = pic,
    hot = null,
    year = year?.takeIf { it.isNotBlank() },
    quality = remarks?.takeIf { it.isNotBlank() },
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
