package app.jianxia.tv.ui.live

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jianxia.core.model.EpgGuide
import app.jianxia.core.model.LiveChannel
import app.jianxia.tv.AppContainer
import app.jianxia.tv.player.EngineFallback
import app.jianxia.tv.player.PlaybackHost
import app.jianxia.tv.player.StreamOpen
import app.jianxia.tv.player.parseEngine
import app.jianxia.tv.ui.EmptyHint
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LiveState(
    val loading: Boolean = true,
    val channels: List<LiveChannel> = emptyList(),
    val guide: EpgGuide = EpgGuide(),
    val error: String? = null,
)

class LiveViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = LiveState()
            val expanded = runCatching { app.catalog.expand() }.getOrNull()
            if (expanded == null || expanded.lives.isEmpty()) {
                _state.value = LiveState(loading = false, error = "还没有直播源")
                return@launch
            }
            val preferred = app.settings.state.value.defaultSourceId
            val mine = expanded.lives.filter { it.originId == preferred }
            val lives = if (preferred.isNotBlank() && mine.isNotEmpty()) mine else expanded.lives
            val channels = app.live.channels(lives)
            val guide = app.live.guide(lives)
            _state.value = LiveState(loading = false, channels = channels, guide = guide, error = if (channels.isEmpty()) "直播列表是空的" else null)
        }
    }

    fun remember(url: String) {
        viewModelScope.launch { app.settings.update { it.copy(lastLiveUrl = url) } }
    }
}

@Composable
fun LiveScreen() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val vm: LiveViewModel = appViewModel { LiveViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var index by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    val host = remember {
        PlaybackHost(context).also { created ->
            val current = app.settings.state.value
            created.setEngine(parseEngine(current.playerEngine), current.decoder == "software")
        }
    }
    val fallback = remember { EngineFallback(host) }
    DisposableEffect(host) {
        host.listener = object : PlaybackHost.Listener {
            override fun onFirstFrame() {
                connecting = false
                playError = null
            }

            override fun onFailure(message: String) {
                if (!host.hasFirstFrame && fallback.onFailed()) {
                    connecting = true
                    playError = null
                    return
                }
                connecting = false
                playError = "这个频道暂时播不了"
            }

            override fun onState(playing: Boolean, buffering: Boolean) {
                connecting = !host.hasFirstFrame || buffering
            }
        }
        onDispose { host.release() }
    }
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.channels) {
        if (state.channels.isEmpty()) {
            placed = false
            return@LaunchedEffect
        }
        val saved = state.channels.indexOfFirst { it.url == settings.lastLiveUrl }
        index = if (saved >= 0) saved else 0
        placed = true
    }
    LaunchedEffect(index, state.channels, settings.playerEngine, settings.decoder, placed) {
        if (!placed) return@LaunchedEffect
        val channel = state.channels.getOrNull(index) ?: return@LaunchedEffect
        fallback.reset()
        connecting = true
        playError = null
        host.setEngine(parseEngine(settings.playerEngine), settings.decoder == "software")
        host.setSpeed(settings.defaultSpeed)
        host.play(
            StreamOpen(
                url = channel.url,
                userAgent = channel.userAgent,
                referer = channel.referer,
                headers = channel.headers,
                speed = settings.defaultSpeed,
            ),
        )
        vm.remember(channel.url)
    }
    when {
        state.loading -> Box(Modifier.fillMaxSize().padding(ScreenPadding)) { CircularProgressIndicator(color = palette.accent) }
        state.channels.isEmpty() -> EmptyHint("还没有直播", state.error ?: "在设置里添加 M3U 或 TXT 直播源。", "知道了") {}
        else -> Row(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        index = (index - 1).coerceAtLeast(0)
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        index = (index + 1).coerceAtMost(state.channels.lastIndex)
                        true
                    }
                    else -> false
                }
            },
        ) {
            val listState = rememberLazyListState()
            LaunchedEffect(index) { listState.animateScrollToItem(index) }
            LazyColumn(state = listState, modifier = Modifier.width(320.dp).fillMaxHeight().padding(12.dp)) {
                itemsIndexed(state.channels) { itemIndex, channel ->
                    SelectChip(
                        "${channel.group}  ${channel.name}",
                        itemIndex == index,
                        modifier = Modifier.padding(bottom = 6.dp),
                        onClick = { index = itemIndex },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx).also { host.bind(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
                val channel = state.channels[index]
                val (now, next) = state.guide.nowAndNext(channel, System.currentTimeMillis())
                Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(20.dp)) {
                    Text(channel.name, color = Color.White, fontSize = 24.sp)
                    val clock = SimpleDateFormat("HH:mm", Locale.CHINA)
                    if (now != null) {
                        Text("正在播出  ${now.title}  ${clock.format(Date(now.startMs))}-${clock.format(Date(now.stopMs))}", color = Color.White)
                    }
                    if (next != null) Text("下一档  ${next.title}", color = Color(0xFFD9D3C7))
                    if (connecting && playError == null) Text("正在连接", color = palette.accent, modifier = Modifier.padding(top = 6.dp))
                    playError?.let { Text(it, color = palette.danger, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}
