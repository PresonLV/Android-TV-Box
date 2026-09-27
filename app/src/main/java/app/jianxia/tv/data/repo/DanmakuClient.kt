package app.jianxia.tv.data.repo

import app.jianxia.core.danmaku.DanmakuApi
import app.jianxia.core.danmaku.DanmakuCue
import app.jianxia.core.danmaku.DanmakuParse
import app.jianxia.core.model.AppSettings
import app.jianxia.tv.data.net.NetClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DanmakuClient(private val http: NetClient) {
    private val memory = object : LinkedHashMap<String, Pair<Long, List<DanmakuCue>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<DanmakuCue>>>?) = size > 24
    }

    fun load(settings: AppSettings, title: String, episodeName: String, index: Int): List<DanmakuCue> {
        if (!settings.danmakuEnabled) return emptyList()
        val base = settings.danmakuApiUrl
        if (DanmakuApi.url(base, settings.danmakuTokenOrBlank(settings), "/api/v2/match") == null) return emptyList()
        val key = "$base|$title|$episodeName|$index"
        synchronized(memory) {
            memory[key]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.second
        }?.let { return filter(settings, it) }
        val cues = runCatching { fetch(settings, title, episodeName, index) }.getOrDefault(emptyList())
        if (cues.isNotEmpty()) synchronized(memory) { memory[key] = System.currentTimeMillis() to cues }
        return filter(settings, cues)
    }

    private fun fetch(settings: AppSettings, title: String, episodeName: String, index: Int): List<DanmakuCue> {
        val fileName = listOf(title, episodeName).filter { it.isNotBlank() }.joinToString(" ")
        val matched = post(settings, DanmakuApi.matchPath(), """{"fileName":${json(fileName)}}""")
            ?.let { DanmakuParse.hits(it).firstOrNull() }
        val episodeId = matched?.episodeId ?: searchEpisode(settings, title, episodeName, index) ?: return emptyList()
        val body = get(settings, DanmakuApi.commentPath(episodeId)) ?: return emptyList()
        return DanmakuParse.cues(body)
    }

    private fun searchEpisode(settings: AppSettings, title: String, episodeName: String, index: Int): Long? {
        val found = get(settings, DanmakuApi.searchPath(title))?.let { DanmakuParse.animes(it) }.orEmpty()
        val anime = DanmakuParse.pickAnime(found, title) ?: return null
        val episodes = get(settings, DanmakuApi.episodesPath(anime.animeId))?.let { DanmakuParse.episodes(it) }.orEmpty()
        return DanmakuParse.pickEpisode(episodes, episodeName, index)?.episodeId
    }

    private fun filter(settings: AppSettings, cues: List<DanmakuCue>): List<DanmakuCue> {
        val words = settings.danmakuBlockWords.lineSequence().flatMap { it.split(',', '，') }.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return DanmakuParse.thin(cues.filter { !DanmakuParse.blocked(it.text, words) }, settings.danmakuDensity)
    }

    private fun get(settings: AppSettings, path: String): String? {
        val url = DanmakuApi.url(settings.danmakuApiUrl, settings.danmakuTokenOrBlank(settings), path) ?: return null
        return call(url, null)
    }

    private fun post(settings: AppSettings, path: String, jsonBody: String): String? {
        val url = DanmakuApi.url(settings.danmakuApiUrl, settings.danmakuTokenOrBlank(settings), path) ?: return null
        return call(url, jsonBody)
    }

    private fun call(url: String, jsonBody: String?): String? {
        return try {
            val builder = Request.Builder().url(url).header("Accept", "application/json, application/xml, text/xml, */*")
            if (jsonBody == null) builder.get() else builder.post(jsonBody.toRequestBody(JSON))
            http.http.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(builder.build()).execute().use { response ->
                    if (response.code !in 200..299) return null
                    response.body?.string()?.take(4_000_000)
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun AppSettings.danmakuTokenOrBlank(settings: AppSettings): String = settings.danmakuApiToken

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TTL = 6 * 60 * 60 * 1000L
    }
}
