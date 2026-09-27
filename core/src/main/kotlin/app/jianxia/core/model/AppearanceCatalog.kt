package app.jianxia.core.model

data class AppearanceItem(val id: String, val label: String)

object AppearanceCatalog {
    val wallpapers = listOf(
        AppearanceItem("ink", "墨色"),
        AppearanceItem("dusk", "暮色"),
        AppearanceItem("ocean", "海湾"),
        AppearanceItem("forest", "林间"),
        AppearanceItem("ember", "余烬"),
        AppearanceItem("night", "夜航"),
        AppearanceItem("sand", "沙丘"),
        AppearanceItem("mist", "薄雾"),
        AppearanceItem("aurora", "极光"),
        AppearanceItem("paper", "纸页"),
        AppearanceItem("copper", "暖铜"),
        AppearanceItem("plum", "梅子"),
    )
    val accents = listOf(
        AppearanceItem("#E2B15A", "暖金"),
        AppearanceItem("#6FCFC0", "青瓷"),
        AppearanceItem("#7C9CFF", "晴蓝"),
        AppearanceItem("#E07A6A", "珊瑚"),
        AppearanceItem("#C084FC", "藤紫"),
        AppearanceItem("#8FCB7B", "新绿"),
        AppearanceItem("#F2E7D5", "米白"),
        AppearanceItem("#E36B8A", "桃"),
    )
    val modes = listOf(
        AppearanceItem("dark", "深色"),
        AppearanceItem("light", "浅色"),
        AppearanceItem("system", "跟随系统"),
    )
    val fonts = listOf(
        AppearanceItem("small", "小"),
        AppearanceItem("medium", "标准"),
        AppearanceItem("large", "大"),
        AppearanceItem("xlarge", "特大"),
    )
    val solids = listOf(
        AppearanceItem("#12151C", "炭黑"),
        AppearanceItem("#1C1915", "咖"),
        AppearanceItem("#1A2744", "藏青"),
        AppearanceItem("#1E2A22", "松绿"),
        AppearanceItem("#3A241C", "棕"),
        AppearanceItem("#F4F1EA", "米"),
        AppearanceItem("#E7EEF2", "雾白"),
        AppearanceItem("#F3E6D8", "杏"),
    )
    val posters = listOf(
        AppearanceItem("small", "小"),
        AppearanceItem("medium", "中"),
        AppearanceItem("large", "大"),
    )
    val engines = listOf(
        AppearanceItem("vlc", "VLC"),
        AppearanceItem("exo", "系统 (ExoPlayer)"),
    )
    val decoders = listOf(
        AppearanceItem("hardware", "硬件"),
        AppearanceItem("software", "软件"),
    )
    val speeds = listOf(
        AppearanceItem("0.75", "0.75x"),
        AppearanceItem("1", "正常"),
        AppearanceItem("1.25", "1.25x"),
        AppearanceItem("1.5", "1.5x"),
        AppearanceItem("2", "2x"),
    )
    val aspects = listOf(
        AppearanceItem("fit", "适应"),
        AppearanceItem("fill", "铺满"),
        AppearanceItem("zoom", "放大"),
        AppearanceItem("16:9", "16:9"),
        AppearanceItem("4:3", "4:3"),
    )
    val startups = listOf(
        AppearanceItem("vod", "点播"),
        AppearanceItem("live", "直播"),
    )
    val timeouts = listOf(3, 5, 8, 12, 20).map { AppearanceItem(it.toString(), "$it 秒") }
    val lineModes = listOf(
        AppearanceItem("on", "开"),
        AppearanceItem("off", "关"),
    )

    val wallpaperIds = wallpapers.map { it.id }.toSet()
    val fontIds = fonts.map { it.id }.toSet()

    fun label(items: List<AppearanceItem>, id: String, fallback: String): String =
        items.firstOrNull { it.id.equals(id, ignoreCase = true) }?.label ?: fallback
}

fun HomeRowSetting.displayTitle(): String = when (id) {
    "history" -> "最近播放"
    "favorite" -> "收藏"
    else -> title
}

fun AppSettings.modeLabel(): String = AppearanceCatalog.label(AppearanceCatalog.modes, themeMode, "深色")

fun AppSettings.accentLabel(): String = AppearanceCatalog.label(AppearanceCatalog.accents, accent, accent)

fun AppSettings.wallpaperLabel(): String = when (backgroundType) {
    "none" -> "无"
    "solid" -> "纯色 · " + AppearanceCatalog.label(AppearanceCatalog.solids, solidColor, solidColor)
    "image" -> "自定义图片"
    else -> AppearanceCatalog.label(AppearanceCatalog.wallpapers, wallpaperId, "墨色")
}

fun AppSettings.fontLabel(): String = AppearanceCatalog.label(AppearanceCatalog.fonts, fontScale, "标准")

fun AppSettings.posterLabel(): String = AppearanceCatalog.label(AppearanceCatalog.posters, posterSize, "中")

fun AppSettings.engineLabel(): String = if (playerEngine == "exo") "系统 (ExoPlayer)" else "VLC"

fun AppSettings.decoderLabel(): String = if (decoder == "software") "软件" else "硬件"

fun AppSettings.speedLabel(): String = when {
    defaultSpeed < 0.9f -> "0.75x"
    defaultSpeed < 1.1f -> "正常"
    defaultSpeed < 1.4f -> "1.25x"
    defaultSpeed < 1.8f -> "1.5x"
    else -> "2x"
}

fun AppSettings.aspectLabel(): String = AppearanceCatalog.label(AppearanceCatalog.aspects, aspect, "适应")

fun AppSettings.startupLabel(): String = if (startupPage == "live") "直播" else "点播"

fun AppSettings.withAppearance(
    themeMode: String? = null,
    accent: String? = null,
    backgroundType: String? = null,
    wallpaperId: String? = null,
    solidColor: String? = null,
    backgroundImageUrl: String? = null,
    wallpaperBlur: Int? = null,
    wallpaperDim: Int? = null,
    fontScale: String? = null,
): AppSettings = copy(
    themeMode = themeMode ?: this.themeMode,
    accent = accent ?: this.accent,
    backgroundType = backgroundType ?: this.backgroundType,
    wallpaperId = wallpaperId ?: this.wallpaperId,
    gradientId = when {
        wallpaperId == null -> this.gradientId
        wallpaperId in AppSettings.GRADIENTS -> wallpaperId
        else -> this.gradientId
    },
    solidColor = solidColor ?: this.solidColor,
    backgroundImageUrl = backgroundImageUrl ?: this.backgroundImageUrl,
    wallpaperBlur = wallpaperBlur ?: this.wallpaperBlur,
    wallpaperDim = wallpaperDim ?: this.wallpaperDim,
    fontScale = fontScale ?: this.fontScale,
)

fun AppSettings.resetSection(section: String): AppSettings {
    val fresh = AppSettings()
    return when (section) {
        "look" -> copy(
            themeMode = fresh.themeMode,
            accent = fresh.accent,
            backgroundType = fresh.backgroundType,
            gradientId = fresh.gradientId,
            backgroundImageUrl = "",
            wallpaperId = fresh.wallpaperId,
            solidColor = fresh.solidColor,
            wallpaperBlur = fresh.wallpaperBlur,
            wallpaperDim = fresh.wallpaperDim,
            fontScale = fresh.fontScale,
        )
        "home" -> copy(homeRows = HomeRowSetting.defaults(), posterSize = fresh.posterSize)
        "play" -> copy(
            playerEngine = fresh.playerEngine,
            decoder = fresh.decoder,
            defaultSpeed = fresh.defaultSpeed,
            aspect = fresh.aspect,
            startupPage = fresh.startupPage,
        )
        "lines" -> copy(
            defaultSourceId = "",
            searchTimeoutSec = fresh.searchTimeoutSec,
            autoLineSelect = fresh.autoLineSelect,
        )
        else -> this
    }
}
