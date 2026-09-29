package app.jianxia.tv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.core.model.MergedVod
import app.jianxia.tv.AppContainer
import app.jianxia.tv.ui.Keycap
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PosterCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.appViewModel
import app.jianxia.tv.ui.posterSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val buffer: String = "",
    val pinyin: Boolean = true,
    val loading: Boolean = false,
    val results: List<MergedVod> = emptyList(),
    val message: String? = null,
)

class SearchViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun typeLetter(letter: String) {
        _state.update { state ->
            if (state.pinyin) state.copy(buffer = state.buffer + letter.lowercase())
            else state.copy(query = state.query + letter)
        }
        if (!_state.value.pinyin) schedule(_state.value.query)
    }

    fun typeQuery(text: String) {
        _state.update { it.copy(query = it.query + text) }
        schedule(_state.value.query)
    }

    fun commit(char: String) {
        _state.update { it.copy(query = it.query + char, buffer = "") }
        schedule(_state.value.query)
    }

    fun commitBuffer() {
        val buffer = _state.value.buffer
        if (buffer.isEmpty()) return
        _state.update { it.copy(query = it.query + buffer, buffer = "") }
        schedule(_state.value.query)
    }

    fun delete() {
        val state = _state.value
        if (state.buffer.isNotEmpty()) {
            _state.update { it.copy(buffer = it.buffer.dropLast(1)) }
        } else if (state.query.isNotEmpty()) {
            _state.update { it.copy(query = it.query.dropLast(1)) }
            schedule(_state.value.query)
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = SearchState(pinyin = _state.value.pinyin)
    }

    fun toggleMode() {
        _state.update { it.copy(pinyin = !it.pinyin, buffer = "") }
    }

    fun submit(query: String) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return
        _state.update { it.copy(query = cleaned, buffer = "") }
        schedule(cleaned, immediate = true)
    }

    fun searchNow() {
        commitBuffer()
        schedule(_state.value.query, immediate = true)
    }

    fun useRecent(value: String) {
        _state.update { it.copy(query = value, buffer = "") }
        schedule(value, immediate = true)
    }

    private fun schedule(query: String, immediate: Boolean = false) {
        job?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), loading = false, message = null) }
            return
        }
        job = viewModelScope.launch {
            if (!immediate) delay(350)
            _state.update { it.copy(loading = true) }
            val result = runCatching { app.catalog.search(app.settings.state.value, query.trim()) }
            app.backup.rememberSearch(query.trim())
            _state.update {
                it.copy(
                    loading = false,
                    results = result.getOrNull()?.items.orEmpty(),
                    message = result.exceptionOrNull()?.message ?: result.getOrNull()?.message,
                )
            }
        }
    }
}

@Composable
fun SearchScreen(onOpen: (String) -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val vm: SearchViewModel = appViewModel { SearchViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val tick by app.session.tick.collectAsStateWithLifecycle()
    LaunchedEffect(tick) {
        val pending = app.session.pendingSearch
        if (!pending.isNullOrBlank()) {
            app.session.pendingSearch = null
            vm.submit(pending)
        }
    }
    val (posterW, posterH) = posterSize(settings.posterSize)
    val candidates = if (state.pinyin) app.pinyin.candidates(state.buffer) else emptyList()
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.width(420.dp).verticalScroll(rememberScrollState())) {
            Text(state.query.ifBlank { "搜索片名" }, color = if (state.query.isBlank()) palette.muted else palette.text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text(
                if (state.buffer.isBlank()) "拼音或字母" else state.buffer,
                color = palette.accent,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (candidates.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    candidates.forEach { char -> SelectChip(char, false) { vm.commit(char) } }
                }
            }
            listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { letter -> Keycap(letter.toString()) { vm.typeLetter(letter.toString()) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                "1234567890".forEach { digit -> Keycap(digit.toString()) { vm.typeQuery(digit.toString()) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                listOf(".", ":", "/", "-", "_").forEach { symbol -> Keycap(symbol) { vm.typeQuery(symbol) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(if (state.pinyin) "拼音" else "ABC", onClick = vm::toggleMode)
                TvButton("删除", onClick = vm::delete)
                TvButton("清空", onClick = vm::clear)
                TvButton("搜索", primary = true, onClick = vm::searchNow)
            }
            if (settings.recentSearches.isNotEmpty()) {
                Text("最近搜索", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.recentSearches.take(6).forEach { recent ->
                        SelectChip(recent, false) { vm.useRecent(recent) }
                    }
                }
            }
        }
        Column(Modifier.padding(start = 24.dp).weight(1f).fillMaxHeight()) {
            when {
                state.loading -> CircularProgressIndicator(color = palette.accent)
                state.query.isBlank() -> Text("用遥控器输入片名。拼音会给出常用字，也可以切换成 ABC。", color = palette.muted, fontSize = 16.sp)
                state.results.isEmpty() -> Column {
                    Text("没有找到片源", color = palette.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    Text(state.message ?: "这些来源里没有这部片子。", color = palette.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                }
                settings.searchStyle == "list" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(state.message.orEmpty(), color = palette.muted, modifier = Modifier.padding(bottom = 10.dp))
                    state.results.forEach { item ->
                        TvButton(listOfNotNull(item.title, item.year, item.remarks).joinToString("  ·  ")) { onOpen(item.key) }
                    }
                }
                else -> {
                    Text(state.message.orEmpty(), color = palette.muted, modifier = Modifier.padding(bottom = 10.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(posterW + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.results, key = { it.key }) { item ->
                            PosterCard(
                                title = item.title,
                                imageUrl = item.pic,
                                subtitle = listOfNotNull(item.year, item.remarks).joinToString(" · "),
                                width = posterW,
                                height = posterH,
                                onClick = { onOpen(item.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}
