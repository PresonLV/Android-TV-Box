package app.jianxia.tv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.SeekParameters
import app.jianxia.tv.data.net.Ua

object Players {
    fun create(context: Context, software: Boolean): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Ua.VALUE)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        val renderers = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setMediaCodecSelector { mime, secure, tunnel ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
                if (!software) all else all.filter { it.softwareOnly }.ifEmpty { all }
            }
        return ExoPlayer.Builder(context.applicationContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                playWhenReady = true
            }
    }
}
