package app.jianxia.tv.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.compose.foundation.BorderStroke
import app.jianxia.core.model.AppearanceCatalog
import app.jianxia.core.model.AppearanceItem
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.accentLabel
import app.jianxia.core.model.aspectLabel
import app.jianxia.core.model.decoderLabel
import app.jianxia.core.model.displayTitle
import app.jianxia.core.model.layoutLabel
import app.jianxia.core.model.engineLabel
import app.jianxia.core.model.fontLabel
import app.jianxia.core.model.modeLabel
import app.jianxia.core.model.posterLabel
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.speedLabel
import app.jianxia.core.model.startupLabel
import app.jianxia.core.model.wallpaperLabel
import app.jianxia.core.model.withAppearance
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.WallpaperLayer
import app.jianxia.tv.ui.hexColor
import app.jianxia.tv.ui.loadWallpaper
import kotlinx.coroutines.launch

@Composable
internal fun SettingsMenu(versionName: String, onOpen: (String) -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState())) {
            Text("设置", color = palette.text, fontSize = 28.sp)
            Text("用方向键选择，确认键打开。每一项都会显示当前值。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            SettingRow("外观", "${settings.modeLabel()} · ${settings.accentLabel()} · ${settings.wallpaperLabel()}") { onOpen("look") }
            SettingRow("首页", "${settings.layoutLabel()} · ${settings.posterLabel()}海报 · ${settings.homeRows.count { it.visible }} 行") { onOpen("home") }
            SettingRow("播放", "${settings.engineLabel()} · ${settings.speedLabel()} · ${settings.aspectLabel()}") { onOpen("play") }
            SettingRow("豆瓣与播放增强", if (settings.skipHlsAds) "去广告开" else "去广告关") { onOpen("enhance") }
            SettingRow("远程爬虫", if (settings.spiderEnabled) "已打开" else "已关闭") { onOpen("spider") }
            SettingRow("接口与线路", if (settings.autoLineSelect) "自动选线开" else "自动选线关") { onOpen("lines") }
            SettingRow("数据与备份", "导入、导出") { onOpen("backup") }
            SettingRow("关于", versionName) { onOpen("about") }
        }
        Column(Modifier.weight(0.9f).padding(start = 24.dp)) {
            Text("当前外观", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))) {
                WallpaperLayer(settings, Modifier.fillMaxSize())
                Text("TV NET", color = palette.text, fontSize = 28.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
internal fun LookHub(onOpen: (String) -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SectionPage(title = "外观", hint = "主题、壁纸和文字都会马上反映到界面上。") {
        SettingRow("主题", "${settings.modeLabel()} · ${settings.accentLabel()}") { onOpen("theme") }
        SettingRow("壁纸", settings.wallpaperLabel()) { onOpen("wallpaper") }
        SettingRow("文字大小", settings.fontLabel()) { onOpen("font") }
        TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp)) {
            scope.launch { app.settings.update { it.resetSection("look") } }
        }
    }
}

@Composable
internal fun ThemeStudio() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
            Text("主题", color = palette.text, fontSize = 26.sp)
            Text("深浅", color = palette.muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            AppearanceCatalog.modes.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        ChoiceCard(item.label, item.id == settings.themeMode, Modifier.weight(1f)) {
                            scope.launch { app.settings.update { it.withAppearance(themeMode = item.id) } }
                        }
                    }
                }
            }
            Text("颜色", color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            AppearanceCatalog.accents.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        SwatchCard(item.label, item.id, item.id.equals(settings.accent, true), Modifier.weight(1f)) {
                            scope.launch { app.settings.update { it.withAppearance(accent = item.id) } }
                        }
                    }
                }
            }
            TvButton("恢复默认") { scope.launch { app.settings.update { it.resetSection("look") } } }
        }
        Column(Modifier.weight(0.8f).padding(start = 20.dp)) {
            Text("预览", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp))) {
                WallpaperLayer(settings, Modifier.fillMaxSize())
                Column(Modifier.padding(16.dp)) {
                    Text("TV NET", color = palette.accent, fontSize = 26.sp)
                    Text("这是标题", color = palette.text, fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp))
                    Text("这是说明文字", color = palette.muted, modifier = Modifier.padding(top = 6.dp))
                    Box(Modifier.padding(top = 16.dp).width(88.dp).height(36.dp).clip(RoundedCornerShape(10.dp)).background(palette.accent))
                }
            }
        }
    }
}

@Composable
internal fun WallpaperStudio(onCustom: () -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { draft = settings }
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.weight(1.25f).verticalScroll(rememberScrollState())) {
            Text("壁纸", color = palette.text, fontSize = 26.sp)
            Text("移动焦点可以预览，按确认键才会换上。", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            AppearanceCatalog.wallpapers.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        val bitmap = remember(item.id) { loadWallpaper(context, item.id, 0) }
                        val selected = settings.backgroundType == "builtin" && settings.wallpaperId == item.id
                        Surface(
                            onClick = { scope.launch { app.settings.update { it.withAppearance(backgroundType = "builtin", wallpaperId = item.id) } } },
                            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) draft = settings.withAppearance(backgroundType = "builtin", wallpaperId = item.id) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = palette.surface,
                                focusedContainerColor = palette.surface2,
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = if (selected) Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(12.dp)) else Border.None,
                                focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(12.dp)),
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                        ) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap,
                                        contentDescription = item.label,
                                        modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)).background(palette.surface2))
                                }
                                Text(item.label, color = palette.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            Text("其他", color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            AppearanceCatalog.solids.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        val selected = settings.backgroundType == "solid" && settings.solidColor.equals(item.id, true)
                        SwatchCard(item.label, item.id, selected, Modifier.weight(1f), onFocus = {
                            draft = settings.withAppearance(backgroundType = "solid", solidColor = item.id)
                        }) {
                            scope.launch { app.settings.update { it.withAppearance(backgroundType = "solid", solidColor = item.id) } }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard("无", settings.backgroundType == "none", Modifier.weight(1f), onFocus = {
                    draft = settings.withAppearance(backgroundType = "none")
                }) { scope.launch { app.settings.update { it.withAppearance(backgroundType = "none") } } }
                ChoiceCard("自定义图片", settings.backgroundType == "image", Modifier.weight(1f), onClick = onCustom)
            }
            Stepper("模糊", settings.wallpaperBlur, 24) { value ->
                scope.launch { app.settings.update { it.copy(wallpaperBlur = value) } }
            }
            Stepper("变暗", settings.wallpaperDim, 80) { value ->
                scope.launch { app.settings.update { it.copy(wallpaperDim = value) } }
            }
            TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                scope.launch { app.settings.update { it.resetSection("look") } }
            }
        }
        Column(Modifier.weight(0.85f).padding(start = 18.dp)) {
            Text("预览", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp))) {
                WallpaperLayer(draft, Modifier.fillMaxSize())
                Text(draft.wallpaperLabel(), color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(14.dp))
            }
            Text("自定义图片也可以在手机页面里粘贴网址。", color = palette.muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            PhoneQrCard()
        }
    }
}

@Composable
internal fun ChoicePage(
    title: String,
    choices: List<AppearanceItem>,
    selected: String,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
) {
    val palette = LocalPalette.current
    SectionPage(title = title, hint = "当前：${choices.firstOrNull { it.id.equals(selected, true) }?.label ?: selected}") {
        choices.forEach { item ->
            SettingRow(item.label, if (item.id.equals(selected, true)) "当前" else "") { onSelect(item.id) }
        }
        TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp), onClick = onReset)
    }
}

@Composable
internal fun HomeStudio() {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SectionPage(title = "首页", hint = "先选布局。影院模式是大图首页，经典是原来的列表。") {
        Text("布局", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LayoutPreview("影院模式", cinema = true, selected = settings.homeLayout != "classic", Modifier.weight(1f)) {
                scope.launch { app.settings.update { it.copy(homeLayout = "cinema") } }
            }
            LayoutPreview("经典", cinema = false, selected = settings.homeLayout == "classic", Modifier.weight(1f)) {
                scope.launch { app.settings.update { it.copy(homeLayout = "classic") } }
            }
        }
        Text("动效", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceCard("流畅", !settings.reduceMotion, Modifier.weight(1f)) {
                scope.launch { app.settings.update { it.copy(reduceMotion = false) } }
            }
            ChoiceCard("关闭", settings.reduceMotion, Modifier.weight(1f)) {
                scope.launch { app.settings.update { it.copy(reduceMotion = true) } }
            }
        }
        Text("关闭后首页不再淡入，大图也不再模糊，适合比较慢的盒子。", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text("栏目预览", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface).padding(12.dp),
        ) {
            settings.homeRows.filter { it.visible }.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(row.displayTitle(), color = palette.text, modifier = Modifier.width(96.dp), fontSize = 14.sp)
                    val count = if (settings.posterSize == "small") 6 else if (settings.posterSize == "large") 3 else 4
                    val width = if (settings.posterSize == "small") 28.dp else if (settings.posterSize == "large") 52.dp else 38.dp
                    repeat(count) {
                        Box(
                            Modifier.padding(end = 6.dp).width(width).height(width * 1.35f).clip(RoundedCornerShape(4.dp)).background(palette.accent.copy(alpha = 0.85f)),
                        )
                    }
                }
            }
        }
        Text("海报大小", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearanceCatalog.posters.forEach { item ->
                ChoiceCard(item.label, settings.posterSize == item.id, Modifier.weight(1f)) {
                    scope.launch { app.settings.update { it.copy(posterSize = item.id) } }
                }
            }
        }
        Text("栏目顺序", color = palette.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        settings.homeRows.forEachIndexed { index, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text("${index + 1}. ${row.displayTitle()}", color = palette.text, modifier = Modifier.width(160.dp), fontSize = 16.sp)
                TvButton(if (row.visible) "显示" else "隐藏") {
                    scope.launch {
                        app.settings.update { current ->
                            current.copy(homeRows = current.homeRows.map { if (it.id == row.id) it.copy(visible = !it.visible) else it })
                        }
                    }
                }
                TvButton("上移", enabled = index > 0) {
                    scope.launch { app.settings.update { it.copy(homeRows = it.homeRows.move(index, -1)) } }
                }
                TvButton("下移", enabled = index < settings.homeRows.lastIndex) {
                    scope.launch { app.settings.update { it.copy(homeRows = it.homeRows.move(index, 1)) } }
                }
            }
        }
        TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp)) {
            scope.launch { app.settings.update { it.resetSection("home") } }
        }
    }
}

@Composable
internal fun PlayHub(onOpen: (String) -> Unit) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SectionPage(title = "播放", hint = "选中一行后按确认键，从列表里选。") {
        SettingRow("播放器内核", settings.engineLabel()) { onOpen("engine") }
        SettingRow("解码", settings.decoderLabel()) { onOpen("decoder") }
        SettingRow("默认倍速", settings.speedLabel()) { onOpen("speed") }
        SettingRow("默认画面", settings.aspectLabel()) { onOpen("aspect") }
        SettingRow("启动页", settings.startupLabel()) { onOpen("startup") }
        TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp)) {
            scope.launch { app.settings.update { it.resetSection("play") } }
        }
    }
}

@Composable
internal fun LinesHub(sourceName: String, onOpen: (String) -> Unit) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SectionPage(title = "接口与线路", hint = "接口本身仍建议用手机扫码添加。") {
        SettingRow("管理接口", "添加、排序、重试") { onOpen("sources") }
        SettingRow("默认来源", sourceName) { onOpen("source") }
        SettingRow("自动选线", if (settings.autoLineSelect) "开" else "关") { onOpen("autoline") }
        SettingRow("搜索超时", "${settings.searchTimeoutSec} 秒") { onOpen("timeout") }
        TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp)) {
            scope.launch { app.settings.update { it.resetSection("lines") } }
        }
    }
}

@Composable
private fun SectionPage(title: String, hint: String, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(ScreenPadding).verticalScroll(rememberScrollState())) {
        Text(title, color = palette.text, fontSize = 26.sp)
        Text(hint, color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        content()
    }
}

@Composable
internal fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth().heightIn(min = 64.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface,
            contentColor = palette.text,
            focusedContainerColor = palette.surface2,
            focusedContentColor = palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = palette.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) Text(value, color = palette.accent, fontSize = 16.sp)
        }
    }
}

@Composable
private fun LayoutPreview(
    title: String,
    cinema: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface,
            focusedContainerColor = palette.surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF10141C)).padding(8.dp),
            ) {
                if (cinema) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(palette.accent.copy(alpha = 0.55f)))
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(4) {
                                Box(Modifier.width(16.dp).height(22.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.75f)))
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                repeat(5) {
                                    Box(Modifier.width(14.dp).height(20.dp).clip(RoundedCornerShape(3.dp)).background(palette.accent.copy(alpha = 0.8f)))
                                }
                            }
                        }
                    }
                }
            }
            Text(title, color = if (selected) palette.accent else palette.text, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
            Text(if (selected) "当前" else " ", color = palette.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChoiceCard(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) palette.accent else palette.surface,
            contentColor = if (selected) palette.onAccent else palette.text,
            focusedContainerColor = if (selected) palette.accent else palette.surface2,
            focusedContentColor = if (selected) palette.onAccent else palette.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) palette.onAccent else palette.text, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SwatchCard(
    label: String,
    hex: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.surface,
            focusedContainerColor = palette.surface2,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, palette.accent), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, palette.accent), shape = RoundedCornerShape(14.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(hexColor(hex, palette.accent)),
            )
            Text(label, color = palette.text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text("$label  $value", color = palette.text, fontSize = 16.sp)
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(palette.surface2)
                .border(if (focused) 3.dp else 0.dp, palette.accent, RoundedCornerShape(99.dp))
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = 4
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onChange((value - step).coerceAtLeast(0))
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onChange((value + step).coerceAtMost(max))
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Box(
                Modifier.fillMaxWidth(if (max == 0) 0f else value / max.toFloat()).height(28.dp).background(palette.accent),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TvButton("减小") { onChange((value - 4).coerceAtLeast(0)) }
            TvButton("增大") { onChange((value + 4).coerceAtMost(max)) }
        }
    }
}

private fun <T> List<T>.move(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply {
        val item = removeAt(index)
        add(target, item)
    }
}
