package app.jianxia.tv.ui.douban

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jianxia.core.douban.DoubanCard
import app.jianxia.core.douban.DoubanCatalog
import app.jianxia.core.douban.DoubanComment
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PosterCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.posterSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DoubanScreen(onSearch: (String) -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf("movie") }
    var tag by remember { mutableStateOf("热门") }
    var start by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var cards by remember { mutableStateOf<List<DoubanCard>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    val shelf = DoubanCatalog.shelf(kind)
    LaunchedEffect(kind, tag, start, settings.doubanEnabled, settings.doubanDataProxy, settings.doubanDataProxyUrl) {
        if (!shelf.tags.contains(tag)) return@LaunchedEffect
        loading = true
        val page = runCatching { app.douban.browse(settings, kind, tag, start) }.getOrDefault(emptyList())
        cards = page
        note = when {
            !settings.doubanEnabled -> "豆瓣已关闭"
            page.isEmpty() -> "豆瓣暂时不可用，已保留界面。可以稍后再试。"
            else -> "选中一部后，会用已添加的接口搜索这个名字。"
        }
        loading = false
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
        Text("豆瓣", color = palette.text, fontSize = 28.sp)
        Text("分类来自豆瓣公开列表。选中一部后，用已添加的接口搜索，不播放豆瓣上的片源。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("电影", kind == "movie") { kind = "movie"; tag = "热门"; start = 0 }
            SelectChip("电视剧", kind == "tv") { kind = "tv"; tag = "热门"; start = 0 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp, bottom = 12.dp)) {
            shelf.tags.take(8).forEach { item ->
                SelectChip(item, item == tag) { tag = item; start = 0 }
            }
        }
        if (shelf.tags.size > 8) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                shelf.tags.drop(8).forEach { item ->
                    SelectChip(item, item == tag) { tag = item; start = 0 }
                }
            }
        }
        Text(note, color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
        if (loading) {
            CircularProgressIndicator(color = palette.accent)
        } else {
            val (w, h) = posterSize(settings.posterSize)
            cards.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    row.forEach { card ->
                        PosterCard(
                            title = card.title,
                            imageUrl = card.poster,
                            subtitle = listOfNotNull(card.rating.takeIf { it.isNotBlank() }?.let { "豆瓣 $it" }, card.year.takeIf { it.isNotBlank() }).joinToString(" · "),
                            width = w,
                            height = h,
                            onClick = { onSearch(card.title) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (start > 0) TvButton("上一页") { start = (start - 20).coerceAtLeast(0) }
                if (cards.size >= 20) TvButton("下一页") { start += 20 }
                TvButton("返回", onClick = onBack)
            }
        }
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
            TvButton("豆瓣分类", primary = true, onClick = onOpenPage)
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
