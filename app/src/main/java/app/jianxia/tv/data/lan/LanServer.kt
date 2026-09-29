package app.jianxia.tv.data.lan

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.jianxia.core.UserFacingError
import app.jianxia.core.model.AppearanceCatalog
import app.jianxia.core.model.AppearanceItem
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.ShelfToggle
import app.jianxia.core.model.UiDiy
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.withAppearance
import app.jianxia.tv.data.repo.BackupRepository
import app.jianxia.tv.data.repo.SettingsRepository
import app.jianxia.tv.data.repo.SourceRepository
import app.jianxia.tv.data.repo.SubtitleStore
import app.jianxia.tv.PlaybackSession
import app.jianxia.tv.ui.push.directPlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class LanStatus(
    val running: Boolean = false,
    val host: String? = null,
    val port: Int = 0,
    val pin: String = "",
    val error: String? = null,
)

class LanServer(
    private val context: Context,
    private val sources: SourceRepository,
    private val backup: BackupRepository,
    private val settings: SettingsRepository,
    private val subtitles: SubtitleStore,
    private val session: PlaybackSession,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val pinValue = (1000..9999).random().toString()
    private val _status = MutableStateFlow(LanStatus(pin = pinValue))
    val status: StateFlow<LanStatus> = _status.asStateFlow()

    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = start()
        override fun onStop(owner: LifecycleOwner) = stop()
    }

    fun refreshPin() {
        val next = (1000..9999).random().toString()
        _status.value = _status.value.copy(pin = next)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                val server = bind()
                socket = server
                _status.value = LanStatus(
                    running = true,
                    host = lanAddress(),
                    port = server.localPort,
                    pin = _status.value.pin.ifBlank { pinValue },
                    error = null,
                )
                android.util.Log.i(
                    "JianXia",
                    "局域网页面 http://${_status.value.host ?: "设备"}:${server.localPort}/?pin=${_status.value.pin}",
                )
                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    pool.execute {
                        try {
                            handle(client)
                        } catch (_: Exception) {
                            // 单次请求失败不影响服务。
                        } finally {
                            runCatching { client.close() }
                        }
                    }
                }
            } catch (error: Exception) {
                _status.value = _status.value.copy(running = false, error = "局域网服务没有启动")
            } finally {
                running.set(false)
            }
        }, "jianxia-lan").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        _status.value = _status.value.copy(running = false)
    }

    private fun bind(): ServerSocket {
        val ports = listOf(8765, 8766, 8767, 8899, 0)
        var last: Exception? = null
        for (port in ports) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            } catch (error: Exception) {
                last = error
            }
        }
        throw last ?: IllegalStateException("没有可用端口")
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 8_000
        val request = readRequest(socket) ?: return
        socket.soTimeout = 60_000
        val pin = queryPin(request.path) ?: header(request.headers, "x-pin")
        val route = request.path.substringBefore("?")
        if (request.method == "GET" && route.startsWith("/wallpapers/") && route.endsWith(".webp")) {
            val id = route.removePrefix("/wallpapers/").removeSuffix(".webp")
            val bytes = if (id in AppearanceCatalog.wallpaperIds) {
                runCatching { context.assets.open("wallpapers/$id.webp").use { it.readBytes() } }.getOrNull()
            } else {
                null
            }
            if (bytes == null) {
                write(socket, 404, "application/json", errorJson("没有这张壁纸").toByteArray())
            } else {
                writeRaw(socket, 200, "image/webp", bytes)
            }
            return
        }
        if (request.method == "GET" && route == "/wallpaper/custom") {
            if (pin == null || pin != _status.value.pin) {
                write(socket, 401, "application/json", errorJson("口令不正确").toByteArray())
                return
            }
            val file = wallpaperFile()
            if (!file.isFile) {
                write(socket, 404, "application/json", errorJson("还没有上传壁纸").toByteArray())
            } else {
                writeRaw(socket, 200, "image/jpeg", file.readBytes())
            }
            return
        }
        if (route == "/" || route.isEmpty()) {
            val ok = pin != null && pin == _status.value.pin
            write(socket, 200, "text/html", page(ok).toByteArray())
            return
        }
        if (pin == null || pin != _status.value.pin) {
            write(socket, 401, "application/json", errorJson("口令不正确").toByteArray())
            return
        }
        val body = request.body.decodeToString()
        val result = runBlocking {
            runCatching {
                when {
                    request.method == "GET" && route == "/api/sources" -> sourcesJson()
                    request.method == "GET" && route == "/api/appearance" -> appearanceJson()
                    request.method == "GET" && route == "/api/drive" -> driveJson()
                    request.method == "POST" && route == "/api/drive" -> saveDrive(body)
                    request.method == "POST" && route == "/api/push" -> pushPlay(body)
                    request.method == "POST" && route == "/api/search" -> pushSearch(body)
                    request.method == "POST" && route == "/api/appearance" -> {
                        val obj = parseObject(body)
                        when (obj.str("reset")) {
                            "look" -> settings.update { it.resetSection("look") }
                            "diy" -> settings.update { it.resetSection("diy") }
                            else -> settings.update { current -> applyAppearance(current, obj) }
                        }
                        appearanceJson("已保存")
                    }
                    request.method == "POST" && route == "/api/wallpaper" -> saveWallpaper(body)
                    request.method == "GET" && route == "/api/export" -> backup.export()
                    request.method == "POST" && route == "/api/sources" -> {
                        val obj = parseObject(body)
                        val text = obj.raw("text") ?: obj.raw("url").orEmpty()
                        batchJson(sources.addMany(text, obj.str("name"), obj.str("epg")))
                    }
                    request.method == "POST" && route == "/api/sources/batch" -> {
                        val obj = parseObject(body)
                        val text = obj.raw("text") ?: obj.raw("url").orEmpty()
                        batchJson(sources.addMany(text, obj.str("name"), obj.str("epg")))
                    }
                    request.method == "POST" && route == "/api/sources/update" -> {
                        val obj = parseObject(body)
                        val message = sources.update(
                            obj.str("id").orEmpty(),
                            obj.str("name").orEmpty(),
                            obj.str("url").orEmpty(),
                            obj.str("epg"),
                        )
                        sourcesJson(message)
                    }
                    request.method == "POST" && route == "/api/sources/delete" -> {
                        sources.delete(parseObject(body).str("id").orEmpty())
                        sourcesJson("已删除")
                    }
                    request.method == "POST" && route == "/api/sources/retry" -> {
                        val message = sources.recheck(parseObject(body).str("id").orEmpty())
                        sourcesJson(message)
                    }
                    request.method == "POST" && route == "/api/sources/toggle" -> {
                        val obj = parseObject(body)
                        sources.setEnabled(obj.str("id").orEmpty(), obj.str("enabled") != "false")
                        sourcesJson("已更新")
                    }
                    request.method == "POST" && route == "/api/sources/move" -> {
                        val obj = parseObject(body)
                        sources.move(obj.str("id").orEmpty(), obj.str("direction") == "up")
                        sourcesJson("已调整顺序")
                    }
                    request.method == "GET" && route == "/api/extras" -> extrasJson()
                    request.method == "POST" && route == "/api/extras" -> {
                        val obj = parseObject(body)
                        if (obj.str("reset") == "enhance") {
                            settings.update { it.resetSection("enhance") }
                        } else {
                            settings.update { current ->
                                current.copy(
                                    doubanEnabled = obj.bool("doubanEnabled") ?: current.doubanEnabled,
                                    doubanDataProxy = obj.str("doubanDataProxy") ?: current.doubanDataProxy,
                                    doubanDataProxyUrl = obj.raw("doubanDataProxyUrl") ?: current.doubanDataProxyUrl,
                                    doubanImageProxy = obj.str("doubanImageProxy") ?: current.doubanImageProxy,
                                    doubanImageProxyUrl = obj.raw("doubanImageProxyUrl") ?: current.doubanImageProxyUrl,
                                    skipHlsAds = obj.bool("skipHlsAds") ?: current.skipHlsAds,
                                    hlsAdRules = obj.raw("hlsAdRules") ?: current.hlsAdRules,
                                    danmakuApiUrl = obj.raw("danmakuApiUrl") ?: current.danmakuApiUrl,
                                    danmakuApiToken = obj.raw("danmakuApiToken") ?: current.danmakuApiToken,
                                    danmakuEnabled = obj.bool("danmakuEnabled") ?: current.danmakuEnabled,
                                    danmakuOpacity = obj.int("danmakuOpacity") ?: current.danmakuOpacity,
                                    danmakuFont = obj.str("danmakuFont") ?: current.danmakuFont,
                                    danmakuSpeed = obj.str("danmakuSpeed") ?: current.danmakuSpeed,
                                    danmakuDensity = obj.int("danmakuDensity") ?: current.danmakuDensity,
                                    danmakuArea = obj.str("danmakuArea") ?: current.danmakuArea,
                                    danmakuBlockWords = obj.raw("danmakuBlockWords") ?: current.danmakuBlockWords,
                                    subtitleSize = obj.str("subtitleSize") ?: current.subtitleSize,
                                    subtitlePosition = obj.str("subtitlePosition") ?: current.subtitlePosition,
                                )
                            }
                        }
                        extrasJson("已保存")
                    }
                    request.method == "POST" && route == "/api/subtitle" -> {
                        val obj = parseObject(body)
                        val name = obj.str("name") ?: "subtitle.srt"
                        val bytes = when {
                            !obj.raw("base64").isNullOrBlank() -> android.util.Base64.decode(obj.raw("base64"), android.util.Base64.DEFAULT)
                            obj.raw("text") != null -> obj.raw("text").orEmpty().toByteArray()
                            else -> throw IllegalArgumentException("没有字幕内容")
                        }
                        if (bytes.isEmpty()) throw IllegalArgumentException("字幕是空的")
                        if (bytes.size > SubtitleStore.MAX_BYTES) throw IllegalArgumentException("字幕文件太大")
                        val file = subtitles.save(name, bytes)
                        buildJsonObject {
                            put("ok", true)
                            put("message", "已保存 ${file.name}")
                            put("name", file.name)
                        }.toString()
                    }
                    request.method == "POST" && route == "/api/import" -> {
                        buildJsonObject {
                            put("ok", true)
                            put("message", backup.import(body))
                        }.toString()
                    }
                    else -> error("没有这个操作")
                }
            }
        }
        result.fold(
            onSuccess = { payload ->
                if (route == "/api/export") {
                    write(socket, 200, "application/json", payload.toByteArray(), "jianxia-backup.json")
                } else {
                    write(socket, 200, "application/json", payload.toByteArray())
                }
            },
            onFailure = { error ->
                write(socket, 400, "application/json", errorJson(UserFacingError.message(error)).toByteArray())
            },
        )
    }

    private suspend fun batchJson(lines: List<app.jianxia.core.backup.BatchLine>): String {
        val message = app.jianxia.core.backup.BatchAdd.message(lines)
        val items = sources.list()
        return buildJsonObject {
            put("ok", true)
            put("message", message)
            put("sources", buildJsonArray {
                items.forEach { source ->
                    add(buildJsonObject {
                        put("id", source.id)
                        put("name", source.name)
                        put("url", source.url)
                        put("kind", source.kind)
                        put("epgUrl", source.epgUrl.orEmpty())
                        put("enabled", source.enabled)
                        put("note", source.note)
                    })
                }
            })
            put("results", buildJsonArray {
                lines.forEach { line ->
                    add(buildJsonObject {
                        put("url", line.url)
                        put("status", line.status)
                        put("detail", when (line.status) {
                            "ok" -> "成功"
                            "exists" -> "已存在"
                            else -> line.detail
                        })
                    })
                }
            })
        }.toString()
    }

    private suspend fun sourcesJson(message: String = "ok"): String {
        val items = sources.list()
        return buildJsonObject {
            put("ok", true)
            put("message", message)
            put("sources", buildJsonArray {
                items.forEach { source ->
                    add(buildJsonObject {
                        put("id", source.id)
                        put("name", source.name)
                        put("url", source.url)
                        put("kind", source.kind)
                        put("epgUrl", source.epgUrl.orEmpty())
                        put("enabled", source.enabled)
                        put("note", source.note)
                    })
                }
            })
        }.toString()
    }

    private fun driveJson(message: String = "ok"): String {
        val current = settings.state.value
        return buildJsonObject {
            put("ok", true)
            put("message", message)
            put("quark", current.quarkCookie.isNotBlank())
            put("uc", current.ucCookie.isNotBlank())
            put("ali", current.aliToken.isNotBlank())
        }.toString()
    }

    private suspend fun saveDrive(body: String): String {
        val obj = parseObject(body)
        settings.update { current ->
            current.copy(
                quarkCookie = obj.raw("quark") ?: current.quarkCookie,
                ucCookie = obj.raw("uc") ?: current.ucCookie,
                aliToken = obj.raw("ali") ?: current.aliToken,
            )
        }
        return driveJson("已保存。回到电视重新打开影片后再播放。")
    }

    private fun pushPlay(body: String): String {
        val url = parseObject(body).raw("url").orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("地址需要以 http:// 或 https:// 开头")
        }
        session.request = directPlay(url)
        session.pushPlay(url)
        return buildJsonObject {
            put("ok", true)
            put("message", "已推送到电视播放")
        }.toString()
    }

    private fun pushSearch(body: String): String {
        val query = (parseObject(body).raw("query") ?: parseObject(body).raw("text")).orEmpty().trim()
        if (query.isBlank()) throw IllegalArgumentException("请输入要搜索的片名")
        session.pushSearch(query)
        return buildJsonObject {
            put("ok", true)
            put("message", "已在电视上搜索")
        }.toString()
    }

    private fun appearanceJson(message: String = "ok"): String {
        val current = settings.state.value
        return buildJsonObject {
            put("ok", true)
            put("message", message)
            put("settings", buildJsonObject {
                put("themeMode", current.themeMode)
                put("accent", current.accent)
                put("backgroundType", current.backgroundType)
                put("wallpaperId", current.wallpaperId)
                put("solidColor", current.solidColor)
                put("backgroundImageUrl", current.backgroundImageUrl)
                put("wallpaperBlur", current.wallpaperBlur)
                put("wallpaperDim", current.wallpaperDim)
                put("fontScale", current.fontScale)
                put("posterSize", current.posterSize)
                put("posterColumns", current.posterColumns)
                put("tileAlpha", current.tileAlpha)
                put("cornerRadius", current.cornerRadius)
                put("showRating", current.showRating)
                put("showYear", current.showYear)
                put("showQuality", current.showQuality)
                put("showDoubanBadge", current.showDoubanBadge)
                put("showClock", current.showClock)
                put("homeShell", current.homeShell)
                put("homeRail", current.homeRail)
                put("playerBar", current.playerBar)
                put("homeActions", toggleArray(UiDiy.actionsOf(current)))
                put("homeTabs", toggleArray(current.homeTabs.ifEmpty { listOf(ShelfToggle("home", "主页")) }))
            })
            put("wallpapers", catalog(AppearanceCatalog.wallpapers))
            put("accents", catalog(AppearanceCatalog.accents))
            put("modes", catalog(AppearanceCatalog.modes))
            put("fonts", catalog(AppearanceCatalog.fonts.filter { it.id != "xlarge" }))
            put("solids", catalog(AppearanceCatalog.solids))
            put("posters", catalog(AppearanceCatalog.posters))
            put("shells", catalog(listOf(AppearanceItem("warehouse", "影视仓"), AppearanceItem("cinema", "影院"))))
            put("rails", catalog(listOf(AppearanceItem("left", "左侧竖排"), AppearanceItem("top", "顶部横排"))))
            put("bars", catalog(listOf(AppearanceItem("full", "完整"), AppearanceItem("slim", "精简"), AppearanceItem("float", "悬浮"))))
            put("columns", catalog(listOf(4, 5, 6).map { AppearanceItem(it.toString(), "$it 列") }))
        }.toString()
    }

    private fun toggleArray(items: List<ShelfToggle>) = buildJsonArray {
        items.forEach { item ->
            add(buildJsonObject {
                put("id", item.id)
                put("title", item.title)
                put("visible", item.visible)
            })
        }
    }

    private fun applyAppearance(current: AppSettings, obj: JsonObject): AppSettings = current.withAppearance(
        themeMode = obj.str("themeMode"),
        accent = obj.str("accent"),
        backgroundType = obj.str("backgroundType"),
        wallpaperId = obj.str("wallpaperId"),
        solidColor = obj.str("solidColor"),
        backgroundImageUrl = if (obj.containsKey("backgroundImageUrl")) obj.raw("backgroundImageUrl").orEmpty() else null,
        wallpaperBlur = obj.int("wallpaperBlur"),
        wallpaperDim = obj.int("wallpaperDim"),
        fontScale = obj.str("fontScale"),
    ).copy(
        posterSize = obj.str("posterSize") ?: current.posterSize,
        posterColumns = obj.int("posterColumns") ?: current.posterColumns,
        tileAlpha = obj.int("tileAlpha") ?: current.tileAlpha,
        cornerRadius = obj.int("cornerRadius") ?: current.cornerRadius,
        showRating = obj.bool("showRating") ?: current.showRating,
        showYear = obj.bool("showYear") ?: current.showYear,
        showQuality = obj.bool("showQuality") ?: current.showQuality,
        showDoubanBadge = obj.bool("showDoubanBadge") ?: current.showDoubanBadge,
        showClock = obj.bool("showClock") ?: current.showClock,
        homeShell = obj.str("homeShell") ?: current.homeShell,
        homeRail = obj.str("homeRail") ?: current.homeRail,
        playerBar = obj.str("playerBar") ?: current.playerBar,
        homeActions = obj.toggles("homeActions") ?: current.homeActions,
        homeTabs = obj.toggles("homeTabs") ?: current.homeTabs,
    )

    private suspend fun saveWallpaper(body: String): String {
        val encoded = parseObject(body).raw("base64").orEmpty().trim()
        if (encoded.isEmpty()) throw IllegalArgumentException("没有图片")
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > 4_000_000) throw IllegalArgumentException("图片请小于 4MB")
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("这不是能用的图片")
        bitmap.recycle()
        val file = wallpaperFile()
        file.writeBytes(bytes)
        settings.update { it.withAppearance(backgroundType = "image", backgroundImageUrl = file.toURI().toString()) }
        return appearanceJson("壁纸已换上")
    }

    private fun wallpaperFile() = File(context.filesDir, "diy-wallpaper.img")

    private fun JsonObject.toggles(key: String): List<ShelfToggle>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("id") ?: return@mapNotNull null
            ShelfToggle(id, item.str("title") ?: id, item.bool("visible") ?: true)
        }
    }

    private fun catalog(items: List<AppearanceItem>) = buildJsonArray {
        items.forEach { item ->
            add(buildJsonObject {
                put("id", item.id)
                put("label", item.label)
            })
        }
    }

    private fun parseObject(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        throw IllegalArgumentException("请求内容无法识别")
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.raw(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = when (this[key]?.jsonPrimitive?.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private suspend fun extrasJson(message: String? = null): String {
        val current = settings.state.value
        return buildJsonObject {
            put("ok", true)
            if (message != null) put("message", message)
            put("doubanEnabled", current.doubanEnabled)
            put("doubanDataProxy", current.doubanDataProxy)
            put("doubanDataProxyUrl", current.doubanDataProxyUrl)
            put("doubanImageProxy", current.doubanImageProxy)
            put("doubanImageProxyUrl", current.doubanImageProxyUrl)
            put("skipHlsAds", current.skipHlsAds)
            put("hlsAdRules", current.hlsAdRules)
            put("danmakuApiUrl", current.danmakuApiUrl)
            put("danmakuApiToken", current.danmakuApiToken)
            put("danmakuEnabled", current.danmakuEnabled)
            put("danmakuBlockWords", current.danmakuBlockWords)
            put("subtitleSize", current.subtitleSize)
            put("subtitlePosition", current.subtitlePosition)
        }.toString()
    }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun errorJson(message: String) = """{"ok":false,"message":${jsonString(message)}}"""

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(char)
                }
            }
            append('"')
        }

    private fun queryPin(path: String): String? =
        path.substringAfter("?", "").split("&").mapNotNull {
            val key = it.substringBefore("=")
            val value = it.substringAfter("=", "")
            if (key == "pin") java.net.URLDecoder.decode(value, "UTF-8") else null
        }.firstOrNull()

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun readRequest(socket: Socket): RawRequest? {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        val one = ByteArray(1)
        while (header.size() < 32_768) {
            val read = input.read(one)
            if (read < 0) return null
            header.write(one, 0, read)
            val bytes = header.toByteArray()
            if (bytes.size >= 4 &&
                bytes[bytes.size - 4] == '\r'.code.toByte() &&
                bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() &&
                bytes[bytes.size - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        val text = header.toString(Charsets.UTF_8.name())
        val blocks = text.split("\r\n\r\n", limit = 2)
        val lines = blocks[0].split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }.toMap()
        val length = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
        if (length > 6_000_000) return null
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return RawRequest(requestLine[0], requestLine[1], headers, body.copyOf(offset))
    }

    private fun write(socket: Socket, code: Int, type: String, body: ByteArray, download: String? = null) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            else -> "Error"
        }
        val disposition = if (download != null) "Content-Disposition: attachment; filename=\"$download\"\r\n" else ""
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n$disposition\r\n"
        val output = socket.getOutputStream()
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun writeRaw(socket: Socket, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: public, max-age=86400\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun page(unlocked: Boolean): String {
        val raw = if (unlocked) UNLOCKED_PAGE else LOCKED_PAGE
        val crash = app.jianxia.tv.CrashStore.read(context.filesDir)
        if (crash.isBlank()) return raw
        val safe = crash.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val block = "<h2>最近一次崩溃</h2><pre style=\"white-space:pre-wrap\">$safe</pre>"
        return raw.replace("</main>", "$block</main>")
    }

    private companion object {
        private const val LOCKED_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>个人影院</title>
            <style>body{margin:0;font-family:sans-serif;background:#12151c;color:#f4f1ea}main{max-width:640px;margin:0 auto;padding:24px}p{color:#b7b1a6}</style>
            </head><body><main><h1>个人影院</h1><p>请扫描电视上的二维码打开这个页面。口令只显示在电视屏幕上。</p></main></body></html>
        """
        private const val UNLOCKED_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>个人影院</title>
            <style>
            body{margin:0;font-family:sans-serif;background:#12151c;color:#f4f1ea}
            main{max-width:640px;margin:0 auto;padding:24px}
            p{color:#b7b1a6;line-height:1.6}
            input,textarea,button{font:inherit}
            input,textarea{width:100%;box-sizing:border-box;background:#1c2230;color:#f4f1ea;border:1px solid #343b4d;border-radius:12px;padding:12px;margin:6px 0 12px}
            button{background:#e2b15a;color:#1c1915;border:0;border-radius:999px;padding:10px 16px;margin:4px 6px 4px 0}
            button.ghost{background:#1c2230;color:#f4f1ea}
            .card{background:#1a2030;border-radius:16px;padding:14px;margin:10px 0}
            .row{display:flex;gap:8px;flex-wrap:wrap}
            select{width:100%;box-sizing:border-box;background:#1c2230;color:#f4f1ea;border:1px solid #343b4d;border-radius:12px;padding:12px;margin:6px 0 12px}
            input[type=range]{width:100%;margin:8px 0 14px}
            .thumbs,.swatches{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 12px}
            .thumbs button,.swatches button{padding:0;border:2px solid transparent;background:#1c2230;border-radius:12px;overflow:hidden}
            .thumbs button.on,.swatches button.on{border-color:#e2b15a}
            .thumbs img{width:96px;height:54px;object-fit:cover;display:block}
            .swatches i{display:block;width:56px;height:36px}
            </style></head><body><main>
            <h1>个人影院</h1>
            <h2>推送到电视</h2>
            <p>播放地址会直接在电视上打开。搜索词会打开电视的搜索页。网盘 Cookie 只保存在这台电视上，不会上传到别处。</p>
            <label>播放地址<input id="pushUrl" placeholder="https:// 视频地址，m3u8 或 mp4"></label>
            <button onclick="pushPlay()">推送到电视播放</button>
            <label>搜索片名<input id="searchWord" placeholder="输入片名"></label>
            <button onclick="pushSearch()">搜索</button>
            <p id="pushMsg"></p>
            <h2>网盘 Cookie</h2>
            <p>夸克：电脑浏览器登录 pan.quark.cn，按 F12，在网络里点任意请求，复制请求头 Cookie。UC 打开 drive.uc.cn 同样复制。阿里云盘粘贴自己的 refresh_token。</p>
            <label>夸克 Cookie<textarea id="quarkCookie" rows="3" placeholder="粘贴夸克 Cookie"></textarea></label>
            <label>UC Cookie<textarea id="ucCookie" rows="3" placeholder="粘贴 UC Cookie"></textarea></label>
            <label>阿里 token<textarea id="aliToken" rows="2" placeholder="refresh_token"></textarea></label>
            <button onclick="saveDrive()">保存到电视</button>
            <p id="driveMsg"></p>
            <p>在这里粘贴接口地址。点播片源需要自己添加。可以一次粘贴很多网址：每行一个，或和说明文字混在一起。重复的会标成已存在。</p>
            <label>名称（可选，只在添加一个地址时使用）<input id="name" placeholder="例如：家里的配置"></label>
            <label>地址<textarea id="url" rows="5" placeholder="https:// 可以一次粘贴多个"></textarea></label>
            <label>节目单地址（只有一个直播地址时才会用上）<input id="epg" placeholder="XMLTV 地址"></label>
            <button onclick="add()">添加</button>
            <p id="msg"></p>
            <div id="batch"></div>
            <div id="list"></div>
            <h2>外观</h2>
            <p>和电视上的设置是同一份。选好后点保存，电视会马上换上。排版、角标和首页按钮在下面的界面 DIY。</p>
            <label>深浅<select id="themeMode"></select></label>
            <label>颜色<select id="accent"></select></label>
            <div id="swatches" class="swatches"></div>
            <label>壁纸<select id="wallpaperPick"></select></label>
            <div id="thumbs" class="thumbs"></div>
            <label>纯色<select id="solidColor"></select></label>
            <label>自定义图片地址<input id="bgUrl" placeholder="https:// 图片地址，可留空"></label>
            <label>模糊 <span id="blurVal"></span><input id="blur" type="range" min="0" max="24" value="0"></label>
            <label>变暗 <span id="dimVal"></span><input id="dim" type="range" min="0" max="80" value="28"></label>
            <label>文字大小<select id="fontScale"></select></label>
            <div class="row">
              <button onclick="saveLook()">保存外观</button>
              <button class="ghost" onclick="resetLook()">恢复默认外观</button>
            </div>
            <p id="lookMsg"></p>
            <h2>界面 DIY</h2>
            <p>改完会立刻写到电视上。壁纸可以选内置、填网址，或从手机上传一张图。</p>
            <label>首页布局<select id="homeShell"></select></label>
            <label>功能键位置<select id="homeRail"></select></label>
            <label>播放条<select id="playerBar"></select></label>
            <label>海报列数<select id="posterColumns"></select></label>
            <label>海报大小<select id="posterSize"></select></label>
            <label>文字大小<select id="diyFont"></select></label>
            <label>不透明度 <span id="tileAlphaVal"></span><input id="tileAlpha" type="range" min="30" max="100" value="72"></label>
            <label>圆角 <span id="cornerVal"></span><input id="cornerRadius" type="range" min="0" max="28" value="12"></label>
            <label><input id="showClock" type="checkbox" checked> 显示首页时钟</label>
            <label><input id="showRating" type="checkbox" checked> 评分</label>
            <label><input id="showYear" type="checkbox" checked> 年份</label>
            <label><input id="showQuality" type="checkbox" checked> 清晰度</label>
            <label><input id="showDouban" type="checkbox" checked> 豆瓣热播</label>
            <label>上传壁纸<input id="wallFile" type="file" accept="image/*"></label>
            <button class="ghost" onclick="uploadWall()">上传并使用这张图</button>
            <h3>首页功能键</h3>
            <div id="actionList"></div>
            <h3>首页分类</h3>
            <p>打开过首页之后，站点分类会出现在这里，可以隐藏和排序。</p>
            <div id="tabList"></div>
            <div class="row">
              <button onclick="saveDiy()">保存界面</button>
              <button class="ghost" onclick="resetDiy()">恢复默认</button>
            </div>
            <p id="diyMsg"></p>
            <h2>豆瓣、去广告、弹幕、字幕</h2>
            <p>豆瓣只用来显示评分和短评，点进去会用你自己的接口搜索。弹幕需要自己填写 danmu_api 地址，个人影院不内置弹幕服务器。去广告规则一行一条，按地址正则匹配。</p>
            <label>豆瓣数据<select id="doubanData"><option value="direct">直连</option><option value="img3">img3 图片 CDN</option><option value="custom">自定义前缀</option></select></label>
            <label>豆瓣数据代理<input id="doubanDataUrl" placeholder="https://代理/{url} 或前缀"></label>
            <label>豆瓣图片<select id="doubanImage"><option value="direct">直连</option><option value="img3">img3.doubanio.com</option><option value="custom">自定义前缀</option></select></label>
            <label>豆瓣图片代理<input id="doubanImageUrl" placeholder="https:// 图片代理前缀"></label>
            <label><input id="skipAds" type="checkbox" checked> 跳过 m3u8 广告切片</label>
            <label>广告地址规则<textarea id="adRules" rows="4" placeholder="一行一条正则，例如 ad-slice"></textarea></label>
            <label>弹幕接口<input id="danmakuUrl" placeholder="http:// 你的 danmu_api 根地址"></label>
            <label>弹幕令牌<input id="danmakuToken" placeholder="没有就留空"></label>
            <label>弹幕屏蔽词<textarea id="danmakuWords" rows="3" placeholder="一行一个词，re: 开头表示正则"></textarea></label>
            <label>字幕文件<input id="subFile" type="file" accept=".srt,.vtt,.ass,.ssa,.sup"></label>
            <div class="row">
              <button onclick="saveExtras()">保存这些设置</button>
              <button class="ghost" onclick="uploadSub()">上传字幕</button>
            </div>
            <p id="extraMsg"></p>
            <h2>导入 / 导出</h2>
            <textarea id="backup" rows="6" placeholder="粘贴备份 JSON，或每行写一个网址"></textarea>
            <div class="row">
              <button onclick="importBackup()">导入并覆盖</button>
              <button class="ghost" onclick="exportBackup()">下载备份</button>
            </div>
            <script>
            async function pushPlay(){
              const url = document.getElementById('pushUrl').value.trim();
              try {
                const data = await api('/api/push', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({url:url})});
                document.getElementById('pushMsg').textContent = data.message || '已推送';
              } catch (e) { document.getElementById('pushMsg').textContent = friendly(e); }
            }
            async function pushSearch(){
              const query = document.getElementById('searchWord').value.trim();
              try {
                const data = await api('/api/search', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({query:query})});
                document.getElementById('pushMsg').textContent = data.message || '已搜索';
              } catch (e) { document.getElementById('pushMsg').textContent = friendly(e); }
            }
            async function saveDrive(){
              try {
                const data = await api('/api/drive', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({
                  quark: document.getElementById('quarkCookie').value,
                  uc: document.getElementById('ucCookie').value,
                  ali: document.getElementById('aliToken').value
                })});
                document.getElementById('driveMsg').textContent = data.message || '已保存';
              } catch (e) { document.getElementById('driveMsg').textContent = friendly(e); }
            }
            async function loadExtras(){
              try {
                const data = await api('/api/extras');
                document.getElementById('doubanData').value = data.doubanDataProxy || 'direct';
                document.getElementById('doubanDataUrl').value = data.doubanDataProxyUrl || '';
                document.getElementById('doubanImage').value = data.doubanImageProxy || 'img3';
                document.getElementById('doubanImageUrl').value = data.doubanImageProxyUrl || '';
                document.getElementById('skipAds').checked = data.skipHlsAds !== false;
                document.getElementById('adRules').value = data.hlsAdRules || '';
                document.getElementById('danmakuUrl').value = data.danmakuApiUrl || '';
                document.getElementById('danmakuToken').value = data.danmakuApiToken || '';
                document.getElementById('danmakuWords').value = data.danmakuBlockWords || '';
              } catch (e) { document.getElementById('extraMsg').textContent = friendly(e); }
            }
            async function saveExtras(){
              try {
                const data = await api('/api/extras', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({
                  doubanDataProxy: document.getElementById('doubanData').value,
                  doubanDataProxyUrl: document.getElementById('doubanDataUrl').value,
                  doubanImageProxy: document.getElementById('doubanImage').value,
                  doubanImageProxyUrl: document.getElementById('doubanImageUrl').value,
                  skipHlsAds: document.getElementById('skipAds').checked,
                  hlsAdRules: document.getElementById('adRules').value,
                  danmakuApiUrl: document.getElementById('danmakuUrl').value,
                  danmakuApiToken: document.getElementById('danmakuToken').value,
                  danmakuBlockWords: document.getElementById('danmakuWords').value
                })});
                document.getElementById('extraMsg').textContent = data.message || '已保存';
              } catch (e) { document.getElementById('extraMsg').textContent = friendly(e); }
            }
            async function uploadSub(){
              const file = document.getElementById('subFile').files[0];
              if (!file) { document.getElementById('extraMsg').textContent = '先选择字幕文件'; return; }
              const lower = file.name.toLowerCase();
              const binary = lower.endsWith('.sup') || lower.endsWith('.pgs');
              const payload = {name: file.name};
              if (binary) {
                const buf = await file.arrayBuffer();
                const bytes = new Uint8Array(buf);
                let raw = '';
                for (let i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i]);
                payload.base64 = btoa(raw);
              } else {
                payload.text = await file.text();
              }
              try {
                const data = await api('/api/subtitle', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(payload)});
                document.getElementById('extraMsg').textContent = data.message || '已上传';
              } catch (e) { document.getElementById('extraMsg').textContent = friendly(e); }
            }
            loadExtras();
            const pin = new URLSearchParams(location.search).get('pin');
            const q = pin ? ('?pin=' + encodeURIComponent(pin)) : '';
            function friendly(error){
              const text = (error && error.message) ? String(error.message) : '';
              if (!text || text === 'Load failed' || text === 'Failed to fetch' || text === 'Network request failed' || text.indexOf('NetworkError') === 0 || text === 'The Internet connection appears to be offline.') {
                return '电视没有及时回应。接口可能已经保存，请看下面的列表。';
              }
              if (text.indexOf('Unexpected JSON') >= 0 || text.indexOf('JSON input') >= 0) {
                return '这不是备份文件。如果要添加接口，请每行写一个网址。';
              }
              return text;
            }
            async function api(path, opts){
              const res = await fetch(path + q, opts);
              const data = await res.json().catch(function(){ return {ok:false, message:'电视返回的内容无法识别'}; });
              if(!res.ok || data.ok===false) throw new Error(data.message || '操作没有完成');
              return data;
            }
            function say(text){ document.getElementById('msg').textContent = text || ''; }
            function kindName(item){
              if (item.note) return item.note;
              const map = {tvbox:'TVBox 配置', maccms_json:'苹果 CMS JSON', maccms_xml:'苹果 CMS XML', live:'直播', failed:'加载失败，可重试'};
              return map[item.kind] || '未知格式';
            }
            async function load(){
              const data = await api('/api/sources');
              const box = document.getElementById('list');
              box.innerHTML = '';
              (data.sources||[]).forEach(function(item){
                const card = document.createElement('div');
                card.className = 'card';
                const title = document.createElement('strong');
                title.textContent = item.name + (item.enabled ? '' : '（已停用）');
                const meta = document.createElement('div');
                meta.textContent = kindName(item) + ' · ' + item.url;
                const row = document.createElement('div');
                row.className = 'row';
                row.appendChild(btn('上移', function(){ move(item.id,'up'); }));
                row.appendChild(btn('下移', function(){ move(item.id,'down'); }));
                row.appendChild(btn(item.enabled?'停用':'启用', function(){ toggle(item); }));
                if (item.kind === 'failed' || item.note) row.appendChild(btn('重试', function(){ retry(item.id); }));
                row.appendChild(btn('删除', function(){ remove(item.id); }));
                card.appendChild(title); card.appendChild(meta); card.appendChild(row);
                box.appendChild(card);
              });
              if(!(data.sources||[]).length) box.innerHTML = '<p>还没有接口。</p>';
            }
            function btn(text, action){ const b = document.createElement('button'); b.className='ghost'; b.textContent=text; b.onclick=action; return b; }
            function showBatch(results){
              const box = document.getElementById('batch');
              box.innerHTML = '';
              (results || []).forEach(function(item){
                const card = document.createElement('div');
                card.className = 'card';
                const title = document.createElement('strong');
                const label = item.status === 'ok' ? '成功' : (item.status === 'exists' ? '已存在' : (item.detail || '失败'));
                title.textContent = label;
                const meta = document.createElement('div');
                meta.textContent = item.url || '';
                card.appendChild(title);
                card.appendChild(meta);
                box.appendChild(card);
              });
            }
            async function add(){
              say('添加中…');
              try {
                const data = await api('/api/sources/batch', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({name:document.getElementById('name').value,text:document.getElementById('url').value,epg:document.getElementById('epg').value})});
                document.getElementById('url').value='';
                showBatch(data.results);
                say(data.message || '完成');
                load();
              } catch(e){ say(friendly(e)); load().catch(function(){}); }
            }
            async function retry(id){
              say('添加中…');
              try { const data = await api('/api/sources/retry', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({id:id})}); say(data.message || '成功'); load(); }
              catch(e){ say(friendly(e)); load().catch(function(){}); }
            }
            async function move(id, direction){ try { await api('/api/sources/move', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({id:id,direction:direction})}); load(); } catch(e){ say(friendly(e));} }
            async function toggle(item){ try { await api('/api/sources/toggle', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({id:item.id, enabled: item.enabled?'false':'true'})}); load(); } catch(e){ say(friendly(e));} }
            async function remove(id){ if(!confirm('删除这个接口？')) return; try { await api('/api/sources/delete', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({id:id})}); load(); } catch(e){ say(friendly(e));} }
            async function importBackup(){
              say('添加中…');
              try { const data = await api('/api/import', {method:'POST', headers:{'Content-Type':'text/plain;charset=utf-8'}, body: document.getElementById('backup').value}); say(data.message || '成功'); load(); }
              catch(e){ say(friendly(e)); load().catch(function(){}); }
            }
            function exportBackup(){ location.href = '/api/export' + q; }
            function fillSelect(id, items, value){
              const box = document.getElementById(id);
              box.innerHTML = '';
              (items||[]).forEach(function(item){
                const option = document.createElement('option');
                option.value = item.id;
                option.textContent = item.label;
                if (String(item.id).toLowerCase() === String(value||'').toLowerCase()) option.selected = true;
                box.appendChild(option);
              });
            }
            function loadLook(){
              return api('/api/appearance').then(function(data){
                const s = data.settings || {};
                fillSelect('themeMode', data.modes, s.themeMode);
                fillSelect('accent', data.accents, s.accent);
                fillSelect('fontScale', data.fonts, s.fontScale);
                fillSelect('solidColor', data.solids, s.solidColor);
                const picks = [{id:'builtin', label:'内置壁纸'}].concat([{id:'solid', label:'纯色'}, {id:'none', label:'无'}, {id:'image', label:'自定义图片'}]);
                fillSelect('wallpaperPick', picks, s.backgroundType === 'builtin' ? 'builtin' : (s.backgroundType || 'builtin'));
                document.getElementById('bgUrl').value = s.backgroundImageUrl || '';
                document.getElementById('blur').value = s.wallpaperBlur || 0;
                document.getElementById('dim').value = s.wallpaperDim || 0;
                document.getElementById('blurVal').textContent = document.getElementById('blur').value;
                document.getElementById('dimVal').textContent = document.getElementById('dim').value;
                const thumbs = document.getElementById('thumbs');
                thumbs.innerHTML = '';
                (data.wallpapers||[]).forEach(function(item){
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.className = (s.backgroundType === 'builtin' && s.wallpaperId === item.id) ? 'on' : '';
                  button.title = item.label;
                  const img = document.createElement('img');
                  img.alt = item.label;
                  img.src = '/wallpapers/' + encodeURIComponent(item.id) + '.webp';
                  button.appendChild(img);
                  button.onclick = function(){
                    document.getElementById('wallpaperPick').value = 'builtin';
                    thumbs.querySelectorAll('button').forEach(function(node){ node.className = ''; });
                    button.className = 'on';
                    button.dataset.id = item.id;
                    thumbs.dataset.picked = item.id;
                  };
                  button.dataset.id = item.id;
                  thumbs.appendChild(button);
                });
                thumbs.dataset.picked = s.wallpaperId || '';
                const swatches = document.getElementById('swatches');
                swatches.innerHTML = '';
                (data.accents||[]).forEach(function(item){
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.className = String(s.accent||'').toLowerCase() === String(item.id).toLowerCase() ? 'on' : '';
                  button.title = item.label;
                  const chip = document.createElement('i');
                  chip.style.background = item.id;
                  button.appendChild(chip);
                  button.onclick = function(){
                    document.getElementById('accent').value = item.id;
                    swatches.querySelectorAll('button').forEach(function(node){ node.className = ''; });
                    button.className = 'on';
                  };
                  swatches.appendChild(button);
                });
                loadDiy(data);
              });
            }
            let diyActions = [];
            let diyTabs = [];
            function loadDiy(data){
              const s = (data && data.settings) || {};
              fillSelect('homeShell', data.shells, s.homeShell || 'warehouse');
              fillSelect('homeRail', data.rails, s.homeRail || 'left');
              fillSelect('playerBar', data.bars, s.playerBar || 'full');
              fillSelect('posterColumns', data.columns, String(s.posterColumns || 5));
              fillSelect('posterSize', data.posters, s.posterSize || 'medium');
              fillSelect('diyFont', data.fonts, s.fontScale || 'medium');
              document.getElementById('tileAlpha').value = s.tileAlpha || 72;
              document.getElementById('cornerRadius').value = (s.cornerRadius === 0 || s.cornerRadius) ? s.cornerRadius : 12;
              document.getElementById('tileAlphaVal').textContent = document.getElementById('tileAlpha').value;
              document.getElementById('cornerVal').textContent = document.getElementById('cornerRadius').value;
              document.getElementById('showClock').checked = s.showClock !== false;
              document.getElementById('showRating').checked = s.showRating !== false;
              document.getElementById('showYear').checked = s.showYear !== false;
              document.getElementById('showQuality').checked = s.showQuality !== false;
              document.getElementById('showDouban').checked = s.showDoubanBadge !== false;
              diyActions = s.homeActions || [];
              diyTabs = s.homeTabs || [];
              paintOrder('actionList', diyActions);
              paintOrder('tabList', diyTabs);
            }
            function paintOrder(boxId, items){
              const box = document.getElementById(boxId);
              if (!box) return;
              box.innerHTML = '';
              items.forEach(function(item, index){
                const card = document.createElement('div');
                card.className = 'card';
                const title = document.createElement('strong');
                title.textContent = item.title + (item.visible ? '' : '（已隐藏）');
                const row = document.createElement('div');
                row.className = 'row';
                row.appendChild(btn(item.visible ? '隐藏' : '显示', function(){ item.visible = !item.visible; paintOrder(boxId, items); saveDiy(); }));
                row.appendChild(btn('上移', function(){ if (index === 0) return; const prev = items[index - 1]; items[index - 1] = item; items[index] = prev; paintOrder(boxId, items); saveDiy(); }));
                row.appendChild(btn('下移', function(){ if (index >= items.length - 1) return; const next = items[index + 1]; items[index + 1] = item; items[index] = next; paintOrder(boxId, items); saveDiy(); }));
                card.appendChild(title);
                card.appendChild(row);
                box.appendChild(card);
              });
              if (!items.length) box.innerHTML = '<p>还没有可调整的项目。</p>';
            }
            function diyPayload(){
              return {
                homeShell: document.getElementById('homeShell').value,
                homeRail: document.getElementById('homeRail').value,
                playerBar: document.getElementById('playerBar').value,
                posterColumns: Number(document.getElementById('posterColumns').value),
                posterSize: document.getElementById('posterSize').value,
                fontScale: document.getElementById('diyFont').value,
                tileAlpha: Number(document.getElementById('tileAlpha').value),
                cornerRadius: Number(document.getElementById('cornerRadius').value),
                showClock: document.getElementById('showClock').checked,
                showRating: document.getElementById('showRating').checked,
                showYear: document.getElementById('showYear').checked,
                showQuality: document.getElementById('showQuality').checked,
                showDoubanBadge: document.getElementById('showDouban').checked,
                homeActions: diyActions,
                homeTabs: diyTabs
              };
            }
            async function saveDiy(){
              document.getElementById('diyMsg').textContent = '保存中…';
              try {
                const data = await api('/api/appearance', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(diyPayload())});
                document.getElementById('diyMsg').textContent = data.message || '已保存';
              } catch (e) { document.getElementById('diyMsg').textContent = friendly(e); }
            }
            async function resetDiy(){
              document.getElementById('diyMsg').textContent = '保存中…';
              try {
                const data = await api('/api/appearance', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({reset:'diy'})});
                document.getElementById('diyMsg').textContent = data.message || '已恢复';
                await loadLook();
              } catch (e) { document.getElementById('diyMsg').textContent = friendly(e); }
            }
            async function uploadWall(){
              const file = document.getElementById('wallFile').files[0];
              if (!file) { document.getElementById('diyMsg').textContent = '先选择一张图片'; return; }
              if (file.size > 4 * 1024 * 1024) { document.getElementById('diyMsg').textContent = '图片请小于 4MB'; return; }
              const buf = await file.arrayBuffer();
              const bytes = new Uint8Array(buf);
              let raw = '';
              for (let i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i]);
              document.getElementById('diyMsg').textContent = '上传中…';
              try {
                const data = await api('/api/wallpaper', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({base64: btoa(raw)})});
                document.getElementById('diyMsg').textContent = data.message || '壁纸已换上';
                await loadLook();
              } catch (e) { document.getElementById('diyMsg').textContent = friendly(e); }
            }
            ['homeShell','homeRail','playerBar','posterColumns','posterSize','diyFont','showClock','showRating','showYear','showQuality','showDouban'].forEach(function(id){
              const node = document.getElementById(id);
              if (node) node.addEventListener('change', saveDiy);
            });
            document.getElementById('tileAlpha').oninput = function(){ document.getElementById('tileAlphaVal').textContent = this.value; };
            document.getElementById('cornerRadius').oninput = function(){ document.getElementById('cornerVal').textContent = this.value; };
            document.getElementById('tileAlpha').onchange = saveDiy;
            document.getElementById('cornerRadius').onchange = saveDiy;
            document.getElementById('blur').oninput = function(){ document.getElementById('blurVal').textContent = this.value; };
            document.getElementById('dim').oninput = function(){ document.getElementById('dimVal').textContent = this.value; };
            async function saveLook(){
              const type = document.getElementById('wallpaperPick').value;
              const picked = document.getElementById('thumbs').dataset.picked || '';
              document.getElementById('lookMsg').textContent = '保存中…';
              try {
                const data = await api('/api/appearance', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({
                  themeMode: document.getElementById('themeMode').value,
                  accent: document.getElementById('accent').value,
                  backgroundType: type,
                  wallpaperId: picked,
                  solidColor: document.getElementById('solidColor').value,
                  backgroundImageUrl: document.getElementById('bgUrl').value,
                  wallpaperBlur: Number(document.getElementById('blur').value),
                  wallpaperDim: Number(document.getElementById('dim').value),
                  fontScale: document.getElementById('fontScale').value
                })});
                document.getElementById('lookMsg').textContent = data.message || '已保存';
              } catch(e){ document.getElementById('lookMsg').textContent = friendly(e); }
            }
            async function resetLook(){
              document.getElementById('lookMsg').textContent = '保存中…';
              try {
                const data = await api('/api/appearance', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({reset:'look'})});
                document.getElementById('lookMsg').textContent = data.message || '已恢复';
                await loadLook();
              } catch(e){ document.getElementById('lookMsg').textContent = friendly(e); }
            }
            load().catch(function(e){ say(friendly(e)); });
            loadLook().catch(function(e){ document.getElementById('lookMsg').textContent = friendly(e); });
            </script>
            </main></body></html>
        """
    }

    private fun lanAddress(): String? {
        val found = mutableListOf<Inet4Address>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") || name.startsWith("virbr")) continue
            for (address in nif.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) found += address
            }
        }
        return (found.firstOrNull { it.isSiteLocalAddress } ?: found.firstOrNull())?.hostAddress
    }
}

private data class RawRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)
