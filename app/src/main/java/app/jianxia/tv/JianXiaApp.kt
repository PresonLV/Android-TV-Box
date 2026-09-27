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
import app.jianxia.tv.data.repo.LibraryRepository
import app.jianxia.tv.data.repo.LiveRepository
import app.jianxia.tv.data.repo.SettingsRepository
import app.jianxia.tv.data.repo.SourceRepository

class JianXiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.lan.observer)
    }
}

class AppContainer(context: Application) {
    val http = NetClient()
    val database = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val sources = SourceRepository(database.sources(), http)
    val library = LibraryRepository(database.library())
    val catalog = CatalogRepository(http, sources, CatalogStore())
    val live = LiveRepository(http)
    val backup = BackupRepository(settings, sources)
    val lan = LanServer(context, sources, backup, settings)
    val session = PlaybackSession()
    val pinyin: PinyinIme = PinyinIme.loadDefault()
}

class PlaybackSession {
    var request: PlayRequest? = null
}

data class PlayRequest(
    val item: MergedVod,
    val episodeIndex: Int,
    val resumeMs: Long,
    val preferredLineId: String?,
)
