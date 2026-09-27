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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jianxia.core.live.IptvOrg
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
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LiveState(
    val loading: Boolean = true,
    val channels: List<LiveChannel> = emptyList(),
    val guide: EpgGuide = EpgGuide(),
    val error: String? = null,
    val public: Boolean = false,
    val publicLabel: String = "中国",
    val publicCode: String = "cn",
    val publicKind: String = "country",
    val unavailable: Set<String> = emptySet(),
    val retry: Int = 0,
)

data class PickerState(
    val kind: String? = null,
    val loading: Boolean = false,
    val entries: List<IptvOrg.Entry> = emptyList(),
    val error: String? = null,
)

class LiveViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()
    private val _picker = MutableStateFlow(PickerState())
    val picker: StateFlow<PickerState> = _picker.asStateFlow()
    private var probeJob: Job? = null
    private val checked = mutableSetOf<String>()

    fun load(force: Boolean = false) {
        probeJob?.cancel()
        checked.clear()
        viewModelScope.launch {
            val previous = _state.value
            _state.value = previous.copy(loading = true, error = null)
            runCatching { app.sources.ensurePublicChannels(app.settings) }
            val settings = app.settings.state.value
            val hasPublic = app.sources.list().any { it.id == IptvOrg.ID }
            val expanded = runCatching { app.catalog.expand(force) }.getOrNull()
            val preferred = settings.defaultSourceId
            val mine = expanded?.lives.orEmpty().filter { it.originId == preferred }
            val lives = if (preferred.isNotBlank() && mine.isNotEmpty()) mine else expanded?.lives.orEmpty()
            if (lives.isEmpty()) {
                _state.value = LiveState(
                    loading = false,
                    error = "还没有直播源",
                    public = hasPublic,
                    publicLabel = IptvOrg.label(settings.iptvOrgKind, settings.iptvOrgCode),
                    publicCode = settings.iptvOrgCode,
                    publicKind = settings.iptvOrgKind,
                )
                return@launch
            }
            val loaded = app.live.load(lives)
            val blocked = loaded.channels.filter { IptvOrg.listedAsBlocked(it.name) }.map { it.url }.toSet()
            checked += blocked
            _state.value = LiveState(
                loading = false,
                channels = loaded.channels,
                guide = loaded.guide,
                error = loaded.error,
                public = hasPublic,
                publicLabel = IptvOrg.label(settings.iptvOrgKind, settings.iptvOrgCode),
                publicCode = settings.iptvOrgCode,
                publicKind = settings.iptvOrgKind,
                unavailable = blocked,
            )
        }
    }

    fun openPicker(kind: String) {
        viewModelScope.launch {
            _picker.value = PickerState(kind = kind, loading = true)
            val text = runCatching { app.http.text(IptvOrg.INDEX, maxBytes = 400_000) }.getOrNull()
            val parsed = text?.let { IptvOrg.parseIndex(it) }.orEmpty().filter { it.kind == kind }
            val current = app.settings.state.value.iptvOrgCode
            val entries = (if (parsed.isEmpty()) IptvOrg.fallback(kind) else parsed)
                .sortedWith(compareByDescending<IptvOrg.Entry> { it.code == current }.thenBy { it.label })
            _picker.value = PickerState(
                kind = kind,
                entries = entries,
                error = if (text == null) "索引暂时打不开，先用常用列表" else null,
            )
        }
    }

    fun closePicker() {
        _picker.value = PickerState()
    }

    fun choose(entry: IptvOrg.Entry) {
        viewModelScope.launch {
            app.sources.applyPublicPlaylist(entry.kind, entry.code, entry.label)
            app.settings.update {
                it.copy(iptvOrgSeeded = true, iptvOrgKind = entry.kind, iptvOrgCode = entry.code)
            }
            _picker.value = PickerState()
            load(force = true)
        }
    }

    fun restorePublic() {
        val settings = app.settings.state.value
        choose(
            IptvOrg.Entry(
                kind = settings.iptvOrgKind,
                code = settings.iptvOrgCode,
                label = IptvOrg.label(settings.iptvOrgKind, settings.iptvOrgCode),
                url = IptvOrg.playlist(settings.iptvOrgKind, settings.iptvOrgCode),
            ),
        )
    }

    fun probeAround(index: Int) {
        val channels = _state.value.channels
        if (channels.isEmpty()) return
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val slice = channels.drop((index + 1).coerceAtLeast(0)).take(6)
            val gate = Semaphore(2)
            coroutineScope {
                slice.map { channel ->
                    async {
                        if (!isActive) return@async
                        if (channel.url in _state.value.unavailable || channel.url in checked) return@async
                        if (IptvOrg.listedAsBlocked(channel.name)) {
                            mark(channel.url)
                            return@async
                        }
                        gate.acquire()
                        try {
                            if (!isActive) return@async
                            val probe = app.http.probe(channel.url)
                            checked += channel.url
                            if (!probe.ok) mark(channel.url)
                        } finally {
                            gate.release()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun fail(url: String) = mark(url)

    fun revive(url: String) {
        checked += url
        val current = _state.value
        if (url !in current.unavailable) return
        _state.value = current.copy(unavailable = current.unavailable - url)
    }

    fun retry(url: String) {
        checked -= url
        val current = _state.value
        _state.value = current.copy(unavailable = current.unavailable - url, retry = current.retry + 1)
    }

    private fun mark(url: String) {
        checked += url
        val current = _state.value
        if (url in current.unavailable) return
        _state.value = current.copy(unavailable = current.unavailable + url)
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
    val picker by vm.picker.collectAsStateWithLifecycle()
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
    val channelNow = rememberUpdatedState(state.channels.getOrNull(index))
    DisposableEffect(host) {
        host.listener = object : PlaybackHost.Listener {
            override fun onFirstFrame() {
                connecting = false
                playError = null
                channelNow.value?.let { vm.revive(it.url) }
            }

            override fun onFailure(message: String) {
                if (!host.hasFirstFrame && fallback.onFailed()) {
                    connecting = true
                    playError = null
                    return
                }
                connecting = false
                playError = "这个频道暂时播不了"
                channelNow.value?.let { vm.fail(it.url) }
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
    LaunchedEffect(index, state.channels, placed) {
        if (placed) vm.probeAround(index)
    }
    LaunchedEffect(index, state.channels, settings.playerEngine, settings.decoder, placed, state.retry) {
        if (!placed) return@LaunchedEffect
        val channel = state.channels.getOrNull(index) ?: return@LaunchedEffect
        if (channel.url in state.unavailable) {
            host.pause()
            connecting = false
            playError = "这个频道暂时播不了"
            return@LaunchedEffect
        }
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
        state.channels.isEmpty() && !state.public -> EmptyHint(
            "还没有直播",
            state.error ?: "可以添加公共频道，或在设置里加入自己的 M3U / TXT 直播源。",
            "添加公共频道",
        ) { vm.restorePublic() }
        else -> Row(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (picker.kind != null) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        index = (index - 1).coerceAtLeast(0)
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        index = (index + 1).coerceAtMost(state.channels.lastIndex.coerceAtLeast(0))
                        true
                    }
                    else -> false
                }
            },
        ) {
            Column(Modifier.width(380.dp).fillMaxHeight().padding(12.dp)) {
                if (state.public) {
                    Text(IptvOrg.ATTRIBUTION, color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("当前：${state.publicLabel}", color = palette.accent, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectChip("国家", picker.kind == "country") { vm.openPicker("country") }
                        SelectChip("分类", picker.kind == "category") { vm.openPicker("category") }
                        SelectChip("语言", picker.kind == "language") { vm.openPicker("language") }
                    }
                    picker.error?.let { Text(it, color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
                if (picker.kind != null) {
                    if (picker.loading) {
                        CircularProgressIndicator(color = palette.accent, modifier = Modifier.padding(top = 16.dp))
                    } else {
                        val listState = rememberLazyListState()
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                            item {
                                TvButton("返回频道", modifier = Modifier.padding(bottom = 8.dp)) { vm.closePicker() }
                            }
                            items(picker.entries, key = { "${it.kind}:${it.code}" }) { entry ->
                                SelectChip(
                                    entry.label,
                                    entry.code == state.publicCode && entry.kind == state.publicKind,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                    onClick = { vm.choose(entry) },
                                )
                            }
                        }
                    }
                } else if (state.channels.isEmpty()) {
                    Text(state.error ?: "这个列表暂时是空的", color = palette.muted, modifier = Modifier.padding(top = 16.dp))
                    TvButton("重试", modifier = Modifier.padding(top = 10.dp)) { vm.load(force = true) }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(index) { listState.animateScrollToItem(index) }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                        itemsIndexed(state.channels, key = { itemIndex, channel -> "$itemIndex:${channel.url}" }) { itemIndex, channel ->
                            val dead = channel.url in state.unavailable
                            SelectChip(
                                if (dead) "${channel.name}  暂不可用" else "${channel.group}  ${channel.name}",
                                itemIndex == index,
                                dimmed = dead,
                                modifier = Modifier.padding(bottom = 6.dp),
                                onClick = {
                                    if (dead) vm.retry(channel.url)
                                    index = itemIndex
                                },
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx).also { host.bind(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
                val channel = state.channels.getOrNull(index)
                if (channel != null) {
                    val (now, next) = state.guide.nowAndNext(channel, System.currentTimeMillis())
                    Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(20.dp)) {
                        Text(channel.name, color = Color.White, fontSize = 24.sp)
                        val clock = SimpleDateFormat("HH:mm", Locale.CHINA)
                        if (now != null) {
                            Text("正在播出  ${now.title}  ${clock.format(Date(now.startMs))}-${clock.format(Date(now.stopMs))}", color = Color.White)
                        }
                        if (next != null) Text("下一档  ${next.title}", color = Color(0xFFD9D3C7))
                        if (channel.url in state.unavailable) {
                            Text("这个频道暂时播不了", color = palette.danger, modifier = Modifier.padding(top = 6.dp))
                            TvButton("重试", modifier = Modifier.padding(top = 8.dp)) { vm.retry(channel.url) }
                        } else if (connecting && playError == null) {
                            Text("正在连接", color = palette.accent, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            playError?.let { Text(it, color = palette.danger, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                } else {
                    Text(
                        state.error ?: "先在左边选一个国家、分类或语言",
                        color = Color.White,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
    }
}
