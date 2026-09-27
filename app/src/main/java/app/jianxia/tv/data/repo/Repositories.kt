package app.jianxia.tv.data.repo

import android.content.Context
import app.jianxia.core.UserFacingError
import app.jianxia.core.aggregate.ParallelAggregator
import app.jianxia.core.backup.BackupCodec
import app.jianxia.core.backup.UrlList
import app.jianxia.core.backup.withRecentSearch
import app.jianxia.core.merge.CategoryMatcher
import app.jianxia.core.merge.mergeVodItems
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.BackupBundle
import app.jianxia.core.model.BackupSource
import app.jianxia.core.model.EpgGuide
import app.jianxia.core.model.LiveChannel
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.ParseDef
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.parser.DetectedSource
import app.jianxia.core.parser.M3uParser
import app.jianxia.core.parser.SourceDetector
import app.jianxia.core.parser.TvBoxConfigParser
import app.jianxia.core.parser.TxtLiveParser
import app.jianxia.core.parser.XmlTvParser
import app.jianxia.tv.data.db.FavoriteEntity
import app.jianxia.tv.data.db.HistoryEntity
import app.jianxia.tv.data.db.LibraryDao
import app.jianxia.tv.data.db.LineChoiceEntity
import app.jianxia.tv.data.db.SkipEntity
import app.jianxia.tv.data.db.SourceDao
import app.jianxia.tv.data.db.SourceEntity
import app.jianxia.tv.data.net.NetClient
import app.jianxia.tv.data.source.CatalogRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SettingsRepository(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        mutex.withLock {
            val next = block(_state.value).sanitized()
            persist(next)
        }
    }

    suspend fun write(settings: AppSettings) {
        mutex.withLock { persist(settings.sanitized()) }
    }

    private fun persist(settings: AppSettings) {
        _state.value = settings
        file.writeText(BackupCodec.json.encodeToString(AppSettings.serializer(), settings))
    }

    private fun read(): AppSettings {
        if (!file.exists()) return AppSettings()
        return runCatching {
            BackupCodec.json.decodeFromString(AppSettings.serializer(), file.readText()).sanitized()
        }.getOrDefault(AppSettings())
    }
}

data class SourceChange(val source: SourceEntity, val summary: String)

class SourceRepository(private val dao: SourceDao, private val http: NetClient) {
    fun observe(): Flow<List<SourceEntity>> = dao.observe()

    suspend fun list(): List<SourceEntity> = dao.list()

    suspend fun add(url: String, name: String?, epg: String?): SourceChange = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        requireHttp(trimmed)
        val placeholder = SourceEntity(
            id = UUID.randomUUID().toString(),
            name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: hostOf(trimmed),
            url = trimmed,
            kind = "failed",
            epgUrl = epg?.trim()?.ifBlank { null },
            enabled = true,
            sortOrder = dao.maxOrder() + 1,
            addedAt = System.currentTimeMillis(),
            note = UserFacingError.RETRY,
        )
        dao.upsert(placeholder)
        finish(placeholder, name)
    }

    suspend fun update(id: String, name: String, url: String, epg: String?) = withContext(Dispatchers.IO) {
        val current = dao.list().firstOrNull { it.id == id } ?: error("找不到这个接口")
        val trimmed = url.trim()
        requireHttp(trimmed)
        val placeholder = current.copy(
            name = name.trim().ifBlank { current.name },
            url = trimmed,
            kind = "failed",
            epgUrl = epg?.trim()?.ifBlank { null },
            note = UserFacingError.RETRY,
        )
        dao.upsert(placeholder)
        finish(placeholder, name).summary
    }

    suspend fun recheck(id: String): String = withContext(Dispatchers.IO) {
        val current = dao.list().firstOrNull { it.id == id } ?: error("找不到这个接口")
        finish(current, current.name).summary
    }

    private suspend fun finish(entity: SourceEntity, name: String?): SourceChange {
        val fetched = runCatching { http.fetchConfig(entity.url) }
        if (fetched.isFailure) {
            val reason = UserFacingError.message(fetched.exceptionOrNull() ?: IllegalStateException(UserFacingError.RETRY))
            val saved = entity.copy(
                name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: entity.name.ifBlank { hostOf(entity.url) },
                kind = "failed",
                note = UserFacingError.RETRY,
            )
            dao.upsert(saved)
            return SourceChange(saved, "${UserFacingError.RETRY}：$reason")
        }
        val document = fetched.getOrThrow()
        val detected = SourceDetector.detect(document.text, document.finalUrl)
        if (detected is DetectedSource.Unknown) {
            val saved = entity.copy(
                name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: hostOf(entity.url),
                kind = "failed",
                note = UserFacingError.RETRY,
            )
            dao.upsert(saved)
            return SourceChange(saved, "${UserFacingError.RETRY}：${detected.reason}")
        }
        val saved = entity.copy(
            name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: detected.suggestedName(),
            kind = kindOf(detected),
            note = "",
        )
        dao.upsert(saved)
        return SourceChange(saved, summaryOf(detected, document.text, document.finalUrl))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val current = dao.list().firstOrNull { it.id == id } ?: return
        dao.upsert(current.copy(enabled = enabled))
    }

    suspend fun move(id: String, up: Boolean) {
        val items = dao.list().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in items.indices) return
        val current = items[index]
        items[index] = items[target]
        items[target] = current
        items.forEachIndexed { order, item -> dao.upsert(item.copy(sortOrder = order)) }
    }

    suspend fun replaceAll(sources: List<BackupSource>) {
        dao.deleteAll()
        sources.forEachIndexed { index, source ->
            dao.upsert(
                SourceEntity(
                    id = UUID.randomUUID().toString(),
                    name = source.name,
                    url = source.url,
                    kind = source.kind.ifBlank { "tvbox" },
                    epgUrl = source.epgUrl.ifBlank { null },
                    enabled = source.enabled,
                    sortOrder = index,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun requireHttp(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("请输入以 http:// 或 https:// 开头的地址")
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore(":").ifBlank { "未命名" }

    private fun kindOf(detected: DetectedSource): String = when (detected) {
        is DetectedSource.TvBox -> "tvbox"
        is DetectedSource.MacCmsJson -> "maccms_json"
        is DetectedSource.MacCmsXml -> "maccms_xml"
        is DetectedSource.Live -> "live"
        is DetectedSource.Unknown -> "failed"
    }

    private fun summaryOf(detected: DetectedSource, text: String, baseUrl: String): String = when (detected) {
        is DetectedSource.TvBox -> {
            val config = runCatching { TvBoxConfigParser.parse(text, baseUrl) }.getOrNull()
            if (config == null) {
                "已添加 TVBox 配置"
            } else {
                val ok = config.sites.count { it.unsupportedReason == null }
                val bad = config.sites.count { it.unsupportedReason != null }
                "已添加。可用点播站 $ok 个，直播 ${config.lives.size} 组，不支持 $bad 个"
            }
        }
        is DetectedSource.MacCmsJson, is DetectedSource.MacCmsXml -> "已添加苹果 CMS 接口"
        is DetectedSource.Live -> "已添加直播，解析到 ${detected.channelCount} 个频道"
        is DetectedSource.Unknown -> detected.reason
    }
}

private fun DetectedSource.suggestedName(): String = when (this) {
    is DetectedSource.TvBox -> suggestedName
    is DetectedSource.MacCmsJson -> suggestedName
    is DetectedSource.MacCmsXml -> suggestedName
    is DetectedSource.Live -> suggestedName
    is DetectedSource.Unknown -> "未命名"
}

class LibraryRepository(private val dao: LibraryDao) {
    fun history(): Flow<List<HistoryEntity>> = dao.observeHistory()
    fun favorites(): Flow<List<FavoriteEntity>> = dao.observeFavorites()

    suspend fun isFavorite(key: String): Boolean = dao.favorite(key) != null

    suspend fun setFavorite(item: MergedVod, favorite: Boolean) {
        if (!favorite) {
            dao.deleteFavorite(item.key)
            return
        }
        dao.upsertFavorite(
            FavoriteEntity(
                titleKey = item.key,
                title = item.title,
                pic = item.pic,
                year = item.year,
                typeName = item.typeName,
                payload = encode(item),
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveHistory(
        item: MergedVod,
        episodeIndex: Int,
        episodeName: String,
        positionMs: Long,
        durationMs: Long,
        lineId: String,
    ) {
        dao.upsertHistory(
            HistoryEntity(
                titleKey = item.key,
                title = item.title,
                pic = item.pic,
                year = item.year,
                episodeName = episodeName,
                episodeIndex = episodeIndex,
                positionMs = positionMs,
                durationMs = durationMs,
                lineId = lineId,
                payload = encode(item),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteHistory(key: String) = dao.deleteHistory(key)
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun deleteFavorite(key: String) = dao.deleteFavorite(key)
    suspend fun clearFavorites() = dao.clearFavorites()

    suspend fun historyOf(key: String): HistoryEntity? = dao.history(key)
    suspend fun findMerged(key: String): MergedVod? {
        dao.history(key)?.payload?.let { return decode(it) }
        dao.favorite(key)?.payload?.let { return decode(it) }
        return null
    }

    suspend fun skip(key: String): SkipEntity? = dao.skip(key)
    suspend fun saveSkip(key: String, introMs: Long, outroMs: Long) {
        dao.upsertSkip(SkipEntity(key, introMs, outroMs))
    }

    suspend fun lineChoice(key: String): String? = dao.line(key)?.lineId
    suspend fun saveLine(key: String, lineId: String) {
        dao.upsertLine(LineChoiceEntity(key, lineId, System.currentTimeMillis()))
    }

    suspend fun clearLine(key: String) = dao.deleteLine(key)

    private fun encode(item: MergedVod): String =
        BackupCodec.json.encodeToString(MergedVod.serializer(), item)

    private fun decode(raw: String): MergedVod? =
        runCatching { BackupCodec.json.decodeFromString(MergedVod.serializer(), raw) }.getOrNull()
}

data class ResolvedLive(
    val originId: String,
    val name: String,
    val url: String,
    val epgUrl: String?,
)

data class ExpandedSources(
    val sites: List<VodSiteDef>,
    val lives: List<ResolvedLive>,
    val parses: List<ParseDef>,
    val unsupported: Int,
    val failures: Int,
)

data class HomeCatalog(
    val rows: Map<String, List<MergedVod>>,
    val unsupported: Int,
    val failed: Int,
    val hasVod: Boolean,
    val message: String?,
)

data class SearchCatalog(
    val items: List<MergedVod>,
    val message: String?,
)

class CatalogStore {
    private val map = object : LinkedHashMap<String, MergedVod>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MergedVod>?) = size > 400
    }

    fun put(item: MergedVod) {
        synchronized(map) { map[item.key] = item }
    }

    fun putAll(items: List<MergedVod>) = items.forEach { put(it) }

    fun get(key: String): MergedVod? = synchronized(map) { map[key] }
}

class CatalogRepository(
    private val http: NetClient,
    private val sources: SourceRepository,
    val store: CatalogStore,
) {
    private val registry = CatalogRegistry(http)
    private var cached: Pair<String, ExpandedSources>? = null
    @Volatile var lastHome: HomeCatalog? = null

    fun registry(): CatalogRegistry = registry

    suspend fun expand(force: Boolean = false): ExpandedSources = withContext(Dispatchers.IO) {
        val enabled = sources.list().filter { it.enabled }
        val fingerprint = enabled.joinToString("|") { "${it.id}:${it.url}:${it.epgUrl}:${it.kind}" }
        if (!force) cached?.takeIf { it.first == fingerprint }?.second?.let { return@withContext it }
        val sites = mutableListOf<VodSiteDef>()
        val lives = mutableListOf<ResolvedLive>()
        val parses = mutableListOf<ParseDef>()
        var unsupported = 0
        var failures = 0
        for (source in enabled) {
            try {
                when (source.kind) {
                    "tvbox" -> {
                        val document = http.fetchConfig(source.url)
                        val config = TvBoxConfigParser.parse(document.text, document.finalUrl)
                        config.sites.forEach { site ->
                            sites += site.copy(key = "${source.id}:${site.key}")
                            if (site.unsupportedReason != null) unsupported += 1
                        }
                        config.lives.forEach { live ->
                            lives += ResolvedLive(source.id, live.name, live.url, live.epgUrl)
                        }
                        parses += config.parses
                    }
                    "maccms_json", "maccms_xml" -> sites += VodSiteDef(
                        key = source.id,
                        name = source.name,
                        kind = if (source.kind == "maccms_xml") SiteKind.MACCMS_XML else SiteKind.MACCMS_JSON,
                        api = source.url,
                    )
                    "live" -> lives += ResolvedLive(source.id, source.name, source.url, source.epgUrl)
                    else -> failures += 1
                }
            } catch (_: Exception) {
                failures += 1
            }
        }
        ExpandedSources(sites, lives, parses, unsupported, failures).also { cached = fingerprint to it }
    }

    suspend fun home(settings: AppSettings): HomeCatalog {
        val expanded = expand()
        val vod = expanded.sites.filter { it.unsupportedReason == null && it.kind != SiteKind.UNSUPPORTED }
        val selected = settings.defaultSourceId.takeIf { it.isNotBlank() }?.let { id ->
            vod.filter { it.key == id || it.key.startsWith("$id:") }.ifEmpty { null }
        } ?: vod
        if (selected.isEmpty()) {
            return HomeCatalog(
                rows = emptyMap(),
                unsupported = expanded.unsupported,
                failed = expanded.failures,
                hasVod = false,
                message = if (expanded.failures > 0) UserFacingError.RETRY else null,
            ).also { lastHome = it }
        }
        val timeout = settings.searchTimeoutSec * 1000L
        val first = ParallelAggregator.collect(timeout, selected.map { site ->
            suspend { listOf(site.key to registry.create(site).list(1, null)) }
        })
        val pages = first.items.toMap()
        val rows = linkedMapOf(
            "latest" to mergeVodItems(pages.values.flatMap { it.items }).take(18),
        )
        var failed = expanded.failures + first.failureCount + first.timedOutCount
        for (row in listOf("movie", "tv", "variety", "anime", "doc")) {
            val blocks = selected.mapNotNull { site ->
                val page = pages[site.key] ?: return@mapNotNull null
                val typeId = page.classes.firstOrNull { CategoryMatcher.matches(row, it.name) }?.id
                    ?: return@mapNotNull null
                suspend { registry.create(site).list(1, typeId).items }
            }
            if (blocks.isEmpty()) continue
            val outcome = ParallelAggregator.collect(timeout, blocks)
            failed += outcome.failureCount + outcome.timedOutCount
            val merged = mergeVodItems(outcome.items).take(18)
            if (merged.isNotEmpty()) rows[row] = merged
        }
        rows.values.forEach { store.putAll(it) }
        val message = when {
            failed > 0 && rows.values.all { it.isEmpty() } -> "接口没有返回内容"
            failed > 0 -> "有 $failed 个请求超时或失败"
            expanded.unsupported > 0 -> "已跳过 ${expanded.unsupported} 个不支持的站点"
            else -> null
        }
        return HomeCatalog(rows, expanded.unsupported, failed, true, message).also { lastHome = it }
    }

    suspend fun search(settings: AppSettings, query: String): SearchCatalog {
        val expanded = expand()
        val sites = expanded.sites.filter { it.searchable && it.unsupportedReason == null && it.kind != SiteKind.UNSUPPORTED }
        if (sites.isEmpty()) return SearchCatalog(emptyList(), "没有可搜索的点播站")
        val outcome = ParallelAggregator.collect(settings.searchTimeoutSec * 1000L, sites.map { site ->
            suspend { registry.create(site).search(query).items }
        })
        val merged = mergeVodItems(outcome.items).take(60)
        store.putAll(merged)
        val missed = outcome.failureCount + outcome.timedOutCount
        val message = when {
            merged.isEmpty() && missed > 0 -> "来源没有及时响应"
            merged.isEmpty() -> "没有找到「$query」"
            missed > 0 -> "找到 ${merged.size} 条，另有 $missed 个来源没有响应"
            else -> "找到 ${merged.size} 条"
        }
        return SearchCatalog(merged, message)
    }

    suspend fun hydrate(item: MergedVod): MergedVod {
        val variants = coroutineScope {
            item.variants.map { variant ->
                async(Dispatchers.IO) {
                    if (variant.lines.isNotEmpty() || variant.id.isBlank()) variant
                    else registry.create(variant.toDef()).detail(variant.id) ?: variant
                }
            }.awaitAll()
        }
        return item.copy(variants = variants).also { store.put(it) }
    }

    suspend fun parses(): List<ParseDef> = expand().parses
}

private fun VodItem.toDef(): VodSiteDef = VodSiteDef(
    key = sourceKey,
    name = sourceName,
    kind = siteKind,
    api = api,
)

class LiveRepository(private val http: NetClient) {
    suspend fun channels(lives: List<ResolvedLive>): List<LiveChannel> = withContext(Dispatchers.IO) {
        val all = mutableListOf<LiveChannel>()
        for (live in lives) {
            val parsed = runCatching {
                val text = http.textBlocking(live.url, maxBytes = 4_000_000)
                val m3u = if (text.contains("#EXTM3U", ignoreCase = true)) M3uParser.parse(text) else emptyList()
                val channels = if (m3u.isNotEmpty() && !M3uParser.looksLikeSegments(m3u)) m3u else TxtLiveParser.parse(text)
                channels.map { channel ->
                    if (channel.group == "未分组" || channel.group == "默认") channel.copy(group = live.name) else channel
                }
            }.getOrDefault(emptyList())
            all += parsed
        }
        all
    }

    suspend fun guide(lives: List<ResolvedLive>): EpgGuide = withContext(Dispatchers.IO) {
        val guides = lives.mapNotNull { it.epgUrl }.distinct().mapNotNull { url ->
            runCatching { XmlTvParser.parse(http.textBlocking(url, maxBytes = 8_000_000)) }.getOrNull()
        }
        EpgGuide(
            displayNames = guides.fold(emptyMap()) { acc, guide -> acc + guide.displayNames },
            programmes = guides.flatMap { it.programmes },
        )
    }
}

class BackupRepository(
    private val settings: SettingsRepository,
    private val sources: SourceRepository,
) {
    suspend fun export(): String {
        val bundle = BackupBundle(
            settings = settings.state.value,
            sources = sources.list().map {
                BackupSource(it.name, it.url, it.kind, it.epgUrl.orEmpty(), it.enabled, it.sortOrder)
            },
        )
        return BackupCodec.encode(bundle)
    }

    suspend fun import(raw: String): String {
        val urls = UrlList.extract(raw)
        if (urls != null) {
            val notes = urls.map { url ->
                runCatching { sources.add(url, null, null).summary }.getOrElse { UserFacingError.message(it) }
            }
            return "已按网址添加 ${urls.size} 个，没有覆盖现有配置。${notes.joinToString("；")}"
        }
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException(UserFacingError.NOT_BACKUP)
        }
        val bundle = try {
            BackupCodec.decode(trimmed)
        } catch (error: Exception) {
            throw IllegalArgumentException(UserFacingError.message(error))
        }
        settings.write(bundle.settings)
        sources.replaceAll(bundle.sources)
        return "已导入并覆盖，共 ${bundle.sources.size} 个接口"
    }

    suspend fun rememberSearch(query: String) {
        settings.update { it.withRecentSearch(query) }
    }
}
