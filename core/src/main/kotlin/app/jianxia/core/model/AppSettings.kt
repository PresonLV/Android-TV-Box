package app.jianxia.core.model

import kotlinx.serialization.Serializable

@Serializable
data class HomeRowSetting(
    val id: String,
    val title: String,
    val visible: Boolean = true,
) {
    companion object {
        fun defaults(): List<HomeRowSetting> = listOf(
            HomeRowSetting("history", "继续观看", true),
            HomeRowSetting("favorite", "我的收藏", true),
            HomeRowSetting("latest", "最新", true),
            HomeRowSetting("movie", "电影", true),
            HomeRowSetting("tv", "电视剧", true),
            HomeRowSetting("variety", "综艺", true),
            HomeRowSetting("anime", "动漫", true),
            HomeRowSetting("doc", "纪录片", true),
        )
    }
}

@Serializable
data class AppSettings(
    val themeMode: String = "dark",
    val accent: String = "#E2B15A",
    val backgroundType: String = "gradient",
    val gradientId: String = "ink",
    val backgroundImageUrl: String = "",
    val homeRows: List<HomeRowSetting> = HomeRowSetting.defaults(),
    val posterSize: String = "medium",
    val defaultSourceId: String = "",
    val searchTimeoutSec: Int = 8,
    val autoLineSelect: Boolean = true,
    val decoder: String = "hardware",
    val playerEngine: String = "vlc",
    val defaultSpeed: Float = 1f,
    val aspect: String = "fit",
    val startupPage: String = "vod",
    val recentSearches: List<String> = emptyList(),
    val lastLiveUrl: String = "",
) {
    fun sanitized(): AppSettings = copy(
        themeMode = if (themeMode == "light") "light" else "dark",
        accent = if (ACCENT_HEX.matches(accent)) accent else "#E2B15A",
        backgroundType = if (backgroundType == "image") "image" else "gradient",
        gradientId = if (gradientId in GRADIENTS) gradientId else "ink",
        backgroundImageUrl = backgroundImageUrl.trim(),
        homeRows = sanitizeRows(homeRows),
        posterSize = if (posterSize in POSTER_SIZES) posterSize else "medium",
        defaultSourceId = defaultSourceId.trim(),
        searchTimeoutSec = searchTimeoutSec.coerceIn(3, 30),
        decoder = if (decoder == "software") "software" else "hardware",
        playerEngine = if (playerEngine == "exo") "exo" else "vlc",
        defaultSpeed = when {
            defaultSpeed < 0.5f -> 0.5f
            defaultSpeed > 2f -> 2f
            else -> defaultSpeed
        },
        aspect = if (aspect in ASPECTS) aspect else "fit",
        startupPage = if (startupPage == "live") "live" else "vod",
        recentSearches = recentSearches.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12),
        lastLiveUrl = lastLiveUrl.trim(),
    )

    companion object {
        val GRADIENTS = setOf("ink", "dusk", "ocean", "forest", "ember")
        val POSTER_SIZES = setOf("small", "medium", "large")
        val ASPECTS = setOf("fit", "fill", "zoom", "16:9", "4:3")
        private val ACCENT_HEX = Regex("#[0-9A-Fa-f]{6}")

        fun sanitizeRows(rows: List<HomeRowSetting>): List<HomeRowSetting> {
            val known = defaultsRows()
            if (rows.isEmpty()) return known
            val allowed = known.map { it.id }.toSet()
            val kept = rows.filter { it.id in allowed }.distinctBy { it.id }
            val present = kept.map { it.id }.toSet()
            return kept + known.filter { it.id !in present }
        }

        private fun defaultsRows() = HomeRowSetting.defaults()
    }
}

@Serializable
data class BackupSource(
    val name: String,
    val url: String,
    val kind: String,
    val epgUrl: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

@Serializable
data class BackupBundle(
    val version: Int = 1,
    val settings: AppSettings = AppSettings(),
    val sources: List<BackupSource> = emptyList(),
)
