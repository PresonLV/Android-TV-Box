package app.jianxia.tv.data.repo

import app.jianxia.core.douban.DoubanCard
import app.jianxia.core.douban.DoubanCommentPage
import app.jianxia.core.douban.DoubanDetail
import app.jianxia.core.douban.DoubanParse
import app.jianxia.core.douban.DoubanProxy
import app.jianxia.core.model.AppSettings
import app.jianxia.tv.data.net.NetClient
import app.jianxia.tv.data.net.Ua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class DoubanRepository(private val http: NetClient, cacheDir: File) {
    private val dir = File(cacheDir, "douban").apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 80
    }

    suspend fun browse(settings: AppSettings, kind: String, tag: String, start: Int): List<DoubanCard> {
        if (!settings.doubanEnabled) return emptyList()
        val target = DoubanProxy.categoryUrl(kind, tag, start, 20)
        val body = read(settings, target, BROWSE_TTL) ?: return emptyList()
        return DoubanParse.cards(body).map { paint(settings, it) }
    }

    suspend fun filter(
        settings: AppSettings,
        kind: String,
        featured: String,
        yearRange: String,
        country: String,
        genre: String,
        sort: String,
        start: Int,
    ): List<DoubanCard> {
        if (!settings.doubanEnabled) return emptyList()
        val target = DoubanProxy.filterUrl(kind, featured, yearRange, country, genre, sort, start, 20)
        val body = read(settings, target, BROWSE_TTL) ?: return emptyList()
        return DoubanParse.cards(body).map { paint(settings, it) }
    }

    suspend fun match(settings: AppSettings, title: String, year: String?): DoubanDetail? {
        if (!settings.doubanEnabled || title.isBlank()) return null
        val suggest = read(settings, DoubanProxy.suggestUrl(title), MATCH_TTL) ?: return null
        val card = DoubanParse.match(DoubanParse.cards(suggest), title, year) ?: return null
        val body = read(settings, DoubanProxy.detailUrl(card.id), MATCH_TTL) ?: return null
        return DoubanParse.detail(body)?.let { paint(settings, it) }
    }

    suspend fun comments(settings: AppSettings, id: String, start: Int): DoubanCommentPage {
        if (!settings.doubanEnabled || id.isBlank()) return DoubanCommentPage(emptyList(), start, false)
        val body = read(settings, DoubanProxy.commentsUrl(id, start, 20), COMMENT_TTL)
            ?: return DoubanCommentPage(emptyList(), start, false)
        return DoubanParse.comments(body, start, 20)
    }

    private suspend fun read(settings: AppSettings, target: String, ttl: Long): String? = withContext(Dispatchers.IO) {
        val url = DoubanProxy.dataUrl(settings.doubanDataProxy, settings.doubanDataProxyUrl, target)
        val key = sha(url)
        synchronized(memory) {
            memory[key]?.takeIf { System.currentTimeMillis() - it.at < ttl }?.text
        }?.let { return@withContext it }
        val fresh = runCatching {
            http.fetch(
                url = url,
                headers = mapOf(
                    "Referer" to "https://movie.douban.com/",
                    "Accept" to "application/json, text/html, */*",
                ),
                maxBytes = 1_500_000,
                userAgent = Ua.MEDIA,
                timeoutMs = 12_000,
            ).text
        }.getOrNull()
        if (!fresh.isNullOrBlank()) {
            store(key, fresh)
            return@withContext fresh
        }
        synchronized(memory) { memory[key]?.text } ?: disk(key)
    }

    private fun store(key: String, text: String) {
        val cached = Cached(System.currentTimeMillis(), text)
        synchronized(memory) { memory[key] = cached }
        runCatching { File(dir, key).writeText(text) }
    }

    private fun disk(key: String): String? = runCatching { File(dir, key).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun paint(settings: AppSettings, card: DoubanCard): DoubanCard =
        card.copy(poster = image(settings, card.poster))

    private fun paint(settings: AppSettings, detail: DoubanDetail): DoubanDetail =
        detail.copy(poster = image(settings, detail.poster))

    private fun image(settings: AppSettings, url: String): String {
        val mode = when {
            settings.doubanImageProxy == DoubanProxy.CUSTOM -> DoubanProxy.CUSTOM
            settings.doubanImageProxy == DoubanProxy.IMG3 || settings.doubanDataProxy == DoubanProxy.IMG3 -> DoubanProxy.IMG3
            else -> DoubanProxy.DIRECT
        }
        return DoubanProxy.imageUrl(mode, settings.doubanImageProxyUrl, url)
    }

    private fun sha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private data class Cached(val at: Long, val text: String)

    companion object {
        private const val BROWSE_TTL = 30 * 60 * 1000L
        private const val MATCH_TTL = 6 * 60 * 60 * 1000L
        private const val COMMENT_TTL = 10 * 60 * 1000L
    }
}
