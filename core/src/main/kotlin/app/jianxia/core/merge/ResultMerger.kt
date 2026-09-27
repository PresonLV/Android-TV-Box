package app.jianxia.core.merge

import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.VodItem
import app.jianxia.core.parser.normalizePic

fun mergeVodItems(items: List<VodItem>): List<MergedVod> {
    val groups = LinkedHashMap<String, MutableList<VodItem>>()
    for (item in items) {
        if (item.title.isBlank()) continue
        val key = mergeKey(item.title, item.year)
        groups.getOrPut(key) { mutableListOf() }.add(item)
    }
    return groups.map { (key, group) ->
        fun pick(selector: (VodItem) -> String?): String? =
            group.firstNotNullOfOrNull { selector(it)?.trim()?.takeIf(String::isNotEmpty) }
        MergedVod(
            key = key,
            title = group.maxBy { it.title.length }.title.trim(),
            year = group.firstNotNullOfOrNull { effectiveYear(it.title, it.year) },
            pic = group.firstNotNullOfOrNull { normalizePic(it.pic) },
            typeName = majority(group.mapNotNull { it.typeName?.trim()?.takeIf(String::isNotEmpty) }),
            remarks = pick { it.remarks },
            area = pick { it.area },
            actor = pick { it.actor },
            director = pick { it.director },
            content = pick { it.content },
            score = pick { it.score },
            variants = group,
        )
    }
}

private fun majority(values: List<String>): String? {
    if (values.isEmpty()) return null
    return values.groupingBy { it }.eachCount().maxBy { it.value }.key
}

fun mergeKey(title: String, year: String?): String {
    val normalizedYear = effectiveYear(title, year).orEmpty()
    return "${normalizeTitle(title)}|$normalizedYear"
}

fun effectiveYear(title: String, year: String?): String? {
    val digits = year?.filter(Char::isDigit)?.take(4)
    if (digits != null && digits.length == 4 && (digits.startsWith("19") || digits.startsWith("20"))) {
        return digits
    }
    return Regex("[（(]((19|20)\\d{2})[)）]").find(title)?.groupValues?.get(1)
}

fun normalizeTitle(title: String): String {
    val half = buildString(title.length) {
        title.trim().forEach { char ->
            append(
                when (char) {
                    in 'Ａ'..'Ｚ' -> 'A' + (char - 'Ａ')
                    in 'ａ'..'ｚ' -> 'a' + (char - 'ａ')
                    in '０'..'９' -> '0' + (char - '０')
                    else -> char
                },
            )
        }
    }
    return half.lowercase()
        .replace(Regex("[\\s\\p{Punct}·・:：,，.。!！?？'\"“”‘’\\-—_~～、/\\\\|]+"), "")
        .replace(Regex("(4k|1080p|720p|hd|bd|蓝光|超清|高清)$"), "")
}

object CategoryMatcher {
    fun matches(rowId: String, typeName: String): Boolean {
        val name = typeName.trim()
        if (name.isEmpty()) return false
        return when (rowId) {
            "movie" -> name.contains("电影") || name.contains("影片") ||
                (name.endsWith("片") && !name.contains("动漫") && !name.contains("动画") && !name.contains("纪录"))
            "tv" -> listOf("连续剧", "电视剧", "国产剧", "港台剧", "日韩剧", "欧美剧", "海外剧", "泰剧", "剧集", "网剧")
                .any { name.contains(it) }
            "variety" -> name.contains("综艺")
            "anime" -> listOf("动漫", "动画", "番剧").any { name.contains(it) }
            "doc" -> name.contains("纪录")
            else -> false
        }
    }
}
