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
import app.jianxia.core.live.SportsLive
import app.jianxia.core.model.EpgGuide
import app.jianxia.core.model.LineProbe
import app.jianxia.core.model.LiveChannel
import app.jianxia.tv.AppContainer
import app.jianxia.tv.player.EngineFallback
import app.jianxia.tv.player.PlaybackHost
import app.jianxia.tv.player.StreamOpen
import app.jianxia.tv.player.parseEngine
import app.jianxia.tv.ui.EmptyHint
import app.jianxia.core.hls.HlsAdFilter
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
    val catalog: List<LiveChannel> = emptyList(),
    val showingSports: Boolean = false,
    val sportsLines: List<List<LiveChannel>> = emptyList(),
    val sportsLoading: Boolean = false,
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
    private var rankToken = 0
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
                catalog = loaded.channels,
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

    fun showSports() {
        probeJob?.cancel()
        viewModelScope.launch {
            _picker.value = PickerState()
            val current = _state.value
            val catalog = current.catalog.ifEmpty { current.channels }
            _state.value = current.copy(showingSports = true, sportsLoading = true, channels = emptyList(), error = null)
            val playlist = runCatching { app.live.sportsPlaylist() }.getOrDefault(emptyList())
            val entries = SportsLive.aggregate(catalog, playlist)
            val lines = entries.map { it.lines }
            val visible = entries.map { it.lines.first() }
            val blocked = visible.filter { IptvOrg.listedAsBlocked(it.name) }.map { it.url }.toSet()
            checked += blocked
            _state.value = _state.value.copy(
                loading = false,
                showingSports = true,
                sportsLoading = false,
                sportsLines = lines,
                channels = visible,
                catalog = catalog,
                unavailable = _state.value.unavailable + blocked,
                error = if (visible.isEmpty()) "没有找到体育频道" else null,
            )
        }
    }

    fun showAll() {
        probeJob?.cancel()
        val current = _state.value
        val catalog = current.catalog.ifEmpty { current.channels }
        _state.value = current.copy(
            showingSports = false,
            sportsLoading = false,
            sportsLines = emptyList(),
            channels = catalog,
            error = if (catalog.isEmpty()) current.error ?: "直播列表是空的" else null,
        )
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
        if (_state.value.showingSports) {
            rankSportsLines(index)
        }
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

    /** 体育条目里还有别的线路时换下一条，并返回 true。 */
    fun advanceSportsLine(index: Int): Boolean {
        val current = _state.value
        if (!current.showingSports) return false
        val lines = current.sportsLines.getOrNull(index) ?: return false
        val playing = current.channels.getOrNull(index)?.url ?: return false
        mark(playing)
        val next = lines.firstOrNull { it.url != playing && it.url !in _state.value.unavailable }
        if (next == null) return false
        val visible = current.channels.toMutableList()
        if (index !in visible.indices) return false
        visible[index] = next
        _state.value = _state.value.copy(channels = visible, retry = _state.value.retry + 1)
        return true
    }

    private fun rankSportsLines(index: Int) {
        val lines = _state.value.sportsLines.getOrNull(index).orEmpty()
        if (lines.size < 2) return
        val token = ++rankToken
        viewModelScope.launch {
            val gate = Semaphore(2)
            val probes = coroutineScope {
                lines.take(4).map { line ->
                    async {
                        gate.acquire()
                        try {
                            val probe = app.http.probe(line.url)
                            LineProbe(line.url, probe.connectMs, probe.firstByteMs, probe.resolutionHeight, probe.ok)
                        } finally {
                            gate.release()
                        }
                    }
                }.awaitAll()
            }
            if (token != rankToken || !_state.value.showingSports) return@launch
            val ranked = SportsLive.orderLines(lines, probes)
            val best = ranked.firstOrNull { line -> probes.any { it.id == line.url && it.ok } } ?: return@launch
            val stateNow = _state.value
            val playing = stateNow.channels.getOrNull(index)?.url
            val stored = stateNow.sportsLines.toMutableList()
            if (index !in stored.indices) return@launch
            stored[index] = ranked
            if (best.url == playing) {
                _state.value = stateNow.copy(sportsLines = stored)
                return@launch
            }
            val visible = stateNow.channels.toMutableList()
            visible[index] = best
            _state.value = stateNow.copy(sportsLines = stored, channels = visible, retry = stateNow.retry + 1)
        }
    }

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
    val indexNow = rememberUpdatedState(index)
    val sportsNow = rememberUpdatedState(state.showingSports)
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
                if (sportsNow.value && vm.advanceSportsLine(indexNow.value)) {
                    connecting = true
                    playError = null
                    return
                }
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
    val listKey = state.showingSports to state.channels.map { it.name }
    LaunchedEffect(listKey) {
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
        val open = StreamOpen(
            url = channel.url,
            userAgent = channel.userAgent,
            referer = channel.referer,
            headers = channel.headers,
            speed = settings.defaultSpeed,
        )
        val (agent, headerMap) = open.requestHeaders()
        val playbackUrl = if (settings.skipHlsAds) {
            app.hls.wrap(channel.url, headerMap + ("User-Agent" to agent), HlsAdFilter.compileRules(settings.hlsAdRules))
        } else {
            channel.url
        }
        host.play(open.copy(url = playbackUrl))
        vm.remember(channel.url)
    }
    when {
        state.loading -> Box(Modifier.fillMaxSize().padding(ScreenPadding)) { CircularProgressIndicator(color = palette.accent) }
        state.channels.isEmpty() && !state.public && !state.showingSports && !state.sportsLoading -> EmptyHint(
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectChip(SportsLive.LABEL, state.showingSports) { vm.showSports() }
                    if (state.showingSports) {
                        SelectChip("全部", false) { vm.showAll() }
                    }
                }
                if (state.showingSports) {
                    Text(
                        "同名频道合成一条，自动用较快的线路。只汇总已启用的直播源和 iptv-org 公共体育列表。",
                        color = palette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                }
                if (state.public && !state.showingSports) {
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
                } else if (state.sportsLoading) {
                    CircularProgressIndicator(color = palette.accent, modifier = Modifier.padding(top = 16.dp))
                } else if (state.channels.isEmpty()) {
                    Text(state.error ?: "这个列表暂时是空的", color = palette.muted, modifier = Modifier.padding(top = 16.dp))
                    if (state.showingSports) {
                        TvButton("返回全部", modifier = Modifier.padding(top = 10.dp)) { vm.showAll() }
                    } else {
                        TvButton("重试", modifier = Modifier.padding(top = 10.dp)) { vm.load(force = true) }
                    }
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(index) { listState.animateScrollToItem(index) }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                        itemsIndexed(state.channels, key = { itemIndex, channel -> "$itemIndex:${channel.url}" }) { itemIndex, channel ->
                            val dead = channel.url in state.unavailable
                            val lineCount = state.sportsLines.getOrNull(itemIndex)?.size ?: 1
                            val label = when {
                                dead -> "${channel.name}  暂不可用"
                                state.showingSports && lineCount > 1 -> "${channel.name}  ·  $lineCount 条线路"
                                state.showingSports -> channel.name
                                else -> "${channel.group}  ${channel.name}"
                            }
                            SelectChip(
                                label,
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
