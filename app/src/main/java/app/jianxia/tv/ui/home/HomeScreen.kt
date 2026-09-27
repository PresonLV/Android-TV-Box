package app.jianxia.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import app.jianxia.core.UserFacingError
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.displayTitle
import app.jianxia.tv.AppContainer
import app.jianxia.tv.PlayRequest
import app.jianxia.tv.data.repo.HomeCatalog
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.PosterCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SectionTitle
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.posterSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val catalog: HomeCatalog? = null,
    val error: String? = null,
)

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(catalog = app.catalog.lastHome, loading = app.catalog.lastHome == null))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.catalog == null, error = null) }
            runCatching { app.catalog.home(app.settings.state.value) }
                .onSuccess { catalog -> _state.value = HomeState(loading = false, catalog = catalog) }
                .onFailure { error -> _state.update { it.copy(loading = false, error = UserFacingError.message(error)) } }
        }
    }
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onPlay: () -> Unit, onSettings: () -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val sources by app.sources.observe().collectAsStateWithLifecycle(emptyList())
    val history by app.library.history().collectAsStateWithLifecycle(emptyList())
    val favorites by app.library.favorites().collectAsStateWithLifecycle(emptyList())
    val vm: HomeViewModel = viewModel(factory = factory { HomeViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val fingerprint = sources.joinToString { "${it.id}:${it.enabled}:${it.url}:${it.kind}:${it.note}" }
    LaunchedEffect(fingerprint, settings.defaultSourceId, settings.searchTimeoutSec) { vm.load() }
    if (settings.homeLayout == "cinema") {
        CinemaHome(
            app = app,
            settings = settings,
            sourcesEmpty = sources.none { it.enabled },
            history = history,
            favorites = favorites,
            catalog = state.catalog,
            loading = state.loading,
            error = state.error,
            busy = busy,
            onBusy = { busy = it },
            onOpen = onOpen,
            onPlay = onPlay,
            onSettings = onSettings,
            onRetry = { vm.load() },
        )
        return
    }
    val (posterW, posterH) = posterSize(settings.posterSize)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
        Text("简匣", color = palette.text, fontSize = 28.sp)
        val note = state.catalog?.message
        if (!note.isNullOrBlank()) {
            Text(note, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        when {
            sources.none { it.enabled } -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("还没有接口", color = palette.text, fontSize = 32.sp)
                    Text(
                        "简匣不内置任何片源。推荐用手机扫描右侧二维码添加，这是最省事的办法。也可以用电视上的屏幕键盘输入网址。",
                        color = palette.muted,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 22.dp),
                    )
                    TvButton("在电视上添加", primary = true, onClick = onSettings)
                }
                PhoneQrCard()
            }
            state.loading && state.catalog == null -> CircularProgressIndicator(color = palette.accent, modifier = Modifier.padding(top = 32.dp))
            else -> {
                settings.homeRows.filter { it.visible }.forEach { row ->
                    when (row.id) {
                        "history" -> if (history.isNotEmpty()) {
                            SectionTitle(row.displayTitle(), Modifier.padding(top = 8.dp, bottom = 8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 24.dp, bottom = 8.dp)) {
                                items(history, key = { it.titleKey }) { item ->
                                    val fraction = if (item.durationMs > 0) item.positionMs / item.durationMs.toFloat() else null
                                    PosterCard(
                                        title = item.title,
                                        imageUrl = item.pic,
                                        subtitle = item.episodeName,
                                        width = 220.dp,
                                        height = 124.dp,
                                        progress = fraction,
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                val merged = app.library.findMerged(item.titleKey)
                                                if (merged != null) {
                                                    val full = runCatching { app.catalog.hydrate(merged) }.getOrDefault(merged)
                                                    app.session.request = PlayRequest(full, item.episodeIndex, item.positionMs, item.lineId)
                                                    onPlay()
                                                }
                                                busy = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        "favorite" -> if (favorites.isNotEmpty()) {
                            SectionTitle(row.displayTitle(), Modifier.padding(top = 8.dp, bottom = 8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                                items(favorites, key = { it.titleKey }) { item ->
                                    PosterCard(item.title, item.pic, item.typeName, posterW, posterH, onClick = { onOpen(item.titleKey) })
                                }
                            }
                        }
                        else -> {
                            val items = state.catalog?.rows?.get(row.id).orEmpty()
                            if (items.isNotEmpty()) {
                                SectionTitle(row.displayTitle(), Modifier.padding(top = 8.dp, bottom = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                                    items(items, key = { it.key }) { item ->
                                        PosterCard(item.title, item.pic, meta(item), posterW, posterH, onClick = { onOpen(item.key) })
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.catalog?.rows.isNullOrEmpty() && history.isEmpty() && favorites.isEmpty() && !state.loading) {
                    Text(state.error ?: "这些接口暂时没有返回点播内容。", color = palette.muted, modifier = Modifier.padding(top = 24.dp))
                    TvButton("重试", modifier = Modifier.padding(top = 12.dp)) { vm.load() }
                }
            }
        }
    }
    if (busy) BoxCenter()
    }
}

@Composable
private fun BoxCenter() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalPalette.current.accent)
    }
}

private fun meta(item: MergedVod): String = listOfNotNull(item.year, item.remarks, item.typeName).joinToString(" · ")

@Composable
private fun factory(create: (AppContainer) -> ViewModel): ViewModelProvider.Factory {
    val app = LocalApp.current
    return remember(app) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return create(app) as T
            }
        }
    }
}
