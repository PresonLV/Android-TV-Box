package app.jianxia.core.douban

/** 豆瓣图床经常换主机名。先试 img3/1/2/9，再走图片代理。 */
object PosterUrls {
    private val host = Regex("""https?://img\d+\.doubanio\.com""", RegexOption.IGNORE_CASE)

    fun candidates(raw: String?): List<String> {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return emptyList()
        val match = host.find(url)
        val directs = if (match == null) {
            listOf(url)
        } else {
            val path = url.substring(match.range.last + 1)
            val alts = listOf("img9", "img3", "img1", "img2").map { "https://$it.doubanio.com$path" }
            listOf(url) + alts
        }
        val proxied = directs.map { direct ->
            val bare = direct.removePrefix("https://").removePrefix("http://")
            "https://images.weserv.nl/?url=$bare"
        }
        return (directs + proxied).distinct()
    }
}
