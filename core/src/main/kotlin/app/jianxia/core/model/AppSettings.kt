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
    val homeShell: String = "warehouse",
    val reduceMotion: Boolean = false,
    val posterSize: String = "medium",
    val posterColumns: Int = 5,
    val tileAlpha: Int = 72,
    val cornerRadius: Int = 12,
    val showRating: Boolean = true,
    val showYear: Boolean = true,
    val showQuality: Boolean = true,
    val showDoubanBadge: Boolean = true,
    val showClock: Boolean = true,
    val homeActions: List<ShelfToggle> = emptyList(),
    val homeTabs: List<ShelfToggle> = emptyList(),
    val playerBar: String = "full",
    val homeRail: String = "left",
    val quarkCookie: String = "",
    val ucCookie: String = "",
    val aliToken: String = "",
    val wallpaperPayload: String = "",
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
    val doubanEnabled: Boolean = true,
    val doubanDataProxy: String = "direct",
    val doubanDataProxyUrl: String = "",
    val doubanImageProxy: String = "img3",
    val doubanImageProxyUrl: String = "",
    val skipHlsAds: Boolean = true,
    val hlsAdRules: String = "",
    val danmakuApiUrl: String = "",
    val danmakuApiToken: String = "",
    val danmakuEnabled: Boolean = true,
    val danmakuOpacity: Int = 80,
    val danmakuFont: String = "medium",
    val danmakuSpeed: String = "medium",
    val danmakuDensity: Int = 60,
    val danmakuArea: String = "half",
    val danmakuBlockWords: String = "",
    val subtitleSize: String = "medium",
    val subtitlePosition: String = "bottom",
    val subtitleOffsetMs: Int = 0,
    val spiderEnabled: Boolean = false,
    val showLiveOnVod: Boolean = false,
    val playerEngineChosen: Boolean = false,
    val homeRecommend: String = "douban",
    val homeMultiRow: Boolean = false,
    val searchStyle: String = "poster",
    val aggregateSearch: Boolean = true,
    val videoRender: String = "texture",
    val safeDns: String = "auto",
    val sniffEnabled: Boolean = true,
    val mergeHistory: Boolean = true,
    val historyLimit: Int = 30,
    val windowPreview: Boolean = false,
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
        homeShell = if (homeShell == "cinema") "cinema" else "warehouse",
        posterSize = if (posterSize in POSTER_SIZES) posterSize else "medium",
        posterColumns = if (posterColumns in POSTER_COLUMNS) posterColumns else 5,
        tileAlpha = tileAlpha.coerceIn(30, 100),
        cornerRadius = cornerRadius.coerceIn(0, 28),
        homeActions = UiDiy.sanitizeActions(homeActions),
        homeTabs = UiDiy.sanitizeTabs(homeTabs),
        playerBar = if (playerBar in PLAYER_BARS) playerBar else "full",
        homeRail = if (homeRail == "top") "top" else "left",
        quarkCookie = quarkCookie.trim().take(8_000),
        ucCookie = ucCookie.trim().take(8_000),
        aliToken = aliToken.trim().take(8_000),
        wallpaperPayload = wallpaperPayload.trim().let { if (it.length > 8_000_000) "" else it },
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
        doubanDataProxy = proxyMode(doubanDataProxy),
        doubanImageProxy = proxyMode(doubanImageProxy),
        doubanDataProxyUrl = httpOrBlank(doubanDataProxyUrl),
        doubanImageProxyUrl = httpOrBlank(doubanImageProxyUrl),
        hlsAdRules = hlsAdRules.trim().take(4_000),
        danmakuApiUrl = httpOrBlank(danmakuApiUrl),
        danmakuApiToken = danmakuApiToken.trim().take(200),
        danmakuOpacity = danmakuOpacity.coerceIn(20, 100),
        danmakuFont = if (danmakuFont in SIZES) danmakuFont else "medium",
        danmakuSpeed = if (danmakuSpeed in SPEEDS) danmakuSpeed else "medium",
        danmakuDensity = danmakuDensity.coerceIn(10, 100),
        danmakuArea = if (danmakuArea in AREAS) danmakuArea else "half",
        danmakuBlockWords = danmakuBlockWords.trim().take(2_000),
        subtitleSize = if (subtitleSize in SIZES) subtitleSize else "medium",
        subtitlePosition = if (subtitlePosition in POSITIONS) subtitlePosition else "bottom",
        subtitleOffsetMs = subtitleOffsetMs.coerceIn(-60_000, 60_000),
        homeRecommend = if (homeRecommend == "site") "site" else "douban",
        searchStyle = if (searchStyle == "list") "list" else "poster",
        videoRender = if (videoRender == "surface") "surface" else "texture",
        safeDns = when (safeDns) {
            "off", "on" -> safeDns
            else -> "auto"
        },
        historyLimit = historyLimit.coerceIn(10, 200),
    )

    companion object {
        val GRADIENTS = setOf("ink", "dusk", "ocean", "forest", "ember")
        val WALLPAPERS = AppearanceCatalog.wallpaperIds
        val FONT_SCALES = AppearanceCatalog.fontIds
        val POSTER_SIZES = setOf("small", "medium", "large")
        val POSTER_COLUMNS = setOf(4, 5, 6)
        val PLAYER_BARS = setOf("full", "slim", "float")
        val ASPECTS = setOf("fit", "fill", "zoom", "16:9", "4:3")
        val SIZES = setOf("small", "medium", "large")
        val SPEEDS = setOf("slow", "medium", "fast")
        val AREAS = setOf("quarter", "half", "full")
        val POSITIONS = setOf("bottom", "middle", "top")
        private val ACCENT_HEX = Regex("#[0-9A-Fa-f]{6}")

        fun proxyMode(value: String): String = when (value) {
            "img3", "custom" -> value
            else -> "direct"
        }

        fun httpOrBlank(value: String): String {
            val trimmed = value.trim().take(500)
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else ""
        }

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
