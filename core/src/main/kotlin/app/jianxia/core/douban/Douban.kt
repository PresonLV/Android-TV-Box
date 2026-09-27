package app.jianxia.core.douban

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder

data class DoubanShelf(val kind: String, val label: String, val tags: List<String>)

data class DoubanCard(
    val id: String,
    val title: String,
    val year: String = "",
    val poster: String = "",
    val rating: String = "",
)

data class DoubanPerson(val name: String)

data class DoubanDetail(
    val id: String,
    val title: String,
    val year: String = "",
    val rating: String = "",
    val poster: String = "",
    val intro: String = "",
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
)

data class DoubanComment(
    val id: String,
    val author: String,
    val stars: Int?,
    val text: String,
    val time: String,
    val votes: Int,
)

data class DoubanCommentPage(
    val comments: List<DoubanComment>,
    val start: Int,
    val hasMore: Boolean,
)

/** 豆瓣公开接口的地址拼装。图片可改走 img3，数据可走用户自己的前缀代理。 */
object DoubanProxy {
    const val DIRECT = "direct"
    const val IMG3 = "img3"
    const val CUSTOM = "custom"

    fun categoryUrl(kind: String, tag: String, start: Int, limit: Int): String {
        val type = if (kind == "tv") "tv" else "movie"
        val sort = when (tag) {
            "最新" -> "time"
            "豆瓣高分" -> "rank"
            else -> "recommend"
        }
        val safeLimit = limit.coerceIn(1, 50)
        val safeStart = start.coerceAtLeast(0)
        return "https://movie.douban.com/j/search_subjects?type=$type&tag=${enc(tag)}&sort=$sort&page_limit=$safeLimit&page_start=$safeStart"
    }

    fun suggestUrl(title: String): String =
        "https://movie.douban.com/j/subject_suggest?q=${enc(title.trim())}"

    fun detailUrl(id: String): String =
        "https://m.douban.com/rexxar/api/v2/subject/${id.filter { it.isDigit() }}"

    fun commentsUrl(id: String, start: Int, limit: Int): String {
        val safe = id.filter { it.isDigit() }
        return "https://movie.douban.com/subject/$safe/comments?start=${start.coerceAtLeast(0)}&limit=${limit.coerceIn(1, 50)}&status=P&sort=new_score"
    }

    fun dataUrl(mode: String, custom: String, target: String): String = when (mode) {
        CUSTOM -> prefix(custom, target)
        else -> target
    }

    fun imageUrl(mode: String, custom: String, image: String): String {
        val cleaned = image.trim()
        if (cleaned.isEmpty()) return ""
        return when (mode) {
            IMG3 -> IMAGE_HOST.replace(cleaned, "https://img3.doubanio.com")
            CUSTOM -> prefix(custom, cleaned)
            else -> cleaned
        }
    }

    fun prefix(custom: String, target: String): String {
        val base = custom.trim()
        if (base.isEmpty() || !(base.startsWith("http://") || base.startsWith("https://"))) return target
        if (base.contains("{url}")) return base.replace("{url}", enc(target))
        val root = if (base.endsWith("/")) base else "$base/"
        return root + enc(target)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private val IMAGE_HOST = Regex("""https?://img\d+\.doubanio\.com""", RegexOption.IGNORE_CASE)
}

object DoubanCatalog {
    val movie = DoubanShelf(
        kind = "movie",
        label = "电影",
        tags = listOf(
            "热门", "最新", "豆瓣高分", "华语", "欧美", "韩国", "日本",
            "动作", "喜剧", "科幻", "悬疑", "爱情", "犯罪", "动画",
        ),
    )
    val tv = DoubanShelf(
        kind = "tv",
        label = "电视剧",
        tags = listOf("热门", "美剧", "英剧", "韩剧", "日剧", "国产剧", "港剧", "日本动画", "综艺", "纪录片"),
    )

    fun shelf(kind: String): DoubanShelf = if (kind == "tv") tv else movie
}

object DoubanParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun cards(body: String): List<DoubanCard> {
        val root = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["subjects"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: root["items"]?.let { runCatching { it.jsonArray }.getOrNull() }
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = text(obj, "title")
            val id = text(obj, "id")
            if (title.isBlank() || id.isBlank()) return@mapNotNull null
            val pic = obj["pic"] as? JsonObject
            val poster = text(obj, "cover", "img").ifBlank {
                pic?.let { text(it, "normal", "large") }.orEmpty()
            }
            val ratingObj = obj["rating"] as? JsonObject
            val rating = text(obj, "rate").ifBlank {
                ratingObj?.get("value")?.jsonPrimitive?.doubleOrNull?.let { formatRating(it) }.orEmpty()
            }
            val year = text(obj, "year").ifBlank { yearIn(text(obj, "card_subtitle")) }
            DoubanCard(id = id, title = title, year = year, poster = poster, rating = rating)
        }
    }

    fun detail(body: String): DoubanDetail? {
        val obj = runCatching { json.parseToJsonElement(body.trim()).jsonObject }.getOrNull() ?: return null
        val title = text(obj, "title")
        val id = text(obj, "id")
        if (title.isBlank() || id.isBlank()) return null
        val pic = obj["pic"] as? JsonObject
        val rating = obj["rating"]?.let { runCatching { it.jsonObject["value"]?.jsonPrimitive?.doubleOrNull }.getOrNull() }
        return DoubanDetail(
            id = id,
            title = title,
            year = text(obj, "year"),
            rating = rating?.let { formatRating(it) }.orEmpty(),
            poster = pic?.let { text(it, "large", "normal") }.orEmpty().ifBlank { text(obj, "cover_url") },
            intro = text(obj, "intro"),
            directors = names(obj, "directors"),
            actors = names(obj, "actors"),
        )
    }

    fun comments(html: String, start: Int, limit: Int): DoubanCommentPage {
        val doc = Jsoup.parse(html)
        val items = doc.select(".comment-item").mapNotNull { node ->
            val id = node.attr("data-cid").ifBlank { return@mapNotNull null }
            val text = node.selectFirst(".short")?.text()?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val stars = node.selectFirst(".rating")?.className().orEmpty()
                .let { STAR.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            DoubanComment(
                id = id,
                author = node.selectFirst(".avatar a")?.attr("title").orEmpty().ifBlank { "豆瓣用户" },
                stars = stars,
                text = text,
                time = node.selectFirst(".comment-time")?.attr("title").orEmpty(),
                votes = node.selectFirst(".vote-count")?.text()?.trim()?.toIntOrNull() ?: 0,
            )
        }
        val total = TOTAL.find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val hasMore = if (total > 0) start + items.size < total else items.size >= limit
        return DoubanCommentPage(items, start, hasMore)
    }

    fun match(cards: List<DoubanCard>, title: String, year: String?): DoubanCard? {
        val want = normalize(title)
        if (want.isEmpty()) return null
        val yearWant = year.orEmpty().filter { it.isDigit() }.take(4)
        return cards.mapNotNull { card ->
            val name = normalize(card.title)
            if (name.isEmpty()) return@mapNotNull null
            var score = when {
                name == want -> 100
                name.contains(want) || want.contains(name) -> 40
                else -> return@mapNotNull null
            }
            val cardYear = card.year.filter { it.isDigit() }.take(4)
            if (yearWant.length == 4 && cardYear == yearWant) score += 30
            if (yearWant.length == 4 && cardYear.length == 4 && cardYear != yearWant) score -= 25
            card to score
        }.filter { it.second >= 40 }.maxByOrNull { it.second }?.first
    }

    fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""第\s*[0-9一二三四五六七八九十]+\s*季"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun names(obj: JsonObject, key: String): List<String> =
        obj[key]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            text(item, "name").takeIf { it.isNotBlank() }
        }.distinct().take(12)

    private fun text(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun yearIn(subtitle: String): String = Regex("""(19|20)\d{2}""").find(subtitle)?.value.orEmpty()

    private fun formatRating(value: Double): String {
        if (value <= 0) return ""
        val scaled = (value * 10).toInt().coerceIn(0, 100)
        return "${scaled / 10}.${scaled % 10}"
    }

    private val STAR = Regex("""allstar(\d)0""")
    private val TOTAL = Regex("""全部\s*(\d+)\s*条""")
}
