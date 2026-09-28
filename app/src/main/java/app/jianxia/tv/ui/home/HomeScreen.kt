package app.jianxia.tv.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import app.jianxia.core.UserFacingError
import app.jianxia.core.model.SiteReport
import app.jianxia.tv.AppContainer
import app.jianxia.tv.data.repo.HomeCatalog
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.TvButton
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
fun HomeScreen(
    onOpen: (String) -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onDouban: () -> Unit,
    onSearch: (String) -> Unit,
    onBrowseSite: (String) -> Unit,
    onHistory: () -> Unit,
    onLive: () -> Unit,
    onFavorites: () -> Unit,
    onPush: () -> Unit,
    onSearchPage: () -> Unit,
) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val sources by app.sources.observe().collectAsStateWithLifecycle(emptyList())
    val history by app.library.history().collectAsStateWithLifecycle(emptyList())
    val favorites by app.library.favorites().collectAsStateWithLifecycle(emptyList())
    val vm: HomeViewModel = viewModel(factory = factory { HomeViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var showSites by remember { mutableStateOf(false) }
    val fingerprint = sources.joinToString { "${it.id}:${it.enabled}:${it.url}:${it.kind}:${it.note}" }
    LaunchedEffect(fingerprint, settings.defaultSourceId, settings.searchTimeoutSec) { vm.load() }
    if (showSites) {
        SiteStatusPage(state.catalog?.reports.orEmpty(), state.catalog?.message, onBrowseSite) { showSites = false }
        return
    }
    val noTitles = state.catalog?.rows?.values?.none { it.isNotEmpty() } != false
    val showEmpty = sources.any { it.enabled } && noTitles && history.isEmpty() && favorites.isEmpty() && !state.loading
    if (settings.homeShell == "cinema") {
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
            onSites = { showSites = true },
            showEmpty = showEmpty,
            onDouban = onDouban,
            onSearch = onSearch,
        )
        return
    }
    WarehouseHome(
        onOpen = onOpen,
        onSearchTitle = onSearch,
        onHistory = onHistory,
        onLive = onLive,
        onSearchPage = onSearchPage,
        onPush = onPush,
        onFavorites = onFavorites,
        onSettings = onSettings,
        onSites = { showSites = true },
    )
}

@Composable
private fun SiteStatusPage(
    reports: List<SiteReport>,
    summary: String?,
    onBrowse: (String) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val ordered = reports.sortedBy { statusRank(it.status) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
        Text("站点状态", color = palette.text, fontSize = 28.sp)
        if (!summary.isNullOrBlank()) {
            Text(summary, color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
        TvButton("返回", primary = true, onClick = onBack)
        if (ordered.isEmpty()) {
            Text("还没有站点记录。先添加配置，再回到首页。", color = palette.muted, modifier = Modifier.padding(top = 16.dp))
        }
        ordered.forEach { report ->
            Text(
                "${report.configName} · ${report.siteName}",
                color = palette.text,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "${report.status}：${report.detail}",
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = 20.sp,
            )
            if (report.status == "可用") {
                TvButton("筛选这个站点", modifier = Modifier.padding(top = 8.dp)) { onBrowse(report.id) }
            }
        }
    }
}

private fun statusRank(status: String): Int = when (status) {
    "失败" -> 0
    "可用" -> 1
    else -> 2
}

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
