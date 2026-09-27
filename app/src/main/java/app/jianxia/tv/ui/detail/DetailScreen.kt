package app.jianxia.tv.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jianxia.core.douban.DoubanComment
import app.jianxia.core.model.MergedVod
import app.jianxia.tv.AppContainer
import app.jianxia.tv.PlayRequest
import app.jianxia.tv.ui.douban.DoubanComments
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.Poster
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.appViewModel
import app.jianxia.tv.ui.fromNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailState(
    val loading: Boolean = true,
    val item: MergedVod? = null,
    val sourceIndex: Int = 0,
    val lineIndex: Int = 0,
    val episodeIndex: Int = 0,
    val favorite: Boolean = false,
    val lineTouched: Boolean = false,
    val error: String? = null,
    val resumeMs: Long = 0,
    val historyEpisode: Int = -1,
    val doubanId: String = "",
    val doubanRating: String = "",
    val doubanPeople: String = "",
    val doubanNote: String = "",
    val comments: List<DoubanComment> = emptyList(),
    val commentsMore: Boolean = false,
    val reversed: Boolean = false,
)

class DetailViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()
    private val _speeds = MutableStateFlow<Map<String, String>>(emptyMap())
    val speeds: StateFlow<Map<String, String>> = _speeds.asStateFlow()
    private var probeJob: Job? = null

    fun open(encoded: String) {
        val key = fromNav(encoded)
        viewModelScope.launch {
            _state.value = DetailState()
            val cached = app.catalog.store.get(key) ?: app.library.findMerged(key)
            if (cached == null) {
                _state.value = DetailState(loading = false, error = "找不到这部片子，请从首页或搜索重新进入。")
                return@launch
            }
            val full = runCatching { app.catalog.hydrate(cached) }.getOrDefault(cached)
            val history = app.library.historyOf(full.key)
            val choice = app.library.lineChoice(full.key)
            val located = locate(full, choice)
            _state.value = DetailState(
                loading = false,
                item = full,
                sourceIndex = located.first,
                lineIndex = located.second,
                episodeIndex = history?.episodeIndex ?: 0,
                favorite = app.library.isFavorite(full.key),
                resumeMs = history?.positionMs ?: 0,
                historyEpisode = history?.episodeIndex ?: -1,
            )
            loadDouban(full.title, full.year)
        }
    }

    fun moreComments() {
        val id = _state.value.doubanId
        if (id.isBlank() || !_state.value.commentsMore) return
        val start = _state.value.comments.size
        viewModelScope.launch {
            val page = runCatching { app.douban.comments(app.settings.state.value, id, start) }.getOrNull() ?: return@launch
            _state.update { it.copy(comments = it.comments + page.comments, commentsMore = page.hasMore) }
        }
    }

    private fun loadDouban(title: String, year: String?) {
        viewModelScope.launch {
            val settings = app.settings.state.value
            if (!settings.doubanEnabled) return@launch
            val detail = runCatching { app.douban.match(settings, title, year) }.getOrNull()
            if (detail == null) {
                _state.update { it.copy(doubanNote = "豆瓣暂时没有匹配到这部片子") }
                return@launch
            }
            val page = runCatching { app.douban.comments(settings, detail.id, 0) }.getOrNull()
            val people = listOf(
                detail.directors.take(3).joinToString("、").let { if (it.isBlank()) "" else "导演 $it" },
                detail.actors.take(6).joinToString("、").let { if (it.isBlank()) "" else "演员 $it" },
            ).filter { it.isNotBlank() }.joinToString("    ")
            _state.update {
                it.copy(
                    doubanId = detail.id,
                    doubanRating = detail.rating,
                    doubanPeople = people,
                    doubanNote = "",
                    comments = page?.comments.orEmpty(),
                    commentsMore = page?.hasMore == true,
                )
            }
        }
    }

    fun source(index: Int) = _state.update { it.copy(sourceIndex = index, lineIndex = 0, episodeIndex = 0, lineTouched = true) }
    fun line(index: Int) = _state.update { it.copy(lineIndex = index, episodeIndex = 0, lineTouched = true) }
    fun episode(index: Int) = _state.update { it.copy(episodeIndex = index) }

    fun toggleOrder() = _state.update { it.copy(reversed = !it.reversed) }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val next = !_state.value.favorite
        viewModelScope.launch {
            app.library.setFavorite(item, next)
            _state.update { it.copy(favorite = next) }
        }
    }

    fun play(onPlay: () -> Unit) {
        val state = _state.value
        val item = state.item ?: return
        val lineId = lineId(item, state.sourceIndex, state.lineIndex)
        app.session.request = PlayRequest(
            item = item,
            episodeIndex = state.episodeIndex,
            resumeMs = if (state.episodeIndex == state.historyEpisode) state.resumeMs else 0,
            preferredLineId = if (state.lineTouched) lineId else null,
        )
        onPlay()
    }

    fun refreshSpeeds() {
        probeJob?.cancel()
        val snapshot = _state.value
        val item = snapshot.item ?: return
        val variant = item.variants.getOrNull(snapshot.sourceIndex) ?: return
        _speeds.value = emptyMap()
        probeJob = viewModelScope.launch {
            val next = linkedMapOf<String, String>()
            for (line in variant.lines.take(6)) {
                if (!isActive) return@launch
                val id = "${variant.sourceKey}::${line.name}"
                val url = line.episodes.firstOrNull()?.url.orEmpty()
                val label = if (!url.startsWith("http")) {
                    "不可用"
                } else {
                    val measure = withContext(Dispatchers.IO) { runCatching { app.http.probe(url) }.getOrNull() }
                    when {
                        measure == null || !measure.ok -> "不可用"
                        measure.resolutionHeight != null -> "${measure.connectMs + measure.firstByteMs} 毫秒 · ${measure.resolutionHeight}p"
                        else -> "${measure.connectMs + measure.firstByteMs} 毫秒"
                    }
                }
                next[id] = label
                _speeds.value = next.toMap()
            }
        }
    }

    private fun locate(item: MergedVod, lineId: String?): Pair<Int, Int> {
        if (lineId.isNullOrBlank()) return 0 to 0
        item.variants.forEachIndexed { sourceIndex, variant ->
            variant.lines.forEachIndexed { lineIndex, line ->
                if ("${variant.sourceKey}::${line.name}" == lineId) return sourceIndex to lineIndex
            }
        }
        return 0 to 0
    }
}

internal fun episodePageCount(count: Int): Int = if (count <= 0) 1 else (count + 39) / 40

internal fun episodeOrder(count: Int, reversed: Boolean): List<Int> =
    if (reversed) (count - 1 downTo 0).toList() else (0 until count).toList()

internal fun episodePageFor(count: Int, reversed: Boolean, index: Int): Int {
    val pos = episodeOrder(count, reversed).indexOf(index).coerceAtLeast(0)
    return pos / 40
}

@Composable
internal fun EpisodePager(
    episodes: List<String>,
    selected: Int,
    reversed: Boolean,
    onToggleOrder: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    var page by androidx.compose.runtime.remember(episodes.size, reversed) {
        androidx.compose.runtime.mutableIntStateOf(episodePageFor(episodes.size, reversed, selected))
    }
    val pages = episodePageCount(episodes.size)
    val order = episodeOrder(episodes.size, reversed)
    val window = order.drop(page * 40).take(40)
    Text("选集", color = palette.text, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        SelectChip(if (reversed) "倒序" else "正序", true, onClick = onToggleOrder)
        if (pages > 1) {
            repeat(pages) { index ->
                val start = order.getOrNull(index * 40)?.plus(1) ?: return@repeat
                val end = order.getOrNull(minOf(order.lastIndex, index * 40 + 39))?.plus(1) ?: start
                val label = if (start <= end) "$start-$end" else "$end-$start"
                SelectChip(label, index == page) { page = index }
            }
        }
    }
    window.chunked(8).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            row.forEach { index ->
                SelectChip(episodes.getOrNull(index).orEmpty().ifBlank { "${index + 1}" }, index == selected) {
                    onSelect(index)
                }
            }
        }
    }
}

private fun lineId(item: MergedVod, sourceIndex: Int, lineIndex: Int): String? {
    val variant = item.variants.getOrNull(sourceIndex) ?: return null
    val line = variant.lines.getOrNull(lineIndex) ?: return null
    return "${variant.sourceKey}::${line.name}"
}

@Composable
fun DetailScreen(encodedKey: String, onPlay: () -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val settings by LocalApp.current.settings.state.collectAsStateWithLifecycle()
    val vm: DetailViewModel = appViewModel { DetailViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(encodedKey) { vm.open(encodedKey) }
    if (settings.homeLayout == "cinema") {
        CinemaDetail(vm, onPlay, onBack)
        return
    }
    BackHandler(onBack = onBack)
    val item = state.item
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
        when {
            state.loading -> CircularProgressIndicator(color = palette.accent)
            item == null -> Text(state.error ?: "无法打开详情", color = palette.text, fontSize = 22.sp)
            else -> {
                val variant = item.variants.getOrNull(state.sourceIndex) ?: item.variants.firstOrNull()
                val line = variant?.lines?.getOrNull(state.lineIndex)
                Row {
                    Poster(item.pic, item.title, Modifier.width(220.dp).height(308.dp))
                    Column(Modifier.padding(start = 28.dp).weight(1f)) {
                        Text(item.title, color = palette.text, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(item.year, item.area, item.typeName, item.remarks).joinToString("  ·  "),
                            color = palette.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (!item.actor.isNullOrBlank()) Text("演员  ${item.actor}", color = palette.text, modifier = Modifier.padding(top = 10.dp))
                        if (!item.director.isNullOrBlank()) Text("导演  ${item.director}", color = palette.text, modifier = Modifier.padding(top = 4.dp))
                        if (state.doubanRating.isNotBlank() || state.doubanPeople.isNotBlank()) {
                            Text(
                                listOfNotNull(
                                    state.doubanRating.takeIf { it.isNotBlank() }?.let { "豆瓣 $it" },
                                    state.doubanPeople.takeIf { it.isNotBlank() },
                                ).joinToString("    "),
                                color = palette.text,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        } else if (state.doubanNote.isNotBlank()) {
                            Text(state.doubanNote, color = palette.muted, modifier = Modifier.padding(top = 10.dp))
                        }
                        if (!item.content.isNullOrBlank()) {
                            Text(item.content.orEmpty(), color = palette.muted, modifier = Modifier.padding(top = 12.dp), lineHeight = 22.sp)
                        }
                        Text(
                            "共 ${item.variants.size} 个来源，${item.variants.sumOf { it.lines.size }} 条线路",
                            color = palette.accent,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
                            TvButton(if (state.resumeMs > 10_000) "继续播放" else "播放", primary = true) { vm.play(onPlay) }
                            TvButton(if (state.favorite) "已收藏" else "收藏", onClick = vm::toggleFavorite)
                        }
                    }
                }
                if (item.variants.size > 1) {
                    Text("来源", color = palette.text, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.variants.forEachIndexed { index, source ->
                            SelectChip(source.sourceName, index == state.sourceIndex) { vm.source(index) }
                        }
                    }
                }
                Text("线路", color = palette.text, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                if (variant == null || variant.lines.isEmpty()) {
                    Text("这个来源没有可播放的线路。", color = palette.muted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        variant.lines.forEachIndexed { index, playLine ->
                            SelectChip("${playLine.name}  ${playLine.episodes.size}", index == state.lineIndex) { vm.line(index) }
                        }
                    }
                    EpisodePager(
                        episodes = line?.episodes.orEmpty().map { it.name },
                        selected = state.episodeIndex,
                        reversed = state.reversed,
                        onToggleOrder = vm::toggleOrder,
                        onSelect = vm::episode,
                    )
                }
                DoubanComments(state.comments, state.commentsMore, vm::moreComments)
            }
        }
    }
}
