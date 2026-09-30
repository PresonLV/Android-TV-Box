package app.jianxia.tv

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import app.jianxia.core.model.MergedVod
import app.jianxia.core.pinyin.PinyinIme
import app.jianxia.tv.data.db.AppDatabase
import app.jianxia.tv.data.lan.LanServer
import app.jianxia.tv.data.net.NetClient
import app.jianxia.tv.data.net.ResilientDns
import app.jianxia.tv.data.repo.BackupRepository
import app.jianxia.tv.data.repo.CatalogRepository
import app.jianxia.tv.data.repo.CatalogStore
import app.jianxia.tv.data.repo.DanmakuClient
import app.jianxia.tv.data.repo.DoubanRepository
import app.jianxia.tv.data.repo.LibraryRepository
import app.jianxia.tv.data.repo.LiveRepository
import app.jianxia.tv.data.repo.SettingsRepository
import app.jianxia.tv.data.repo.SourceRepository
import app.jianxia.core.spider.spiderBudgetMs
import app.jianxia.tv.data.repo.SubtitleStore
import app.jianxia.tv.data.source.SpiderCatalogFactory
import app.jianxia.tv.spider.SpiderHub
import app.jianxia.tv.ui.installImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class JianXiaApp : Application() {
    lateinit var container: AppContainer
        private set
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashStore.install(this)
        runCatching { installImageLoader(this) }
        container = AppContainer(this)
        jobs.launch {
            container.pinyin = runCatching { PinyinIme.loadDefault() }.getOrElse { PinyinIme("") }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.lan.observer)
        jobs.launch {
            runCatching { container.sources.ensurePublicChannels(container.settings) }
        }
        jobs.launch {
            app.jianxia.tv.spider.DriveCookies.onChanged = { container.spiders.dropSessions() }
            container.settings.state.collect {
                runCatching {
                    container.spiders.setEnabled(it.spiderEnabled && !CrashStore.safeMode)
                    ResilientDns.mode = it.safeDns
                    app.jianxia.tv.spider.DriveCookies.apply(this@JianXiaApp, it)
                }
            }
        }
    }
}

class AppContainer(context: Application) {
    val http = NetClient()
    val database = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val sources = SourceRepository(database.sources(), http)
    val library = LibraryRepository(database.library())
    val spiders = SpiderHub(context, http) { spiderBudgetMs(settings.state.value.searchTimeoutSec) }
    val catalog = CatalogRepository(
        http,
        sources,
        CatalogStore(),
        { settings.state.value.spiderEnabled },
        spiders,
        context.cacheDir.resolve("browse"),
        { CrashStore.safeMode },
    )
    val live = LiveRepository(http)
    val douban = DoubanRepository(http, context.cacheDir)
    val danmaku = DanmakuClient(http)
    val subtitles = SubtitleStore(context.filesDir)
    val hls = app.jianxia.tv.player.HlsRewriteProxy(http.http)
    val backup = BackupRepository(settings, sources)
    val session = PlaybackSession()
    val lan = LanServer(context, sources, backup, settings, subtitles, session)

    init {
        catalog.registry().register(SpiderCatalogFactory(spiders))
    }
    @Volatile
    var pinyin: PinyinIme = PinyinIme("")
}

sealed class RemoteCommand {
    data class Play(val url: String) : RemoteCommand()
    data class Search(val query: String) : RemoteCommand()
}

class PlaybackSession {
    var request: PlayRequest? = null
    var pendingSearch: String? = null
    private val _tick = MutableStateFlow(0)
    val tick = _tick
    val remote = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 8)

    fun pushSearch(query: String) {
        pendingSearch = query
        _tick.value += 1
        remote.tryEmit(RemoteCommand.Search(query))
    }

    fun pushPlay(url: String) {
        remote.tryEmit(RemoteCommand.Play(url))
    }
}

data class PlayRequest(
    val item: MergedVod,
    val episodeIndex: Int,
    val resumeMs: Long,
    val preferredLineId: String?,
)
