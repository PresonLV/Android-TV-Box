package app.jianxia.core.danmaku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class DanmakuMode { Scroll, Top, Bottom }

data class DanmakuCue(
    val timeSec: Double,
    val mode: DanmakuMode,
    val color: Int,
    val text: String,
)

data class DanmakuAnime(
    val animeId: Long,
    val title: String,
    val episodeCount: Int = 0,
)

data class DanmakuEpisode(val episodeId: Long, val title: String)

data class DanmakuHit(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
)

/** danmu_api 兼容地址。没有内置服务器，地址和令牌都由用户填写。 */
object DanmakuApi {
    fun url(base: String, token: String, path: String): String? {
        val root = base.trim().trimEnd('/')
        if (!root.startsWith("http://") && !root.startsWith("https://")) return null
        val secret = token.trim().trim('/')
        val withToken = if (secret.isEmpty() || root.endsWith("/$secret")) root else "$root/$secret"
        val suffix = if (path.startsWith("/")) path else "/$path"
        return withToken + suffix
    }

    fun searchPath(keyword: String): String = "/api/v2/search/anime?keyword=${encode(keyword)}"

    fun matchPath(): String = "/api/v2/match"

    fun episodesPath(animeId: Long): String = "/api/v2/bangumi/$animeId"

    fun commentPath(episodeId: Long): String = "/api/v2/comment/$episodeId?format=xml"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

object DanmakuParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun cues(body: String): List<DanmakuCue> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = if (trimmed.startsWith("<")) xml(trimmed) else jsonComments(trimmed)
        return parsed.filter { it.text.isNotBlank() }.sortedBy { it.timeSec }
    }

    fun animes(body: String): List<DanmakuAnime> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val array = root["animes"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["animeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = obj["animeTitle"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            DanmakuAnime(id, title, obj["episodeCount"]?.jsonPrimitive?.intOrNull ?: 0)
        }
    }

    fun episodes(body: String): List<DanmakuEpisode> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val bangumi = root["bangumi"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: root
        val array = bangumi["episodes"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = obj["episodeTitle"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            DanmakuEpisode(id, title.ifBlank { id.toString() })
        }
    }

    fun hits(body: String): List<DanmakuHit> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        if (root["isMatched"]?.jsonPrimitive?.contentOrNull == "false") return emptyList()
        val array = root["matches"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val episodeId = obj["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            DanmakuHit(
                episodeId = episodeId,
                animeId = obj["animeId"]?.jsonPrimitive?.longOrNull ?: 0,
                animeTitle = obj["animeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                episodeTitle = obj["episodeTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    fun pickEpisode(episodes: List<DanmakuEpisode>, episodeName: String, index: Int): DanmakuEpisode? {
        if (episodes.isEmpty()) return null
        val wanted = episodeNumber(episodeName) ?: (index + 1)
        return episodes.firstOrNull { episodeNumber(it.title) == wanted }
            ?: episodes.getOrNull(wanted - 1)
            ?: episodes.getOrNull(index.coerceAtLeast(0))
    }

    fun pickAnime(animes: List<DanmakuAnime>, title: String): DanmakuAnime? {
        val want = loose(title)
        if (want.isEmpty()) return animes.firstOrNull()
        return animes.maxByOrNull { anime ->
            val name = loose(anime.title)
            when {
                name == want -> 100
                name.contains(want) || want.contains(name) -> 50
                else -> 0
            }
        }?.takeIf { loose(it.title).let { name -> name == want || name.contains(want) || want.contains(name) } }
    }

    fun blocked(text: String, words: List<String>): Boolean {
        if (text.isBlank()) return true
        return words.any { word ->
            val token = word.trim()
            if (token.isEmpty()) return@any false
            if (token.startsWith("re:")) {
                val pattern = token.removePrefix("re:").trim()
                pattern.isNotEmpty() && runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
            } else {
                text.contains(token, ignoreCase = true)
            }
        }
    }

    fun thin(cues: List<DanmakuCue>, density: Int): List<DanmakuCue> {
        val keep = density.coerceIn(5, 100)
        if (keep >= 100 || cues.size < 8) return cues
        return cues.filter { kotlin.math.abs(it.text.hashCode() % 100) < keep }
    }

    fun episodeNumber(name: String): Int? {
        val patterns = listOf(
            Regex("""第\s*(\d+)\s*[集话期]"""),
            Regex("""(?i)(?:ep|episode)\s*(\d+)"""),
            Regex("""(?i)s\d{1,2}e(\d{1,3})"""),
            Regex("""^(\d{1,4})$"""),
        )
        for (pattern in patterns) {
            pattern.find(name.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun jsonComments(body: String): List<DanmakuCue> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val array = root["comments"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val attr = obj["p"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val text = obj["m"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            cue(attr, unescape(text))
        }
    }

    private fun xml(body: String): List<DanmakuCue> {
        val found = ArrayList<DanmakuCue>()
        val matcher = DANMAKU_TAG.findAll(body)
        matcher.forEach { match ->
            cue(match.groupValues[1], unescape(match.groupValues[2]))?.let { found += it }
        }
        return found
    }

    private fun cue(attr: String, text: String): DanmakuCue? {
        val parts = attr.split(',')
        val time = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        if (time < 0) return null
        val type = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val color = parts.getOrNull(3)?.toIntOrNull()?.and(0xFFFFFF) ?: 0xFFFFFF
        val mode = when (type) {
            4 -> DanmakuMode.Bottom
            5 -> DanmakuMode.Top
            else -> DanmakuMode.Scroll
        }
        return DanmakuCue(time, mode, color, text.trim())
    }

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun loose(value: String): String = value.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

    private val DANMAKU_TAG = Regex("""<d\s+[^>]*p="([^"]+)"[^>]*>([^<]*)</d>""", RegexOption.IGNORE_CASE)
}
