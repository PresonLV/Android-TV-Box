package app.jianxia.tv

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import app.jianxia.core.model.MergedVod
import app.jianxia.core.pinyin.PinyinIme
import app.jianxia.tv.data.db.AppDatabase
import app.jianxia.tv.data.lan.LanServer
import app.jianxia.tv.data.net.NetClient
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class JianXiaApp : Application() {
    lateinit var container: AppContainer
        private set
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashStore.install(this)
        installImageLoader(this)
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.lan.observer)
        jobs.launch {
            runCatching { container.sources.ensurePublicChannels(container.settings) }
        }
        jobs.launch {
            container.settings.state.collect { container.spiders.setEnabled(it.spiderEnabled) }
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
    val catalog = CatalogRepository(http, sources, CatalogStore(), { settings.state.value.spiderEnabled }, spiders)
    val live = LiveRepository(http)
    val douban = DoubanRepository(http, context.cacheDir)
    val danmaku = DanmakuClient(http)
    val subtitles = SubtitleStore(context.filesDir)
    val hls = app.jianxia.tv.player.HlsRewriteProxy(http.http)
    val backup = BackupRepository(settings, sources)
    val lan = LanServer(context, sources, backup, settings, subtitles)
    val session = PlaybackSession()

    init {
        catalog.registry().register(SpiderCatalogFactory(spiders))
    }
    val pinyin: PinyinIme = PinyinIme.loadDefault()
}

class PlaybackSession {
    var request: PlayRequest? = null
    var pendingSearch: String? = null
}

data class PlayRequest(
    val item: MergedVod,
    val episodeIndex: Int,
    val resumeMs: Long,
    val preferredLineId: String?,
)
