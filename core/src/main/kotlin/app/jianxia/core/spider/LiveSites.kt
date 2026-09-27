package app.jianxia.core.spider

/**
 * 虎牙、斗鱼这类直播间爬虫不应出现在影片首页。判断只看站点名称、key 和 api。
 */
object LiveSites {
    private val words = listOf(
        "虎牙",
        "斗鱼",
        "huya",
        "douyu",
        "一直播",
        "哔哩直播",
        "bilibili直播",
        "bilibili live",
        "bililive",
        "yy直播",
        "快手直播",
        "直播",
    )

    fun matches(name: String, key: String, api: String): Boolean {
        val blob = "$name $key $api".lowercase()
        return words.any { blob.contains(it.lowercase()) }
    }
}
