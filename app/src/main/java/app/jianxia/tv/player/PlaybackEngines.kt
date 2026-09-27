package app.jianxia.tv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.jianxia.tv.data.net.Ua
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.ArrayList

data class EmbeddedTrack(val id: String, val label: String)

enum class EngineId {
    Vlc,
    Exo,
}

fun EngineId.other(): EngineId = if (this == EngineId.Vlc) EngineId.Exo else EngineId.Vlc

fun EngineId.label(): String = if (this == EngineId.Vlc) "VLC" else "系统 (ExoPlayer)"

fun EngineId.wire(): String = if (this == EngineId.Vlc) "vlc" else "exo"

fun parseEngine(value: String): EngineId = if (value == "exo") EngineId.Exo else EngineId.Vlc

data class StreamOpen(
    val url: String,
    val userAgent: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
    val resumeMs: Long = 0,
    val speed: Float = 1f,
) {
    fun requestHeaders(): Pair<String, Map<String, String>> {
        val agent = userAgent.ifBlank {
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value.orEmpty()
        }.ifBlank { Ua.MEDIA }
        val map = linkedMapOf<String, String>()
        if (referer.isNotBlank()) map["Referer"] = referer
        headers.forEach { (key, value) ->
            if (value.isBlank() || key.equals("User-Agent", true)) return@forEach
            map[key] = value
        }
        return agent to map
    }
}

/** 当前内核失败时，换另一个内核再试一次。 */
class EngineFallback(private val host: PlaybackHost) {
    private var tried = false

    fun reset() {
        tried = false
    }

    fun onFailed(): Boolean {
        val open = host.currentOpen ?: return false
        if (tried) return false
        tried = true
        val resume = host.positionMs.coerceAtLeast(open.resumeMs)
        host.setEngine(host.engine.other(), host.software)
        host.play(open.copy(resumeMs = resume, speed = host.speed))
        return true
    }
}

@OptIn(UnstableApi::class)
class PlaybackHost(context: Context) {
    interface Listener {
        fun onReady() {}
        fun onFirstFrame() {}
        fun onEnded() {}
        fun onFailure(message: String) {}
        fun onState(playing: Boolean, buffering: Boolean) {}
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val headerSource = HeaderSource()
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(appContext).build()
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var exo: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var vlcLayout: VLCVideoLayout? = null
    private var container: FrameLayout? = null
    private var mounted = false
    private var generation = 0
    private var failedNotified = false
    private var eventsOpen = false
    private var aspect = "fit"
    private var vlcBuffering = false
    private val stall = Runnable {
        if (!hasFirstFrame) fail("长时间没有画面")
    }

    var engine: EngineId = EngineId.Vlc
        private set
    var software: Boolean = false
        private set
    var speed: Float = 1f
        private set
    var listener: Listener? = null
    var currentOpen: StreamOpen? = null
        private set
    var hasFirstFrame: Boolean = false
        private set

    val positionMs: Long
        get() = when (engine) {
            EngineId.Exo -> exo?.currentPosition ?: 0L
            EngineId.Vlc -> mediaPlayer?.time ?: 0L
        }

    val durationMs: Long
        get() = when (engine) {
            EngineId.Exo -> exo?.duration?.coerceAtLeast(0) ?: 0L
            EngineId.Vlc -> mediaPlayer?.length?.coerceAtLeast(0) ?: 0L
        }

    val isPlaying: Boolean
        get() = when (engine) {
            EngineId.Exo -> exo?.isPlaying == true
            EngineId.Vlc -> mediaPlayer?.isPlaying == true
        }

    val isBuffering: Boolean
        get() = when (engine) {
            EngineId.Exo -> exo?.playbackState == Player.STATE_BUFFERING
            EngineId.Vlc -> vlcBuffering
        }

    fun bind(target: FrameLayout) {
        if (container === target && mounted) return
        container = target
        mounted = false
        mount()
    }

    fun setEngine(next: EngineId, useSoftware: Boolean) {
        if (next == engine && useSoftware == software && playerReady()) return
        engine = next
        software = useSoftware
        releasePlayers()
        ensurePlayer()
        mount()
    }

    fun bitrateLabel(): String {
        val bits = bandwidthMeter.bitrateEstimate
        if (bits <= 0L || bits == Long.MAX_VALUE) return ""
        return if (bits >= 1_000_000) String.format("%.1f MB/s", bits / 1_000_000.0) else "${bits / 1000} KB/s"
    }

    fun play(open: StreamOpen) {
        headerSource.listener = bandwidthMeter
        currentOpen = open
        speed = open.speed
        hasFirstFrame = false
        failedNotified = false
        vlcBuffering = true
        eventsOpen = true
        generation += 1
        val token = generation
        handler.removeCallbacks(stall)
        try {
            ensurePlayer()
            mount()
            when (engine) {
                EngineId.Exo -> startExo(open)
                EngineId.Vlc -> startVlc(open)
            }
        } catch (error: Throwable) {
            if (engine == EngineId.Vlc) {
                engine = EngineId.Exo
                releasePlayers()
                runCatching {
                    ensurePlayer()
                    mount()
                    startExo(open)
                }.onFailure {
                    fail(it.message ?: "播放器启动失败")
                    return
                }
            } else {
                fail(error.message ?: "播放器启动失败")
                return
            }
        }
        applySpeed()
        applyAspect()
        handler.postDelayed(stall, FIRST_FRAME_TIMEOUT_MS)
        if (token != generation) return
    }

    fun toggle() {
        if (isPlaying) pause() else resumePlayback()
    }

    fun pause() {
        when (engine) {
            EngineId.Exo -> exo?.pause()
            EngineId.Vlc -> mediaPlayer?.pause()
        }
    }

    fun resumePlayback() {
        when (engine) {
            EngineId.Exo -> exo?.play()
            EngineId.Vlc -> mediaPlayer?.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        when (engine) {
            EngineId.Exo -> exo?.seekTo(target)
            EngineId.Vlc -> mediaPlayer?.setTime(target)
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((positionMs + deltaMs).coerceIn(0L, duration))
    }

    fun setSpeed(value: Float) {
        speed = value
        applySpeed()
    }

    fun embeddedTracks(): List<EmbeddedTrack> = when (engine) {
        EngineId.Exo -> {
            val player = exo ?: return emptyList()
            buildList {
                player.currentTracks.groups.forEachIndexed { groupIndex, group ->
                    if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                    for (index in 0 until group.length) {
                        val format = group.getTrackFormat(index)
                        val label = format.label ?: format.language ?: "内嵌字幕 ${size + 1}"
                        add(EmbeddedTrack("exo:$groupIndex:$index", label))
                    }
                }
            }
        }
        EngineId.Vlc -> mediaPlayer?.spuTracks.orEmpty()
            .filter { it.id >= 0 }
            .map { EmbeddedTrack("vlc:${it.id}", it.name ?: "内嵌字幕") }
    }

    fun selectEmbedded(id: String?) {
        when (engine) {
            EngineId.Exo -> {
                val player = exo ?: return
                val builder = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, id == null)
                if (id != null && id.startsWith("exo:")) {
                    val parts = id.split(":")
                    val group = parts.getOrNull(1)?.toIntOrNull()?.let { player.currentTracks.groups.getOrNull(it) }
                    val track = parts.getOrNull(2)?.toIntOrNull()
                    if (group != null && track != null) {
                        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(track)))
                    }
                }
                player.trackSelectionParameters = builder.build()
            }
            EngineId.Vlc -> {
                val player = mediaPlayer ?: return
                val track = id?.removePrefix("vlc:")?.toIntOrNull()
                player.setSpuTrack(track ?: -1)
            }
        }
    }

    fun setSubtitleOffset(ms: Long) {
        if (engine == EngineId.Vlc) mediaPlayer?.setSpuDelay(ms * 1_000)
    }

    fun attachExternalSubtitle(path: String): Boolean {
        if (engine != EngineId.Vlc) return false
        val player = mediaPlayer ?: return false
        val location = if (path.startsWith("http://") || path.startsWith("https://")) path else Uri.fromFile(File(path)).toString()
        return player.addSlave(0, location, true)
    }

    fun setAspect(value: String) {
        aspect = value
        applyAspect()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        releasePlayers()
        libVlc?.release()
        libVlc = null
        container?.removeAllViews()
        container = null
        mounted = false
        listener = null
    }

    private fun playerReady(): Boolean = when (engine) {
        EngineId.Exo -> exo != null
        EngineId.Vlc -> mediaPlayer != null
    }

    private fun ensurePlayer() {
        when (engine) {
            EngineId.Exo -> if (exo == null) exo = createExo()
            EngineId.Vlc -> if (mediaPlayer == null) mediaPlayer = MediaPlayer(vlc())
        }
    }

    private fun vlc(): LibVLC {
        libVlc?.let { return it }
        val options = ArrayList<String>()
        options += "--network-caching=2000"
        options += "--audio-time-stretch"
        options += "--no-drop-late-frames"
        val created = try {
            LibVLC(appContext, options)
        } catch (error: Throwable) {
            throw IllegalStateException("VLC 无法启动", error)
        }
        libVlc = created
        return created
    }

    private fun createExo(): ExoPlayer {
        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setMediaCodecSelector { mime, secure, tunnel ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
                if (!software) all else all.filter { it.softwareOnly }.ifEmpty { all }
            }
        val player = ExoPlayer.Builder(appContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(headerSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (engine != EngineId.Exo) return
                fail("播放失败")
            }

            override fun onRenderedFirstFrame() {
                if (engine == EngineId.Exo) markFirstFrame()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!eventsOpen || engine != EngineId.Exo) return
                if (playbackState == Player.STATE_ENDED) listener?.onEnded()
                if (playbackState == Player.STATE_READY) listener?.onReady()
                listener?.onState(player.isPlaying, playbackState == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!eventsOpen || engine != EngineId.Exo) return
                listener?.onState(isPlaying, player.playbackState == Player.STATE_BUFFERING)
            }
        })
        return player
    }

    private fun startExo(open: StreamOpen) {
        val player = exo ?: return
        val (agent, headers) = open.requestHeaders()
        headerSource.userAgent = agent
        headerSource.headers = headers
        player.setMediaItem(MediaItem.fromUri(open.url))
        player.prepare()
        player.playWhenReady = true
        if (open.resumeMs > 3_000) player.seekTo(open.resumeMs)
    }

    private fun startVlc(open: StreamOpen) {
        val player = mediaPlayer ?: return
        val (agent, headers) = open.requestHeaders()
        val media = Media(vlc(), Uri.parse(open.url))
        media.setHWDecoderEnabled(!software, false)
        media.addOption(":http-user-agent=$agent")
        headers["Referer"]?.let { media.addOption(":http-referrer=$it") }
        headers.forEach { (key, value) ->
            if (key.equals("Referer", true) || key.equals("Referrer", true)) return@forEach
            media.addOption(":http-header=$key: $value")
        }
        if (software) {
            media.addOption(":no-mediacodec")
            media.addOption(":avcodec-hw=none")
        }
        if (open.resumeMs > 3_000) media.addOption(":start-time=${open.resumeMs / 1000}")
        player.media = media
        media.release()
        val token = generation
        player.setEventListener { event ->
            handler.post {
                if (token != generation) return@post
                when (event.type) {
                    MediaPlayer.Event.EncounteredError -> fail("播放失败")
                    MediaPlayer.Event.EndReached -> listener?.onEnded()
                    MediaPlayer.Event.Vout -> if (event.voutCount > 0) markFirstFrame()
                    MediaPlayer.Event.Playing -> {
                        vlcBuffering = false
                        listener?.onReady()
                        listener?.onState(true, false)
                    }
                    MediaPlayer.Event.Buffering -> {
                        vlcBuffering = event.buffering < 100f
                        listener?.onState(player.isPlaying, vlcBuffering)
                    }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                        vlcBuffering = false
                        listener?.onState(false, false)
                    }
                }
            }
        }
        vlcLayout?.let { player.attachViews(it, null, false, false) }
        player.play()
        player.setSpuTrack(-1)
    }

    private fun mount() {
        val parent = container ?: return
        parent.removeAllViews()
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        when (engine) {
            EngineId.Exo -> {
                val view = PlayerView(appContext).apply {
                    useController = false
                    player = exo
                    setShutterBackgroundColor(Color.BLACK)
                    resizeMode = resizeMode()
                }
                parent.addView(view, params)
                playerView = view
                vlcLayout = null
            }
            EngineId.Vlc -> {
                val view = VLCVideoLayout(appContext)
                parent.addView(view, params)
                vlcLayout = view
                playerView = null
                mediaPlayer?.attachViews(view, null, false, false)
            }
        }
        mounted = true
        applyAspect()
    }

    private fun applySpeed() {
        when (engine) {
            EngineId.Exo -> exo?.setPlaybackSpeed(speed)
            EngineId.Vlc -> mediaPlayer?.setRate(speed)
        }
    }

    private fun applyAspect() {
        when (engine) {
            EngineId.Exo -> playerView?.resizeMode = resizeMode()
            EngineId.Vlc -> {
                val player = mediaPlayer ?: return
                when (aspect) {
                    "16:9" -> {
                        player.setAspectRatio("16:9")
                        player.setScale(0f)
                    }
                    "4:3" -> {
                        player.setAspectRatio("4:3")
                        player.setScale(0f)
                    }
                    "zoom" -> {
                        player.setAspectRatio(null)
                        player.setScale(1.35f)
                    }
                    "fill" -> {
                        player.setAspectRatio(null)
                        player.setScale(1.8f)
                    }
                    else -> {
                        player.setAspectRatio(null)
                        player.setScale(0f)
                    }
                }
            }
        }
    }

    private fun resizeMode(): Int = when (aspect) {
        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        "zoom", "16:9", "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun markFirstFrame() {
        if (!eventsOpen || hasFirstFrame) return
        hasFirstFrame = true
        handler.removeCallbacks(stall)
        listener?.onFirstFrame()
    }

    private fun fail(message: String) {
        if (!eventsOpen || failedNotified) return
        failedNotified = true
        handler.removeCallbacks(stall)
        listener?.onFailure(message)
    }

    private fun releasePlayers() {
        eventsOpen = false
        vlcBuffering = false
        generation += 1
        handler.removeCallbacks(stall)
        exo?.release()
        exo = null
        playerView = null
        mediaPlayer?.let { player ->
            player.setEventListener(null)
            runCatching { player.detachViews() }
            player.release()
        }
        mediaPlayer = null
        vlcLayout = null
        mounted = false
    }

    private class HeaderSource : androidx.media3.datasource.DataSource.Factory {
        @Volatile var userAgent: String = Ua.MEDIA
        @Volatile var headers: Map<String, String> = emptyMap()

        @Volatile var listener: androidx.media3.datasource.TransferListener? = null

        override fun createDataSource(): androidx.media3.datasource.DataSource =
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000)
                .setTransferListener(listener)
                .createDataSource()
    }

    companion object {
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
    }
}

fun openExternalPlayer(
    context: Context,
    url: String,
    title: String,
    userAgent: String,
    referer: String,
    headers: Map<String, String>,
): String? {
    val lines = mutableListOf<String>()
    if (userAgent.isNotBlank()) lines += "User-Agent: $userAgent"
    if (referer.isNotBlank()) lines += "Referer: $referer"
    headers.forEach { (key, value) ->
        if (value.isBlank() || key.equals("User-Agent", true) || key.equals("Referer", true)) return@forEach
        lines += "$key: $value"
    }
    val bundle = Bundle()
    if (userAgent.isNotBlank()) bundle.putString("User-Agent", userAgent)
    if (referer.isNotBlank()) bundle.putString("Referer", referer)
    headers.forEach { (key, value) -> if (value.isNotBlank()) bundle.putString(key, value) }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        putExtra("title", title)
        if (lines.isNotEmpty()) putExtra("headers", lines.toTypedArray())
        putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
    }
    val chooser = Intent.createChooser(view, "用外部播放器打开").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        null
    } catch (_: ActivityNotFoundException) {
        "没有找到外部播放器"
    }
}
