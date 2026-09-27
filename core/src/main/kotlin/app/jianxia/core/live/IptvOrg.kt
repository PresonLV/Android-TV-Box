package app.jianxia.core.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * iptv-org 公开直播列表的地址规则。
 * 安装包里只放这些网址模板和中文名称，频道表在使用时再下载。
 */
object IptvOrg {
    const val ID = "iptv-org"
    const val NAME = "公共频道（iptv-org）"
    const val ATTRIBUTION = "频道列表来自 iptv-org"
    const val HOME = "https://github.com/iptv-org/iptv"
    const val INDEX = "https://raw.githubusercontent.com/iptv-org/iptv/master/PLAYLISTS.md"
    const val GUIDES = "https://iptv-org.github.io/api/guides.json"
    const val GUIDE_INDEX_LIMIT = 1_500_000
    const val DEFAULT_KIND = "country"
    const val DEFAULT_CODE = "cn"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val categoryRow = Regex(
        """<td[^>]*>\s*([^<]+?)\s*</td>\s*<td[^>]*>.*?</td>\s*<td[^>]*>\s*<code>\s*(https://iptv-org\.github\.io/iptv/categories/([a-z0-9]+)\.m3u)\s*</code>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val languageRow = Regex(
        """<td[^>]*>\s*([^<]+?)\s*</td>\s*<td[^>]*>.*?</td>\s*<td[^>]*>\s*<code>\s*(https://iptv-org\.github\.io/iptv/languages/([a-z0-9]+)\.m3u)\s*</code>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val countryLine = Regex(
        """(?m)^[^\n]*<code>\s*(https://iptv-org\.github\.io/iptv/countries/([a-z0-9]+)\.m3u)\s*</code>""",
        RegexOption.IGNORE_CASE,
    )

    data class Entry(val kind: String, val code: String, val label: String, val url: String)

    fun playlist(kind: String, code: String): String {
        val folder = when (kind) {
            "category" -> "categories"
            "language" -> "languages"
            else -> "countries"
        }
        val fallback = if (folder == "countries") DEFAULT_CODE else if (folder == "languages") "zho" else "news"
        val safe = code.lowercase().filter { it.isLetterOrDigit() }.ifBlank { fallback }
        return "https://iptv-org.github.io/iptv/$folder/$safe.m3u"
    }

    fun isPublicPlaylist(url: String): Boolean {
        val text = url.trim().lowercase()
        return text.startsWith("https://iptv-org.github.io/iptv/") &&
            (text.contains("/countries/") || text.contains("/categories/") || text.contains("/languages/") || text.contains("/index")) &&
            text.substringBefore('?').endsWith(".m3u")
    }

    fun note(label: String): String = if (label.isBlank()) ATTRIBUTION else "$ATTRIBUTION · $label"

    fun listedAsBlocked(name: String): Boolean =
        name.contains("geo-blocked", ignoreCase = true) || name.contains("geo blocked", ignoreCase = true)

    fun label(kind: String, code: String, published: String = ""): String {
        val key = code.lowercase()
        val zh = when (kind) {
            "category" -> CATEGORY_ZH[key]
            "language" -> LANGUAGE_ZH[key]
            else -> COUNTRY_ZH[key]
        }
        return zh ?: published.ifBlank { key }
    }

    fun parseIndex(markdown: String): List<Entry> {
        val found = linkedMapOf<String, Entry>()
        categoryRow.findAll(markdown).forEach { match ->
            val code = match.groupValues[3].lowercase()
            if (code == "xxx") return@forEach
            val url = match.groupValues[2]
            found["category:$code"] = Entry("category", code, label("category", code, match.groupValues[1].trim()), url)
        }
        languageRow.findAll(markdown).forEach { match ->
            val code = match.groupValues[3].lowercase()
            val url = match.groupValues[2]
            found["language:$code"] = Entry("language", code, label("language", code, match.groupValues[1].trim()), url)
        }
        countryLine.findAll(markdown).forEach { match ->
            val code = match.groupValues[2].lowercase()
            val url = match.groupValues[1]
            val published = match.value.substringBefore("<code").trim().trimStart('-', '*', ' ').dropWhile { !it.isLetter() }.trim()
            found["country:$code"] = Entry("country", code, label("country", code, published), url)
        }
        return found.values.toList()
    }

    fun fallback(kind: String): List<Entry> {
        val pairs = when (kind) {
            "category" -> listOf("news", "kids", "music", "sports", "general", "movies", "documentary", "entertainment")
            "language" -> listOf("zho", "eng", "jpn", "kor", "fra", "deu", "spa", "rus")
            else -> listOf("cn", "hk", "tw", "mo", "us", "jp", "kr", "uk", "sg", "th")
        }
        return pairs.map { code ->
            Entry(if (kind == "category" || kind == "language") kind else "country", code, label(kind, code), playlist(kind, code))
        }
    }

    /**
     * 从 iptv-org 的 guides.json 里挑出当前频道能用的 XMLTV。
     * 优先中文，其次英文。调用方应先确认索引不大，再把正文传进来。
     */
    fun guideUrls(raw: String, channelIds: Collection<String>, limit: Int = 2): List<String> {
        val wanted = channelIds.map { it.substringBefore("@").trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty() || limit <= 0) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        val options = linkedMapOf<String, MutableList<Pair<String, String>>>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val channel = obj["channel"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: continue
            if (channel !in wanted) continue
            val lang = obj["lang"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val sources = obj["sources"] as? JsonArray ?: continue
            for (source in sources) {
                val item = source as? JsonObject ?: continue
                val url = item["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    options.getOrPut(channel) { mutableListOf() } += url to lang
                }
            }
        }
        val urls = linkedSetOf<String>()
        for (choices in options.values) {
            val picked = choices.firstOrNull { it.second.lowercase().startsWith("zh") }
                ?: choices.firstOrNull { it.second.lowercase().startsWith("en") }
                ?: choices.firstOrNull()
            if (picked != null) urls += picked.first
            if (urls.size >= limit) break
        }
        return urls.take(limit)
    }

    private val CATEGORY_ZH = mapOf(
        "animation" to "动画",
        "auto" to "汽车",
        "business" to "财经",
        "classic" to "经典",
        "comedy" to "喜剧",
        "cooking" to "美食",
        "culture" to "文化",
        "documentary" to "纪录片",
        "education" to "教育",
        "entertainment" to "娱乐",
        "family" to "家庭",
        "general" to "综合",
        "interactive" to "互动",
        "kids" to "少儿",
        "legislative" to "议会",
        "lifestyle" to "生活",
        "movies" to "电影",
        "music" to "音乐",
        "news" to "新闻",
        "outdoor" to "户外",
        "public" to "公共",
        "relax" to "放松",
        "religious" to "宗教",
        "science" to "科学",
        "series" to "剧集",
        "shop" to "购物",
        "sports" to "体育",
        "travel" to "旅游",
        "weather" to "天气",
        "undefined" to "未分类",
    )

    private val LANGUAGE_ZH = mapOf(
        "zho" to "中文",
        "cmn" to "普通话",
        "yue" to "粤语",
        "nan" to "闽南语",
        "eng" to "英语",
        "jpn" to "日语",
        "kor" to "韩语",
        "fra" to "法语",
        "deu" to "德语",
        "spa" to "西班牙语",
        "rus" to "俄语",
        "por" to "葡萄牙语",
        "ara" to "阿拉伯语",
        "hin" to "印地语",
        "tha" to "泰语",
        "vie" to "越南语",
        "ind" to "印尼语",
        "msa" to "马来语",
        "fil" to "菲律宾语",
        "ita" to "意大利语",
        "tur" to "土耳其语",
        "pol" to "波兰语",
        "ukr" to "乌克兰语",
        "nld" to "荷兰语",
        "swe" to "瑞典语",
    )

    private val COUNTRY_ZH = mapOf(
        "af" to "阿富汗",
        "al" to "阿尔巴尼亚",
        "dz" to "阿尔及利亚",
        "ad" to "安道尔",
        "ao" to "安哥拉",
        "ag" to "安提瓜和巴布达",
        "ar" to "阿根廷",
        "am" to "亚美尼亚",
        "aw" to "阿鲁巴",
        "au" to "澳大利亚",
        "at" to "奥地利",
        "az" to "阿塞拜疆",
        "bs" to "巴哈马",
        "bh" to "巴林",
        "bd" to "孟加拉国",
        "bb" to "巴巴多斯",
        "by" to "白俄罗斯",
        "be" to "比利时",
        "bz" to "伯利兹",
        "bj" to "贝宁",
        "bt" to "不丹",
        "bo" to "玻利维亚",
        "bq" to "博奈尔",
        "ba" to "波黑",
        "br" to "巴西",
        "vg" to "英属维尔京群岛",
        "bn" to "文莱",
        "bg" to "保加利亚",
        "bf" to "布基纳法索",
        "kh" to "柬埔寨",
        "cm" to "喀麦隆",
        "ca" to "加拿大",
        "cv" to "佛得角",
        "td" to "乍得",
        "cl" to "智利",
        "cn" to "中国",
        "co" to "哥伦比亚",
        "km" to "科摩罗",
        "cr" to "哥斯达黎加",
        "hr" to "克罗地亚",
        "cu" to "古巴",
        "cw" to "库拉索",
        "cy" to "塞浦路斯",
        "cz" to "捷克",
        "cd" to "刚果（金）",
        "dk" to "丹麦",
        "do" to "多米尼加",
        "ec" to "厄瓜多尔",
        "eg" to "埃及",
        "sv" to "萨尔瓦多",
        "gq" to "赤道几内亚",
        "er" to "厄立特里亚",
        "ee" to "爱沙尼亚",
        "et" to "埃塞俄比亚",
        "fi" to "芬兰",
        "fr" to "法国",
        "gf" to "法属圭亚那",
        "pf" to "法属波利尼西亚",
        "gm" to "冈比亚",
        "ge" to "格鲁吉亚",
        "de" to "德国",
        "gh" to "加纳",
        "gr" to "希腊",
        "gp" to "瓜德罗普",
        "gu" to "关岛",
        "gt" to "危地马拉",
        "gg" to "根西",
        "gn" to "几内亚",
        "gy" to "圭亚那",
        "ht" to "海地",
        "hn" to "洪都拉斯",
        "hk" to "香港",
        "hu" to "匈牙利",
        "is" to "冰岛",
        "in" to "印度",
        "id" to "印度尼西亚",
        "ir" to "伊朗",
        "iq" to "伊拉克",
        "ie" to "爱尔兰",
        "il" to "以色列",
        "it" to "意大利",
        "ci" to "科特迪瓦",
        "jm" to "牙买加",
        "jp" to "日本",
        "jo" to "约旦",
        "kz" to "哈萨克斯坦",
        "ke" to "肯尼亚",
        "xk" to "科索沃",
        "kw" to "科威特",
        "kg" to "吉尔吉斯斯坦",
        "la" to "老挝",
        "lv" to "拉脱维亚",
        "lb" to "黎巴嫩",
        "ly" to "利比亚",
        "li" to "列支敦士登",
        "lt" to "立陶宛",
        "lu" to "卢森堡",
        "mo" to "澳门",
        "mg" to "马达加斯加",
        "my" to "马来西亚",
        "mv" to "马尔代夫",
        "ml" to "马里",
        "mt" to "马耳他",
        "mq" to "马提尼克",
        "mr" to "毛里塔尼亚",
        "mu" to "毛里求斯",
        "mx" to "墨西哥",
        "md" to "摩尔多瓦",
        "mc" to "摩纳哥",
        "mn" to "蒙古",
        "me" to "黑山",
        "ma" to "摩洛哥",
        "mz" to "莫桑比克",
        "mm" to "缅甸",
        "na" to "纳米比亚",
        "np" to "尼泊尔",
        "nl" to "荷兰",
        "nz" to "新西兰",
        "ni" to "尼加拉瓜",
        "ne" to "尼日尔",
        "ng" to "尼日利亚",
        "kp" to "朝鲜",
        "mk" to "北马其顿",
        "no" to "挪威",
        "om" to "阿曼",
        "pk" to "巴基斯坦",
        "ps" to "巴勒斯坦",
        "pa" to "巴拿马",
        "pg" to "巴布亚新几内亚",
        "py" to "巴拉圭",
        "pe" to "秘鲁",
        "ph" to "菲律宾",
        "pl" to "波兰",
        "pt" to "葡萄牙",
        "pr" to "波多黎各",
        "qa" to "卡塔尔",
        "cg" to "刚果（布）",
        "re" to "留尼汪",
        "ro" to "罗马尼亚",
        "ru" to "俄罗斯",
        "rw" to "卢旺达",
        "kn" to "圣基茨和尼维斯",
        "lc" to "圣卢西亚",
        "vc" to "圣文森特和格林纳丁斯",
        "ws" to "萨摩亚",
        "sm" to "圣马力诺",
        "sa" to "沙特阿拉伯",
        "sn" to "塞内加尔",
        "rs" to "塞尔维亚",
        "sl" to "塞拉利昂",
        "sg" to "新加坡",
        "sx" to "荷属圣马丁",
        "sk" to "斯洛伐克",
        "si" to "斯洛文尼亚",
        "so" to "索马里",
        "za" to "南非",
        "kr" to "韩国",
        "es" to "西班牙",
        "lk" to "斯里兰卡",
        "sd" to "苏丹",
        "sr" to "苏里南",
        "se" to "瑞典",
        "ch" to "瑞士",
        "sy" to "叙利亚",
        "tw" to "台湾",
        "tj" to "塔吉克斯坦",
        "tz" to "坦桑尼亚",
        "th" to "泰国",
        "tg" to "多哥",
        "tt" to "特立尼达和多巴哥",
        "tn" to "突尼斯",
        "tr" to "土耳其",
        "tm" to "土库曼斯坦",
        "ug" to "乌干达",
        "ua" to "乌克兰",
        "ae" to "阿联酋",
        "uk" to "英国",
        "us" to "美国",
        "uy" to "乌拉圭",
        "uz" to "乌兹别克斯坦",
        "va" to "梵蒂冈",
        "ve" to "委内瑞拉",
        "vn" to "越南",
        "eh" to "西撒哈拉",
        "ye" to "也门",
        "zw" to "津巴布韦",
        "int" to "国际",
    )
}
