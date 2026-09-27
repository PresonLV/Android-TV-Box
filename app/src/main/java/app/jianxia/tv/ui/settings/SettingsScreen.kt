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
import app.jianxia.tv.data.db.SourceEntity
import app.jianxia.tv.ui.Keycap
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.Panel
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.kindLabel
import kotlinx.coroutines.launch

private val accents = listOf(
    "金" to "#E2B15A",
    "青" to "#6FCFC0",
    "蓝" to "#7C9CFF",
    "珊瑚" to "#E07A6A",
    "紫" to "#C084FC",
    "绿" to "#8FCB7B",
)
private val gradients = listOf("ink" to "墨", "dusk" to "暮", "ocean" to "海", "forest" to "林", "ember" to "焰")

@Composable
fun SettingsScreen(start: String = "root", openCreate: Boolean = false) {
    var section by remember { mutableStateOf(start) }
    BackHandler(enabled = section != "root") { section = "root" }
    when (section) {
        "sources" -> SourcesPage(openCreate = openCreate, onBack = { section = "root" })
        "look" -> LookPage()
        "home" -> HomeLayoutPage()
        "play" -> PlayPage()
        "backup" -> BackupPage()
        "about" -> AboutPage()
        else -> SettingsRoot { section = it }
    }
}

@Composable
private fun SettingsRoot(onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("设置", color = palette.text, fontSize = 28.sp)
        Text("所有内容都来自你自己添加的接口。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        listOf(
            "sources" to "接口与直播源",
            "look" to "外观",
            "home" to "首页布局",
            "play" to "播放",
            "backup" to "导入与导出",
            "about" to "关于",
        ).forEach { (id, label) ->
            TvButton(label, modifier = Modifier.padding(bottom = 10.dp).width(360.dp)) { onOpen(id) }
        }
    }
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
            Text(message ?: "推荐先用右侧二维码，在手机上粘贴地址。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            TvButton("在电视上添加", primary = true) { creating = true }
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
                        if (editing == null) app.sources.add(url, name, epg).summary
                        else app.sources.update(editing!!.id, name, url, epg)
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
                "当前输入：${if (target == "url") "地址" else if (target == "epg") "节目单" else "名称"}。遥控器选下面的按键，不必用电视自带输入法。",
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
private fun LookPage() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var image by remember(settings.backgroundImageUrl) { mutableStateOf(settings.backgroundImageUrl) }
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("外观", color = palette.text, fontSize = 26.sp)
        Text("主题", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("深色", settings.themeMode != "light") { scope.launch { app.settings.update { it.copy(themeMode = "dark") } } }
            SelectChip("浅色", settings.themeMode == "light") { scope.launch { app.settings.update { it.copy(themeMode = "light") } } }
        }
        Text("强调色", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accents.forEach { (label, hex) ->
                SelectChip(label, settings.accent.equals(hex, true)) { scope.launch { app.settings.update { it.copy(accent = hex) } } }
            }
        }
        Text("背景", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            gradients.forEach { (id, label) ->
                SelectChip(label, settings.backgroundType != "image" && settings.gradientId == id) {
                    scope.launch { app.settings.update { it.copy(backgroundType = "gradient", gradientId = id) } }
                }
            }
        }
        Text("自定义图片地址", color = palette.muted, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = image,
            onValueChange = { image = it },
            textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.padding(top = 8.dp).width(640.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            TvButton("使用这张图", primary = true) {
                scope.launch { app.settings.update { it.copy(backgroundType = "image", backgroundImageUrl = image.trim()) } }
            }
            TvButton("改回渐变") {
                scope.launch { app.settings.update { it.copy(backgroundType = "gradient", backgroundImageUrl = "") } }
            }
        }
    }
}

@Composable
private fun HomeLayoutPage() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val sources by app.sources.observe().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("首页布局", color = palette.text, fontSize = 26.sp)
        Text("海报大小", color = palette.muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("small" to "小", "medium" to "中", "large" to "大").forEach { (id, label) ->
                SelectChip(label, settings.posterSize == id) { scope.launch { app.settings.update { it.copy(posterSize = id) } } }
            }
        }
        Text("默认来源", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("全部", settings.defaultSourceId.isBlank()) {
                scope.launch { app.settings.update { it.copy(defaultSourceId = "") } }
            }
            sources.forEach { source ->
                SelectChip(source.name, settings.defaultSourceId == source.id) {
                    scope.launch { app.settings.update { it.copy(defaultSourceId = source.id) } }
                }
            }
        }
        Text("首页行", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        settings.homeRows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text(row.title, color = palette.text, modifier = Modifier.width(120.dp).padding(top = 10.dp))
                TvButton(if (row.visible) "显示" else "隐藏") {
                    scope.launch {
                        app.settings.update { current ->
                            current.copy(homeRows = current.homeRows.map { if (it.id == row.id) it.copy(visible = !it.visible) else it })
                        }
                    }
                }
                TvButton("上移") {
                    if (index == 0) return@TvButton
                    scope.launch { app.settings.update { it.copy(homeRows = it.homeRows.move(index, -1)) } }
                }
                TvButton("下移") {
                    scope.launch { app.settings.update { it.copy(homeRows = it.homeRows.move(index, 1)) } }
                }
            }
        }
    }
}

@Composable
private fun PlayPage() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text("播放", color = palette.text, fontSize = 26.sp)
        Text("启动页", color = palette.muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("点播", settings.startupPage != "live") { scope.launch { app.settings.update { it.copy(startupPage = "vod") } } }
            SelectChip("直播", settings.startupPage == "live") { scope.launch { app.settings.update { it.copy(startupPage = "live") } } }
        }
        Text("搜索超时", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 5, 8, 12, 20).forEach { sec ->
                SelectChip("${sec}秒", settings.searchTimeoutSec == sec) {
                    scope.launch { app.settings.update { it.copy(searchTimeoutSec = sec) } }
                }
            }
        }
        Text("自动选线", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("开", settings.autoLineSelect) { scope.launch { app.settings.update { it.copy(autoLineSelect = true) } } }
            SelectChip("关", !settings.autoLineSelect) { scope.launch { app.settings.update { it.copy(autoLineSelect = false) } } }
        }
        Text("解码", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("硬件", settings.decoder != "software") { scope.launch { app.settings.update { it.copy(decoder = "hardware") } } }
            SelectChip("软件", settings.decoder == "software") { scope.launch { app.settings.update { it.copy(decoder = "software") } } }
        }
        Text("默认倍速", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                SelectChip(if (speed == 1f) "正常" else "${speed}x", settings.defaultSpeed == speed) {
                    scope.launch { app.settings.update { it.copy(defaultSpeed = speed) } }
                }
            }
        }
        Text("默认画面", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("fit" to "适应", "fill" to "铺满", "zoom" to "放大", "16:9" to "16:9", "4:3" to "4:3").forEach { (id, label) ->
                SelectChip(label, settings.aspect == id) { scope.launch { app.settings.update { it.copy(aspect = id) } } }
            }
        }
    }
}

@Composable
private fun BackupPage() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("导入会覆盖当前的接口和设置。") }
    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Text("导入与导出", color = palette.text, fontSize = 26.sp)
        Text(message, color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        BasicTextField(
            value = raw,
            onValueChange = { raw = it },
            textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            TvButton("复制备份", primary = true) {
                scope.launch {
                    val text = app.backup.export()
                    raw = text
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("简匣备份", text))
                    message = "备份已复制，也可以在手机页面下载。"
                }
            }
            TvButton("粘贴") { raw = readClipboard(context).orEmpty() }
            TvButton("导入") {
                scope.launch {
                    message = runCatching { app.backup.import(raw) }.getOrElse { UserFacingError.message(it) }
                }
            }
        }
    }
}

@Composable
private fun AboutPage() {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Text("关于简匣", color = palette.text, fontSize = 26.sp)
        Text(
            "版本 ${app.jianxia.tv.BuildConfig.VERSION_NAME}。这是一个空壳播放器：安装包里没有片源，也没有预置接口。\n\n目前支持 TVBox JSON（含常见的 Base64、图片隐藏和注释）、苹果 CMS（type 0 XML、type 1 JSON）、M3U / TXT 直播和 XMLTV 节目单。\n\nJAR、JS 爬虫站点会显示为不支持，不会执行下载的代码。添加接口时优先用手机扫码。",
            color = palette.muted,
            modifier = Modifier.padding(top = 12.dp).width(720.dp),
            lineHeight = 24.sp,
        )
    }
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun <T> List<T>.move(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply {
        val item = removeAt(index)
        add(target, item)
    }
}
