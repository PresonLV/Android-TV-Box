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
            HomeRowSetting("history", "最近播放", true),
            HomeRowSetting("favorite", "收藏", true),
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
    val backgroundType: String = "builtin",
    val gradientId: String = "ink",
    val backgroundImageUrl: String = "",
    val wallpaperId: String = "",
    val solidColor: String = "#12151C",
    val wallpaperBlur: Int = 0,
    val wallpaperDim: Int = 28,
    val fontScale: String = "medium",
    val homeRows: List<HomeRowSetting> = HomeRowSetting.defaults(),
    val homeLayout: String = "cinema",
    val reduceMotion: Boolean = false,
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
    val iptvOrgSeeded: Boolean = false,
    val iptvOrgKind: String = "country",
    val iptvOrgCode: String = "cn",
) {
    fun sanitized(): AppSettings = copy(
        themeMode = when (themeMode) {
            "light" -> "light"
            "system" -> "system"
            else -> "dark"
        },
        accent = if (ACCENT_HEX.matches(accent)) accent else "#E2B15A",
        backgroundType = when {
            backgroundType == "image" && backgroundImageUrl.isNotBlank() -> "image"
            backgroundType == "solid" -> "solid"
            backgroundType == "none" -> "none"
            else -> "builtin"
        },
        wallpaperId = when {
            wallpaperId in WALLPAPERS -> wallpaperId
            gradientId in WALLPAPERS -> gradientId
            else -> "ink"
        },
        gradientId = when {
            wallpaperId in GRADIENTS -> wallpaperId
            gradientId in GRADIENTS -> gradientId
            else -> "ink"
        },
        backgroundImageUrl = backgroundImageUrl.trim(),
        solidColor = if (ACCENT_HEX.matches(solidColor)) solidColor else "#12151C",
        wallpaperBlur = wallpaperBlur.coerceIn(0, 24),
        wallpaperDim = wallpaperDim.coerceIn(0, 80),
        fontScale = if (fontScale in FONT_SCALES) fontScale else "medium",
        homeRows = sanitizeRows(homeRows),
        homeLayout = if (homeLayout == "classic") "classic" else "cinema",
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
        iptvOrgKind = when (iptvOrgKind) {
            "category", "language" -> iptvOrgKind
            else -> "country"
        },
        iptvOrgCode = iptvOrgCode.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "cn" }.take(12),
    )

    companion object {
        val GRADIENTS = setOf("ink", "dusk", "ocean", "forest", "ember")
        val WALLPAPERS = AppearanceCatalog.wallpaperIds
        val FONT_SCALES = AppearanceCatalog.fontIds
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
