package app.jianxia.tv.player

import android.content.Context
import android.widget.FrameLayout
import app.jianxia.core.line.rankLines
import app.jianxia.core.model.LineProbe
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.ParseDef
import app.jianxia.core.parser.extractMediaUrl
import app.jianxia.core.parser.isDirectMediaUrl
import app.jianxia.core.parser.urlEncode
import app.jianxia.tv.AppContainer
import app.jianxia.tv.data.net.NetClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlayerPanel { Hidden, Main, Lines, Speed, Aspect, Skip, Engine }

data class LineOption(val id: String, val label: String, val detail: String?)

data class PlayerUi(
    val empty: Boolean = false,
    val title: String = "",
    val episodeName: String = "",
    val episodeIndex: Int = 0,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val lineLabel: String = "",
    val lines: List<LineOption> = emptyList(),
    val selectedLineId: String = "",
    val speed: Float = 1f,
    val aspect: String = "fit",
    val engine: String = "vlc",
    val playing: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = true,
    val panel: PlayerPanel = PlayerPanel.Main,
    val hint: String? = null,
    val introSec: Int = 0,
    val outroSec: Int = 0,
    val countdown: Int? = null,
    val error: String? = null,
    val probing: Boolean = false,
)

private data class Candidate(
    val id: String,
    val sourceName: String,
    val lineName: String,
    val episodeName: String,
    val url: String,
    val episodeCount: Int,
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
)

class PlayerViewModel(private val app: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var host: PlaybackHost? = null
    private var fallback: EngineFallback? = null
    private var kernel: EngineId = EngineId.Vlc
    private var item: MergedVod? = null
    private var episodeIndex = 0
    private var current: Candidate? = null
    private var parses: List<ParseDef> = emptyList()
    private val resolved = mutableMapOf<String, String>()
    private val probes = mutableMapOf<String, LineProbe>()
    private var ranked: List<String> = emptyList()
    private val failed = mutableSetOf<String>()
    private var manual = false
    private var software = false
    private var introMs = 0L
    private var outroMs = 0L
    private var introApplied = false
    private var outroFired = false
    private var suppressEnd = false
    private var speed = 1f
    private var aspect = "fit"
    private var lastPos = 0L
    private var lastMove = System.currentTimeMillis()
    private var bufferingSince = 0L
    private var hideJob: Job? = null
    private var failoverJob: Job? = null
    private var countdown: Int? = null
    private var countdownAt = 0L
    private var attached = false
    private var playToken = 0

    fun attach(context: Context) {
        if (host != null) return
        val created = PlaybackHost(context)
        host = created
        fallback = EngineFallback(created)
        val settings = app.settings.state.value
        software = settings.decoder == "software"
        kernel = parseEngine(settings.playerEngine)
        speed = settings.defaultSpeed
        aspect = settings.aspect
        created.setEngine(kernel, software)
        created.setSpeed(speed)
        created.setAspect(aspect)
        created.listener = object : PlaybackHost.Listener {
            override fun onReady() {
                suppressEnd = false
                outroFired = false
                maybeIntro()
            }

            override fun onFirstFrame() {
                suppressEnd = false
                outroFired = false
                maybeIntro()
                publish()
            }

            override fun onEnded() {
                if (!suppressEnd) finishEpisode()
            }

            override fun onFailure(message: String) {
                val playback = host ?: return
                if (!playback.hasFirstFrame && fallback?.onFailed() == true) {
                    _ui.update {
                        it.copy(
                            engine = playback.engine.wire(),
                            hint = "改用 ${playback.engine.label()} 重试",
                            error = null,
                            buffering = true,
                        )
                    }
                    return
                }
                failover(if (playback.hasFirstFrame) "播放中断" else message.ifBlank { "播放失败" })
            }

            override fun onState(playing: Boolean, buffering: Boolean) {
                _ui.update { it.copy(playing = playing, buffering = buffering) }
                if (playing) lastMove = System.currentTimeMillis()
            }
        }
        if (!attached) {
            attached = true
            viewModelScope.launch { begin() }
            startTicker()
        }
    }

    fun bindSurface(target: FrameLayout) {
        host?.bind(target)
    }

    fun showMain() {
        _ui.update { it.copy(panel = PlayerPanel.Main) }
        poke()
    }

    fun hide() {
        if (countdown != null) return
        _ui.update { it.copy(panel = PlayerPanel.Hidden) }
    }

    fun panel(panel: PlayerPanel) {
        _ui.update { it.copy(panel = panel) }
        if (panel == PlayerPanel.Main) poke() else hideJob?.cancel()
    }

    fun playPause() {
        host?.toggle()
        showMain()
    }

    fun seekBy(deltaMs: Long) {
        val playback = host ?: return
        playback.seekBy(deltaMs)
        lastPos = playback.positionMs
        lastMove = System.currentTimeMillis()
        showMain()
    }

    fun next() = changeEpisode(episodeIndex + 1)
    fun previous() = changeEpisode(episodeIndex - 1)

    fun chooseLine(id: String) {
        val candidate = candidates().firstOrNull { it.id == id } ?: return
        manual = true
        failed.clear()
        val key = item?.key ?: return
        viewModelScope.launch { app.library.saveLine(key, id) }
        play(candidate, 0)
        _ui.update { it.copy(panel = PlayerPanel.Main, hint = "已切换到「${candidate.lineName}」") }
        poke()
    }

    fun setSpeed(value: Float) {
        speed = value
        host?.setSpeed(value)
        _ui.update { it.copy(speed = value) }
    }

    fun setAspect(value: String) {
        aspect = value
        host?.setAspect(value)
        _ui.update { it.copy(aspect = value) }
    }

    fun setKernel(wire: String) {
        val next = parseEngine(wire)
        kernel = next
        fallback?.reset()
        val resume = host?.positionMs ?: _ui.value.positionMs
        viewModelScope.launch { app.settings.update { it.copy(playerEngine = next.wire()) } }
        val candidate = current
        if (candidate != null) {
            play(candidate, resume)
        } else {
            host?.setEngine(next, software)
        }
        _ui.update { it.copy(engine = next.wire(), panel = PlayerPanel.Main, hint = "已切换到 ${next.label()}") }
        poke()
    }

    fun openExternal(context: Context) {
        val open = host?.currentOpen
        if (open == null || open.url.isBlank()) {
            _ui.update { it.copy(hint = "没有可打开的地址") }
            return
        }
        val (agent, headers) = open.requestHeaders()
        val message = openExternalPlayer(
            context = context,
            url = open.url,
            title = _ui.value.title,
            userAgent = agent,
            referer = headers["Referer"].orEmpty(),
            headers = headers,
        )
        if (message != null) _ui.update { it.copy(hint = message) }
    }

    fun setSkip(introSec: Int, outroSec: Int) {
        introMs = introSec * 1000L
        outroMs = outroSec * 1000L
        introApplied = false
        val key = item?.key ?: return
        viewModelScope.launch { app.library.saveSkip(key, introMs, outroMs) }
        _ui.update { it.copy(introSec = introSec, outroSec = outroSec, hint = "已记住这部片子的片头片尾") }
    }

    fun cancelCountdown() {
        countdown = null
        _ui.update { it.copy(countdown = null) }
    }

    fun reprobe() {
        manual = false
        val key = item?.key ?: return
        viewModelScope.launch {
            app.library.clearLine(key)
            _ui.update { it.copy(probing = true, hint = "正在重新测速", panel = PlayerPanel.Lines) }
            val list = candidates()
            applyProbes(probeAll(list))
            val best = ranked.firstNotNullOfOrNull { id ->
                list.firstOrNull { it.id == id && probes[id]?.ok != false }
            }
            if (best != null) {
                failed.clear()
                play(best, host?.positionMs ?: 0)
                app.library.saveLine(key, best.id)
            }
            _ui.update { it.copy(probing = false, hint = "已选择当前最快的线路") }
        }
    }

    private suspend fun begin() {
        val opening = app.session.request
        if (opening == null) {
            _ui.update { it.copy(empty = true, buffering = false, panel = PlayerPanel.Hidden) }
            return
        }
        item = opening.item
        parses = runCatching { app.catalog.parses() }.getOrDefault(emptyList())
        val settings = app.settings.state.value
        speed = settings.defaultSpeed
        aspect = settings.aspect
        kernel = parseEngine(settings.playerEngine)
        software = settings.decoder == "software"
        host?.setSpeed(speed)
        host?.setAspect(aspect)
        val skip = app.library.skip(opening.item.key)
        introMs = skip?.introMs ?: 0
        outroMs = skip?.outroMs ?: 0
        episodeIndex = opening.episodeIndex
        if (candidates().isEmpty()) episodeIndex = 0
        val list = candidates()
        if (list.isEmpty()) {
            _ui.update { it.copy(error = "没有可播放的线路", buffering = false, title = opening.item.title) }
            return
        }
        ranked = list.map { it.id }
        val memory = opening.preferredLineId ?: app.library.lineChoice(opening.item.key)
        val initial = list.firstOrNull { it.id == memory } ?: list.first()
        manual = memory != null
        publish()
        if (settings.autoLineSelect && !manual) {
            _ui.update { it.copy(probing = true, hint = "正在测速，选择最快线路") }
            val quick = withTimeoutOrNull(1_800) { probeAll(list) }
            if (quick != null) {
                applyProbes(quick)
                val best = ranked.firstNotNullOfOrNull { id -> list.firstOrNull { it.id == id && probes[id]?.ok == true } } ?: initial
                play(best, opening.resumeMs)
                app.library.saveLine(opening.item.key, best.id)
            } else {
                play(initial, opening.resumeMs)
                viewModelScope.launch {
                    applyProbes(probeAll(list))
                    _ui.update { it.copy(probing = false) }
                }
            }
            _ui.update { it.copy(probing = false) }
        } else {
            play(initial, opening.resumeMs)
            if (settings.autoLineSelect) {
                viewModelScope.launch { applyProbes(probeAll(list)) }
            }
        }
        showMain()
    }

    private fun candidates(): List<Candidate> = candidatesFor(episodeIndex)

    private fun candidatesFor(index: Int): List<Candidate> {
        val currentItem = item ?: return emptyList()
        return currentItem.variants.flatMap { variant ->
            variant.lines.mapNotNull { line ->
                val episode = line.episodes.getOrNull(index) ?: return@mapNotNull null
                Candidate(
                    id = "${variant.sourceKey}::${line.name}",
                    sourceName = variant.sourceName,
                    lineName = line.name,
                    episodeName = episode.name,
                    url = episode.url,
                    episodeCount = line.episodes.size,
                    userAgent = variant.userAgent,
                    referer = variant.referer,
                    headers = variant.headers,
                )
            }
        }
    }

    private fun changeEpisode(index: Int) {
        if (candidatesFor(index).isEmpty()) {
            _ui.update { it.copy(hint = if (index > episodeIndex) "已经是最后一集" else "已经是第一集") }
            return
        }
        countdown = null
        episodeIndex = index
        failed.clear()
        introApplied = false
        suppressEnd = true
        outroFired = true
        val list = candidates()
        val same = list.firstOrNull { it.id == current?.id } ?: list.first()
        play(same, 0)
        _ui.update { it.copy(countdown = null) }
        showMain()
    }

    private fun play(candidate: Candidate, resumeMs: Long) {
        val target = host ?: return
        val token = ++playToken
        if (target.engine != kernel || target.software != software) {
            target.setEngine(kernel, software)
        }
        fallback?.reset()
        current = candidate
        introApplied = false
        viewModelScope.launch {
            val url = resolve(candidate)
            if (token != playToken) return@launch
            withContext(Dispatchers.Main) {
                if (token != playToken) return@withContext
                target.setAspect(aspect)
                target.play(
                    StreamOpen(
                        url = url,
                        userAgent = candidate.userAgent,
                        referer = candidate.referer,
                        headers = candidate.headers,
                        resumeMs = resumeMs,
                        speed = speed,
                    ),
                )
            }
            lastMove = System.currentTimeMillis()
            lastPos = resumeMs
            bufferingSince = 0
            publish()
            saveHistory()
        }
    }

    private suspend fun resolve(candidate: Candidate): String {
        resolved[candidate.id]?.let { return it }
        val url = resolvePlayUrl(candidate.url, parses, app.http)
        resolved[candidate.id] = url
        return url
    }

    private suspend fun probeAll(list: List<Candidate>): List<LineProbe> = coroutineScope {
        list.map { candidate ->
            async(Dispatchers.IO) {
                val url = runCatching { resolve(candidate) }.getOrDefault(candidate.url)
                val measure = app.http.probe(url)
                LineProbe(candidate.id, measure.connectMs, measure.firstByteMs, measure.resolutionHeight, measure.ok)
            }
        }.awaitAll()
    }

    private fun applyProbes(results: List<LineProbe>) {
        results.forEach { probes[it.id] = it }
        val ordered = rankLines(results).map { it.id }
        val rest = candidates().map { it.id }.filter { it !in ordered }
        ranked = ordered + rest
        publish()
    }

    private fun failover(reason: String) {
        if (failoverJob?.isActive == true) return
        failoverJob = viewModelScope.launch {
            val currentId = current?.id
            if (currentId != null) failed += currentId
            val list = candidates()
            val next = ranked.firstNotNullOfOrNull { id ->
                if (id in failed) null else list.firstOrNull { it.id == id }
            } ?: list.firstOrNull { it.id !in failed }
            if (next == null) {
                _ui.update { it.copy(error = "所有线路都无法播放", buffering = false, hint = null, playing = false) }
                return@launch
            }
            _ui.update { it.copy(error = null, hint = "$reason，正在切换到「${next.lineName}」") }
            play(next, 0)
        }
    }

    private fun maybeIntro() {
        val target = host ?: return
        if (introApplied || introMs <= 0) return
        val duration = target.durationMs
        if (target.positionMs < 2_000 && duration > introMs + 5_000) {
            target.seekTo(introMs)
            introApplied = true
            lastPos = introMs
            lastMove = System.currentTimeMillis()
        }
    }

    private fun finishEpisode() {
        if (outroFired) return
        if (candidatesFor(episodeIndex + 1).isEmpty()) {
            _ui.update { it.copy(hint = "播放结束", playing = false, countdown = null) }
            countdown = null
            return
        }
        countdown = 5
        countdownAt = System.currentTimeMillis() + 1_000
        _ui.update { it.copy(countdown = 5, panel = PlayerPanel.Main, hint = null) }
    }

    private fun startTicker() {
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                val target = host ?: continue
                val position = target.positionMs
                val duration = target.durationMs
                if (target.isPlaying && position > lastPos + 400) {
                    lastPos = position
                    lastMove = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                if (target.hasFirstFrame) maybeIntro()
                if (target.hasFirstFrame && target.isBuffering) {
                    if (bufferingSince == 0L) bufferingSince = now
                    if (now - bufferingSince > 12_000) failover("缓冲超时")
                } else {
                    bufferingSince = 0
                }
                if (target.hasFirstFrame && target.isPlaying && position > 0 && now - lastMove > 15_000) {
                    failover("播放停滞")
                }
                if (!outroFired && !suppressEnd && outroMs > 0 && duration > outroMs + 5_000 && duration - position in 1..outroMs) {
                    outroFired = true
                    countdown = null
                    changeEpisode(episodeIndex + 1)
                }
                val left = countdown
                if (left != null && now >= countdownAt) {
                    if (left <= 1) {
                        countdown = null
                        changeEpisode(episodeIndex + 1)
                    } else {
                        countdown = left - 1
                        countdownAt = now + 1_000
                        _ui.update { it.copy(countdown = countdown) }
                    }
                }
                _ui.update {
                    it.copy(
                        positionMs = position,
                        durationMs = duration,
                        buffering = target.isBuffering || !target.hasFirstFrame && it.error == null,
                        playing = target.isPlaying,
                        engine = target.engine.wire(),
                    )
                }
                ticks += 1
                if (ticks % 10 == 0) saveHistory()
            }
        }
    }

    private fun publish() {
        val list = candidates()
        val selected = current
        _ui.update {
            it.copy(
                empty = false,
                title = item?.title.orEmpty(),
                episodeName = selected?.episodeName ?: list.firstOrNull()?.episodeName.orEmpty(),
                episodeIndex = episodeIndex,
                canPrev = candidatesFor(episodeIndex - 1).isNotEmpty(),
                canNext = candidatesFor(episodeIndex + 1).isNotEmpty(),
                lineLabel = selected?.let { line -> "${line.sourceName} · ${line.lineName}" }.orEmpty(),
                selectedLineId = selected?.id.orEmpty(),
                lines = list.map { candidate ->
                    val probe = probes[candidate.id]
                    val detail = when {
                        probe == null -> null
                        !probe.ok -> "不可用"
                        else -> buildString {
                            append("${probe.connectMs + probe.firstByteMs} 毫秒")
                            probe.resolutionHeight?.let { height -> append(" · ${height}p") }
                        }
                    }
                    LineOption(candidate.id, "${candidate.sourceName} · ${candidate.lineName}", detail)
                },
                speed = speed,
                aspect = aspect,
                engine = host?.engine?.wire() ?: kernel.wire(),
                introSec = (introMs / 1000).toInt(),
                outroSec = (outroMs / 1000).toInt(),
            )
        }
    }

    private fun poke() {
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(6_000)
            if (_ui.value.panel == PlayerPanel.Main && countdown == null) hide()
        }
    }

    private suspend fun saveHistory() {
        val currentItem = item ?: return
        val candidate = current ?: return
        val position = host?.positionMs ?: _ui.value.positionMs
        if (position < 1_000) return
        app.library.saveHistory(
            item = currentItem,
            episodeIndex = episodeIndex,
            episodeName = candidate.episodeName,
            positionMs = position,
            durationMs = host?.durationMs ?: 0,
            lineId = candidate.id,
        )
    }

    override fun onCleared() {
        runBlocking(Dispatchers.IO) { runCatching { saveHistory() } }
        host?.release()
        host = null
        super.onCleared()
    }
}

internal suspend fun resolvePlayUrl(raw: String, parses: List<ParseDef>, http: NetClient): String {
    val url = raw.trim()
    if (isDirectMediaUrl(url)) return url
    for (parse in parses) {
        if (!parse.supported) continue
        val request = parse.url + urlEncode(url)
        val body = runCatching { http.text(request) }.getOrNull() ?: continue
        extractMediaUrl(body)?.let { return it }
    }
    return url
}
