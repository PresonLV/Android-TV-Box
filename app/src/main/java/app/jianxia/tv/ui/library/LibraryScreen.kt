package app.jianxia.tv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.tv.PlayRequest
import app.jianxia.tv.ui.EmptyHint
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PageTitle
import app.jianxia.tv.ui.PosterCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.posterSize
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(favorites: Boolean, onOpen: (String) -> Unit, onPlay: () -> Unit = {}) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val history by app.library.history().collectAsStateWithLifecycle(emptyList())
    val favoriteItems by app.library.favorites().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val (posterW, posterH) = posterSize(settings.posterSize)
    Column(Modifier.fillMaxSize().padding(ScreenPadding())) {
        PageTitle(if (favorites) "我的收藏" else "观看历史")
        if (favorites && favoriteItems.isEmpty()) {
            EmptyHint("还没有收藏", "在详情页把片子收进来，这里会按标题记住。", "知道了") {}
            return
        }
        if (!favorites && history.isEmpty()) {
            EmptyHint("还没有历史", "播放过的片子会出现在这里，并可以从上次的进度继续。", "知道了") {}
            return
        }
        TvButton(
            if (favorites) "清空收藏" else "清空历史",
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        ) {
            scope.launch { if (favorites) app.library.clearFavorites() else app.library.clearHistory() }
        }
        if (favorites) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(posterW + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            ) {
                items(favoriteItems, key = { it.titleKey }) { item ->
                    PosterCard(item.title, item.pic, item.typeName, posterW, posterH, onClick = { onOpen(item.titleKey) })
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(history, key = { it.titleKey }) { item ->
                    PosterCard(
                        title = item.title,
                        imageUrl = item.pic,
                        subtitle = item.episodeName,
                        width = 240.dp,
                        height = 136.dp,
                        progress = if (item.durationMs > 0) item.positionMs / item.durationMs.toFloat() else null,
                        onClick = {
                            scope.launch {
                                val merged = app.library.findMerged(item.titleKey) ?: return@launch
                                val full = runCatching { app.catalog.hydrate(merged) }.getOrDefault(merged)
                                app.session.request = PlayRequest(full, item.episodeIndex, item.positionMs, item.lineId)
                                onPlay()
                            }
                        },
                    )
                }
            }
        }
    }
}
