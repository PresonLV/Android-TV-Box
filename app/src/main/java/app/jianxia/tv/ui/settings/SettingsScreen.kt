package app.jianxia.tv.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.core.UserFacingError
import app.jianxia.core.backup.BatchAdd
import app.jianxia.core.live.IptvOrg
import app.jianxia.core.model.AppearanceCatalog
import app.jianxia.core.model.AppearanceItem
import app.jianxia.tv.BuildConfig
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.withAppearance
import app.jianxia.tv.data.db.SourceEntity
import app.jianxia.tv.ui.Keycap
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.Panel
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.kindLabel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(start: String = "root", openCreate: Boolean = false) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val sources by app.sources.observe().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(start) }
    BackHandler(enabled = page != "root") { page = parentPage(page) }
    when (page) {
        "sources" -> SourcesPage(openCreate = openCreate, onBack = { page = parentPage("sources") })
        "look" -> LookHub { page = it }
        "theme" -> ThemeStudio()
        "wallpaper" -> WallpaperStudio { page = "imageUrl" }
        "imageUrl" -> ImageUrlPage { page = "wallpaper" }
        "diy" -> DiyStudio { page = "diyImage" }
        "diyImage" -> ImageUrlPage { page = "diy" }
        "font" -> ChoicePage(
            title = "文字大小",
            choices = AppearanceCatalog.fonts,
            selected = settings.fontScale,
            onSelect = { id -> scope.launch { app.settings.update { it.withAppearance(fontScale = id) } } },
            onReset = { scope.launch { app.settings.update { it.resetSection("look") } } },
        )
        "home" -> HomeStudio()
        "play" -> PlayHub { page = it }
        "engine" -> ChoicePage("播放器内核", AppearanceCatalog.engines, settings.playerEngine, { id ->
            scope.launch { app.settings.update { it.copy(playerEngine = id) } }
        }) { scope.launch { app.settings.update { it.resetSection("play") } } }
        "decoder" -> ChoicePage("解码", AppearanceCatalog.decoders, settings.decoder, { id ->
            scope.launch { app.settings.update { it.copy(decoder = id) } }
        }) { scope.launch { app.settings.update { it.resetSection("play") } } }
        "speed" -> ChoicePage("默认倍速", AppearanceCatalog.speeds, speedId(settings.defaultSpeed), { id ->
            scope.launch { app.settings.update { it.copy(defaultSpeed = id.toFloat()) } }
        }) { scope.launch { app.settings.update { it.resetSection("play") } } }
        "aspect" -> ChoicePage("默认画面", AppearanceCatalog.aspects, settings.aspect, { id ->
            scope.launch { app.settings.update { it.copy(aspect = id) } }
        }) { scope.launch { app.settings.update { it.resetSection("play") } } }
        "startup" -> ChoicePage("启动页", AppearanceCatalog.startups, settings.startupPage, { id ->
            scope.launch { app.settings.update { it.copy(startupPage = id) } }
        }) { scope.launch { app.settings.update { it.resetSection("play") } } }
        "lines" -> LinesHub(
            sourceName = sources.firstOrNull { it.id == settings.defaultSourceId }?.name ?: "全部",
            onOpen = { page = it },
        )
        "source" -> ChoicePage(
            title = "默认来源",
            choices = listOf(AppearanceItem("", "全部")) + sources.map { AppearanceItem(it.id, it.name) },
            selected = settings.defaultSourceId,
            onSelect = { id -> scope.launch { app.settings.update { it.copy(defaultSourceId = id) } } },
            onReset = { scope.launch { app.settings.update { it.resetSection("lines") } } },
        )
        "autoline" -> ChoicePage("自动选线", AppearanceCatalog.lineModes, if (settings.autoLineSelect) "on" else "off", { id ->
            scope.launch { app.settings.update { it.copy(autoLineSelect = id == "on") } }
        }) { scope.launch { app.settings.update { it.resetSection("lines") } } }
        "timeout" -> ChoicePage("搜索超时", AppearanceCatalog.timeouts, settings.searchTimeoutSec.toString(), { id ->
            scope.launch { app.settings.update { it.copy(searchTimeoutSec = id.toInt()) } }
        }) { scope.launch { app.settings.update { it.resetSection("lines") } } }
        "backup" -> BackupPage()
        "enhance" -> EnhancePage()
        "spider" -> SpiderGate { page = "root" }
        "about" -> AboutPage()
        else -> SettingsMenu(BuildConfig.VERSION_NAME) { page = it }
    }
}

private fun parentPage(page: String): String = when (page) {
    "theme", "wallpaper", "font" -> "look"
    "imageUrl" -> "wallpaper"
    "diyImage" -> "diy"
    "engine", "decoder", "speed", "aspect", "startup" -> "play"
    "sources", "source", "autoline", "timeout" -> "lines"
    else -> "root"
}

private fun speedId(value: Float): String = when {
    value < 0.9f -> "0.75"
    value < 1.1f -> "1"
    value < 1.4f -> "1.25"
    value < 1.8f -> "1.5"
    else -> "2"
}

@Composable
private fun SourcesPage(onBack: () -> Unit, openCreate: Boolean = false) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val sources by app.sources.observe().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SourceEntity?>(null) }
    var creating by remember { mutableStateOf(openCreate) }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
            Text("接口与直播源", color = palette.text, fontSize = 26.sp)
            Text(message ?: "推荐先用右侧二维码，在手机上粘贴地址。可以一次粘贴多个网址。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            TvButton("在电视上添加", primary = true) { creating = true }
            if (sources.none { it.id == IptvOrg.ID }) {
                TvButton("添加公共频道", modifier = Modifier.padding(top = 8.dp)) {
                    scope.launch {
                        val current = app.settings.state.value
                        app.sources.applyPublicPlaylist(
                            current.iptvOrgKind,
                            current.iptvOrgCode,
                            IptvOrg.label(current.iptvOrgKind, current.iptvOrgCode),
                        )
                        app.settings.update { it.copy(iptvOrgSeeded = true) }
                        message = "已加入公共频道。频道列表来自 iptv-org"
                    }
                }
            }
            sources.forEach { source ->
                Panel(Modifier.padding(top = 12.dp)) {
                    Text(source.name, color = palette.text, fontSize = 18.sp)
                    Text(
                        "${source.note.ifBlank { kindLabel(source.kind) }}  ·  ${if (source.enabled) "启用" else "停用"}",
                        color = palette.accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(source.url, color = palette.muted, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        TvButton("上移") { scope.launch { app.sources.move(source.id, true) } }
                        TvButton("下移") { scope.launch { app.sources.move(source.id, false) } }
                        TvButton(if (source.enabled) "停用" else "启用") {
                            scope.launch { app.sources.setEnabled(source.id, !source.enabled) }
                        }
                        if (source.kind == "failed" || source.note.isNotBlank()) {
                            TvButton("重试") {
                                scope.launch {
                                    message = runCatching { app.sources.recheck(source.id) }.getOrElse { UserFacingError.message(it) }
                                }
                            }
                        }
                        TvButton("编辑") { editing = source }
                        TvButton("删除") { scope.launch { app.sources.delete(source.id) } }
                    }
                }
            }
            TvButton("返回", modifier = Modifier.padding(top = 16.dp), onClick = onBack)
        }
        PhoneQrCard(onRefreshPin = app.lan::refreshPin, modifier = Modifier.padding(start = 24.dp))
    }
    if (creating || editing != null) {
        SourceDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, url, epg ->
                scope.launch {
                    val result = runCatching {
                        if (editing == null) {
                            BatchAdd.message(app.sources.addMany(url, name, epg))
                        } else {
                            app.sources.update(editing!!.id, name, url, epg)
                        }
                    }
                    message = result.getOrElse { UserFacingError.message(it) }
                    if (result.isSuccess) {
                        creating = false
                        editing = null
                    }
                }
            },
        )
    }
    }
}

@Composable
private fun SourceDialog(initial: SourceEntity?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var epg by remember { mutableStateOf(initial?.epgUrl.orEmpty()) }
    var target by remember { mutableStateOf("url") }
    var uppercase by remember { mutableStateOf(false) }
    var systemIme by remember { mutableStateOf(false) }
    val clipboard = readClipboard(context)
    fun valueOf(which: String) = when (which) {
        "name" -> name
        "epg" -> epg
        else -> url
    }
    fun write(which: String, value: String) {
        when (which) {
            "name" -> name = value
            "epg" -> epg = value
            else -> url = value
        }
    }
    BackHandler(onBack = onDismiss)
    Row(Modifier.fillMaxSize().background(palette.bg).padding(28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(if (initial == null) "添加接口" else "编辑接口", color = palette.text, fontSize = 26.sp)
            Text(
                "当前输入：${if (target == "url") "地址" else if (target == "epg") "节目单" else "名称"}。地址可以一次粘贴多个网址，每行一个，或夹在说明文字里。重复的会标成已存在。",
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Field("名称", name, target == "name") { target = "name" }
            Field("地址", url, target == "url") { target = "url" }
            Field("节目单", epg, target == "epg") { target = "epg" }
            if (systemIme) {
                Text("系统键盘", color = palette.muted, modifier = Modifier.padding(top = 12.dp))
                BasicTextField(
                    value = valueOf(target),
                    onValueChange = { write(target, it) },
                    textStyle = TextStyle(color = palette.text, fontSize = 18.sp),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = if (target == "name") KeyboardType.Text else KeyboardType.Uri),
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp),
                )
            } else {
                listOf(
                    listOf("http://", "https://", "www."),
                    listOf(".com", ".cn", ".net", ".json", ".txt", ".m3u"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        row.forEach { token -> TvButton(token) { write(target, valueOf(target) + token) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    "1234567890".forEach { char ->
                        Keycap(char.toString()) { write(target, valueOf(target) + char) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf(":", "/", ".", "-", "_", "?", "=", "&", "%").forEach { token ->
                        Keycap(token) { write(target, valueOf(target) + token) }
                    }
                }
                listOf("abcdefg", "hijklmn", "opqrstu", "vwxyz").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        row.forEach { char ->
                            val text = if (uppercase) char.uppercase() else char.toString()
                            Keycap(text) { write(target, valueOf(target) + text) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                TvButton(if (uppercase) "小写" else "大写") { uppercase = !uppercase }
                TvButton("退格") { write(target, valueOf(target).dropLast(1)) }
                TvButton("清空") { write(target, "") }
                if (!clipboard.isNullOrBlank()) {
                    TvButton("粘贴") { write(target, clipboard) }
                }
                TvButton(if (systemIme) "使用屏幕键盘" else "使用系统键盘") { systemIme = !systemIme }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                TvButton("保存", primary = true) { onSave(name, url, epg) }
                TvButton("取消", onClick = onDismiss)
            }
        }
        PhoneQrCard(modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun Field(label: String, value: String, selected: Boolean, onFocus: () -> Unit) {
    val palette = LocalPalette.current
    Text(label, color = if (selected) palette.accent else palette.muted, modifier = Modifier.padding(top = 12.dp))
    TvButton(value.ifBlank { "空" }.let { if (it.length > 72) it.take(32) + "…" + it.takeLast(36) else it }, modifier = Modifier.padding(top = 4.dp), primary = selected, onClick = onFocus)
}

@Composable
private fun ImageUrlPage(onDone: () -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by remember(settings.backgroundImageUrl) { mutableStateOf(settings.backgroundImageUrl) }
    var uppercase by remember { mutableStateOf(false) }
    val clipboard = readClipboard(context)
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("自定义壁纸", color = palette.text, fontSize = 26.sp)
        Text("用下面的按键输入图片网址，或在右侧手机页面里粘贴。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
        Text(url.ifBlank { "还没有地址" }, color = palette.accent, modifier = Modifier.padding(bottom = 8.dp))
        listOf(
            listOf("http://", "https://", "www."),
            listOf(".com", ".cn", ".net", ".jpg", ".png", ".webp"),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                row.forEach { token -> TvButton(token) { url += token } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            "1234567890".forEach { char -> Keycap(char.toString()) { url += char } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf(":", "/", ".", "-", "_", "?", "=", "&", "%").forEach { token ->
                Keycap(token) { url += token }
            }
        }
        listOf("abcdefg", "hijklmn", "opqrstu", "vwxyz").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                row.forEach { char ->
                    val text = if (uppercase) char.uppercase() else char.toString()
                    Keycap(text) { url += text }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            TvButton(if (uppercase) "小写" else "大写") { uppercase = !uppercase }
            TvButton("退格") { url = url.dropLast(1) }
            TvButton("清空") { url = "" }
            if (!clipboard.isNullOrBlank()) TvButton("粘贴") { url = clipboard }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            TvButton("使用这张图", primary = true) {
                scope.launch {
                    app.settings.update { it.withAppearance(backgroundType = "image", backgroundImageUrl = url.trim()) }
                    onDone()
                }
            }
            TvButton("返回", onClick = onDone)
        }
    }
}

@Composable
private fun BackupPage() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("整份备份会覆盖当前的接口和设置。外观也可以在手机页面里单独改。") }
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
            Text("数据与备份", color = palette.text, fontSize = 26.sp)
            Text(message, color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            TvButton("复制备份", primary = true) {
                scope.launch {
                    val text = app.backup.export()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("个人影院备份", text))
                    message = "备份已复制。手机页面也可以下载。"
                }
            }
            val pasted = readClipboard(context)
            if (!pasted.isNullOrBlank()) {
                TvButton("导入剪贴板", modifier = Modifier.padding(top = 10.dp)) {
                    scope.launch {
                        message = runCatching { app.backup.import(pasted) }.getOrElse { UserFacingError.message(it) }
                    }
                }
            }
            TvButton("清空搜索记录", modifier = Modifier.padding(top = 10.dp)) {
                scope.launch {
                    app.settings.update { it.copy(recentSearches = emptyList()) }
                    message = "最近搜索已清空。接口还在。"
                }
            }
        }
        PhoneQrCard(modifier = Modifier.padding(start = 24.dp))
    }
}

@Composable
private fun EnhancePage() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("豆瓣与播放增强", color = palette.text, fontSize = 26.sp)
        Text("长地址、广告规则和弹幕令牌用手机页面填写。这里用遥控器开关。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        Text("豆瓣", color = palette.text, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton(if (settings.doubanEnabled) "豆瓣开" else "豆瓣关", primary = settings.doubanEnabled) {
                scope.launch { app.settings.update { it.copy(doubanEnabled = !it.doubanEnabled) } }
            }
        }
        Text("数据", color = palette.muted, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("direct" to "直连", "img3" to "img3", "custom" to "自定义").forEach { (id, label) ->
                TvButton(label, primary = settings.doubanDataProxy == id) {
                    scope.launch { app.settings.update { it.copy(doubanDataProxy = id) } }
                }
            }
        }
        Text("图片", color = palette.muted, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("direct" to "直连", "img3" to "img3", "custom" to "自定义").forEach { (id, label) ->
                TvButton(label, primary = settings.doubanImageProxy == id) {
                    scope.launch { app.settings.update { it.copy(doubanImageProxy = id) } }
                }
            }
        }
        Text("img3 会把海报改到 img3.doubanio.com。自定义前缀在手机页面填写。豆瓣失败时详情仍显示接口自己的资料。", color = palette.muted, modifier = Modifier.padding(top = 8.dp))
        Text("去广告", color = palette.text, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
        TvButton(if (settings.skipHlsAds) "m3u8 去广告开" else "m3u8 去广告关", primary = settings.skipHlsAds, modifier = Modifier.padding(top = 8.dp)) {
            scope.launch { app.settings.update { it.copy(skipHlsAds = !it.skipHlsAds) } }
        }
        Text("弹幕", color = palette.text, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
        Text(
            settings.danmakuApiUrl.ifBlank { "未配置弹幕接口" },
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).width(640.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton(if (settings.danmakuEnabled) "弹幕开" else "弹幕关", primary = settings.danmakuEnabled) {
                scope.launch { app.settings.update { it.copy(danmakuEnabled = !it.danmakuEnabled) } }
            }
            listOf("quarter" to "上方", "half" to "半屏", "full" to "全屏").forEach { (id, label) ->
                TvButton(label, primary = settings.danmakuArea == id) {
                    scope.launch { app.settings.update { it.copy(danmakuArea = id) } }
                }
            }
        }
        Text("字幕", color = palette.text, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf("small" to "小", "medium" to "标准", "large" to "大").forEach { (id, label) ->
                TvButton(label, primary = settings.subtitleSize == id) {
                    scope.launch { app.settings.update { it.copy(subtitleSize = id) } }
                }
            }
            listOf("bottom" to "底部", "middle" to "中间", "top" to "顶部").forEach { (id, label) ->
                TvButton(label, primary = settings.subtitlePosition == id) {
                    scope.launch { app.settings.update { it.copy(subtitlePosition = id) } }
                }
            }
        }
        TvButton("恢复显示默认", modifier = Modifier.padding(top = 16.dp)) {
            scope.launch { app.settings.update { it.resetSection("enhance") } }
        }
        Text("恢复默认不会清掉已填写的弹幕地址、广告规则和代理，但会关闭远程爬虫。", color = palette.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SpiderGate(onCancel: () -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("远程爬虫", color = palette.text, fontSize = 26.sp)
        Text(
            "这会下载并运行配置里的远程代码，可能访问网络和应用数据。只打开你信任的配置。恶意爬虫可以读取本机保存的数据，并发起网络请求。默认关闭；关掉之后会立刻停止执行，首页和搜索不再请求这些站点。",
            color = palette.muted,
            modifier = Modifier.padding(top = 12.dp).width(720.dp),
            lineHeight = 24.sp,
        )
        if (settings.spiderEnabled) {
            Text("当前已打开。JAR 和 JS 站点会计入首页和搜索。", color = palette.text, modifier = Modifier.padding(top = 16.dp))
            TvButton("关闭远程爬虫", primary = true, modifier = Modifier.padding(top = 12.dp)) {
                scope.launch {
                    app.settings.update { it.copy(spiderEnabled = false) }
                    app.spiders.setEnabled(false)
                }
            }
        } else {
            Text("还没有打开。确认后才会下载配置里的 JAR 或 JS。", color = palette.text, modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                TvButton("仍然打开", primary = true) {
                    scope.launch {
                        app.settings.update { it.copy(spiderEnabled = true) }
                        app.spiders.setEnabled(true)
                    }
                }
                TvButton("取消", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun AboutPage() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var licenseHint by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("关于 / 开源许可", color = palette.text, fontSize = 26.sp)
        val crash = remember { app.jianxia.tv.CrashStore.read(context.applicationContext as android.app.Application) }
        if (crash.isNotBlank()) {
            Text("最近一次崩溃", color = palette.text, fontSize = 20.sp, modifier = Modifier.padding(top = 16.dp))
            Text(crash, color = palette.muted, modifier = Modifier.padding(top = 8.dp).width(720.dp), fontSize = 12.sp)
            TvButton("复制崩溃记录", modifier = Modifier.padding(top = 8.dp)) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("个人影院崩溃", crash))
                licenseHint = "已复制崩溃记录"
            }
        }
        Text(
            "个人影院 ${app.jianxia.tv.BuildConfig.VERSION_NAME}。显示名是个人影院，包名仍是 app.jianxia.tv，已安装的版本可以直接升级，原有接口和设置会保留。安装包里没有点播片源。第一次打开会加入「公共频道（iptv-org）」，频道表在使用时从网上获取，默认是中国，可以停用或删除。点播接口仍然只能自己添加。\n\n目前支持 TVBox JSON（含常见的 Base64、图片隐藏和注释）、苹果 CMS（type 0 XML、type 1 JSON）、M3U / TXT 直播和 XMLTV 节目单。\n\nJAR / JS 爬虫默认关闭。要在设置里确认后才会下载并运行配置中的远程代码。添加接口时优先用手机扫码。可以一次粘贴多个网址。",
            color = palette.muted,
            modifier = Modifier.padding(top = 12.dp).width(720.dp),
            lineHeight = 24.sp,
        )
        Text("公共频道", color = palette.text, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp))
        Text(
            "频道列表来自 iptv-org。这些是公开的免费直播地址，个人影院不把频道表打进安装包，也不提供点播片源。列表和项目主页：https://github.com/iptv-org/iptv",
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp).width(720.dp),
            lineHeight = 24.sp,
        )
        Text("豆瓣、去广告和弹幕", color = palette.text, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp))
        Text(
            "豆瓣分类、把 m3u8 在本地改写后再交给播放器、以及按 danmu_api 拉取弹幕，这些做法参考了 MoonTVPlus（MIT，https://github.com/mtvpls/MoonTVPlus）。个人影院按自己的界面重新实现，没有复制它的源码，也没有内置弹幕服务器或付费片源。弹幕接口需要自己填写。",
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp).width(720.dp),
            lineHeight = 24.sp,
        )
        Text("播放器内核", color = palette.text, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp))
        Text(
            "默认内核是 VideoLAN 的 libVLC（VLC for Android 3.6）。libVLC 以 GNU LGPL-2.1 发布。个人影院调用它的公开接口，没有修改它的源码。也可以在播放设置里改用系统内核（Media3 / ExoPlayer，Apache License 2.0）。\n\n个人影院自身的代码以 Apache License 2.0 发布。",
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp).width(720.dp),
            lineHeight = 24.sp,
        )
        Text(
            "libVLC 源码：https://code.videolan.org/videolan/vlc-android\nLGPL-2.1：https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html",
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp).width(720.dp),
            lineHeight = 24.sp,
        )
        licenseHint?.let { Text(it, color = palette.accent, modifier = Modifier.padding(top = 8.dp).width(720.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            TvButton("打开 libVLC 源码") { licenseHint = openLicense(context, "https://code.videolan.org/videolan/vlc-android") }
            TvButton("打开 LGPL-2.1") { licenseHint = openLicense(context, "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html") }
            TvButton("打开 iptv-org") { licenseHint = openLicense(context, "https://github.com/iptv-org/iptv") }
            TvButton("打开 MoonTVPlus") { licenseHint = openLicense(context, "https://github.com/mtvpls/MoonTVPlus") }
        }
    }
}

private fun openLicense(context: Context, url: String): String? {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.fold(
        onSuccess = { null },
        onFailure = { "电视上没有浏览器，请在电脑打开：$url" },
    )
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}
