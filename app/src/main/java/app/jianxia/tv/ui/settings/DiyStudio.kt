package app.jianxia.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.AppearanceCatalog
import app.jianxia.core.model.ShelfToggle
import app.jianxia.core.model.UiDiy
import app.jianxia.core.model.playerBarLabel
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.railLabel
import app.jianxia.core.model.shellLabel
import app.jianxia.core.model.shifted
import app.jianxia.core.model.wallpaperLabel
import app.jianxia.core.model.withAppearance
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.WallpaperLayer
import kotlinx.coroutines.launch

@Composable
internal fun DiyStudio(onImageUrl: () -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(settings) }
    var classes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var classNote by remember { mutableStateOf("分类会在首页读到站点后出现在这里。") }
    LaunchedEffect(settings) { draft = settings }
    LaunchedEffect(settings.defaultSourceId) {
        val expanded = try {
            app.catalog.expand()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        val site = expanded.sites.firstOrNull { it.key == settings.defaultSourceId } ?: expanded.sites.firstOrNull() ?: return@LaunchedEffect
        val home = try {
            app.catalog.browseSite(site.key, 1, null, emptyMap())
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        classes = home?.classes?.map { it.id to it.name }.orEmpty()
        classNote = if (classes.isEmpty()) "这个首页站源还没有分类。打开首页后会补上。" else "隐藏和排序按确认键生效，首页会马上换。"
    }
    val tabs = if (classes.isEmpty()) {
        settings.homeTabs.ifEmpty { listOf(ShelfToggle("home", "主页")) }
    } else {
        UiDiy.mergeTabs(settings.homeTabs, classes)
    }
    fun save(block: (AppSettings) -> AppSettings) {
        scope.launch { app.settings.update(block) }
    }
    Row(Modifier.fillMaxSize().padding(ScreenPadding())) {
        Column(Modifier.weight(1.35f).verticalScroll(rememberScrollState())) {
            Text("界面 DIY", color = palette.text, fontSize = 26.sp)
            Text("按确认键才会改。右边是当前效果，电视其余页面也会一起换。", color = palette.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Text("主题", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceCatalog.modes.forEach { item ->
                    ChoiceCard(item.label, settings.themeMode == item.id, Modifier.weight(1f)) {
                        save { it.withAppearance(themeMode = item.id) }
                    }
                }
            }
            Text("强调色", color = palette.muted, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            AppearanceCatalog.accents.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        SwatchCard(item.label, item.id, item.id.equals(settings.accent, true), Modifier.weight(1f)) {
                            save { it.withAppearance(accent = item.id) }
                        }
                    }
                }
            }
            Text("壁纸", color = palette.muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            AppearanceCatalog.wallpapers.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { item ->
                        ChoiceCard(
                            item.label,
                            settings.backgroundType == "builtin" && settings.wallpaperId == item.id,
                            Modifier.weight(1f),
                            onFocus = { draft = settings.withAppearance(backgroundType = "builtin", wallpaperId = item.id) },
                        ) {
                            save { it.withAppearance(backgroundType = "builtin", wallpaperId = item.id) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                ChoiceCard("纯色", settings.backgroundType == "solid", Modifier.weight(1f)) {
                    save { it.withAppearance(backgroundType = "solid") }
                }
                ChoiceCard("无", settings.backgroundType == "none", Modifier.weight(1f)) {
                    save { it.withAppearance(backgroundType = "none") }
                }
                ChoiceCard("图片网址", settings.backgroundType == "image", Modifier.weight(1f), onClick = onImageUrl)
            }
            Text("手机页面可以上传一张图片，或粘贴图片网址。", color = palette.muted, fontSize = 13.sp)
            Stepper("模糊", settings.wallpaperBlur, 24, step = 2) { value -> save { it.copy(wallpaperBlur = value) } }
            Stepper("变暗", settings.wallpaperDim, 80, step = 4) { value -> save { it.copy(wallpaperDim = value) } }
            Stepper("不透明度", settings.tileAlpha, 100, min = 30, step = 5) { value ->
                save { it.copy(tileAlpha = value) }
            }
            Stepper("圆角", settings.cornerRadius, 28, step = 2) { value -> save { it.copy(cornerRadius = value) } }
            Text("海报", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 5, 6).forEach { count ->
                    ChoiceCard("$count 列", settings.posterColumns == count, Modifier.weight(1f)) {
                        save { it.copy(posterColumns = count) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                AppearanceCatalog.posters.forEach { item ->
                    ChoiceCard(item.label, settings.posterSize == item.id, Modifier.weight(1f)) {
                        save { it.copy(posterSize = item.id) }
                    }
                }
            }
            Text("角标", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BadgeToggle("评分", settings.showRating) { save { it.copy(showRating = !it.showRating) } }
                BadgeToggle("年份", settings.showYear) { save { it.copy(showYear = !it.showYear) } }
                BadgeToggle("清晰度", settings.showQuality) { save { it.copy(showQuality = !it.showQuality) } }
                BadgeToggle("豆瓣热播", settings.showDoubanBadge) { save { it.copy(showDoubanBadge = !it.showDoubanBadge) } }
            }
            Text("文字", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("small" to "小", "medium" to "标准", "large" to "大").forEach { (id, label) ->
                    ChoiceCard(label, settings.fontScale == id, Modifier.weight(1f)) {
                        save { it.withAppearance(fontScale = id) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                ChoiceCard(if (settings.showClock) "时钟显示" else "时钟隐藏", settings.showClock, Modifier.weight(1f)) {
                    save { it.copy(showClock = !it.showClock) }
                }
            }
            Text("首页布局", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard("影视仓", settings.homeShell != "cinema", Modifier.weight(1f)) {
                    save { it.copy(homeShell = "warehouse") }
                }
                ChoiceCard("影院", settings.homeShell == "cinema", Modifier.weight(1f)) {
                    save { it.copy(homeShell = "cinema") }
                }
            }
            Text("功能键位置", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard("左侧竖排", settings.homeRail != "top", Modifier.weight(1f)) {
                    save { it.copy(homeRail = "left") }
                }
                ChoiceCard("顶部横排", settings.homeRail == "top", Modifier.weight(1f)) {
                    save { it.copy(homeRail = "top") }
                }
            }
            Text("播放条", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("full" to "完整", "slim" to "精简", "float" to "悬浮").forEach { (id, label) ->
                    ChoiceCard(label, settings.playerBar == id, Modifier.weight(1f)) {
                        save { it.copy(playerBar = id) }
                    }
                }
            }
            Text("首页功能键", color = palette.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            UiDiy.actionsOf(settings).forEachIndexed { index, item ->
                OrderRow(index, UiDiy.actionsOf(settings).size, item) { next ->
                    save { current ->
                        val rows = UiDiy.actionsOf(current)
                        current.copy(homeActions = rows.adjust(index, next))
                    }
                }
            }
            Text("首页分类", color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Text(classNote, color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            tabs.forEachIndexed { index, item ->
                OrderRow(index, tabs.size, item) { next ->
                    save { current ->
                        val rows = if (classes.isEmpty()) {
                            current.homeTabs.ifEmpty { listOf(ShelfToggle("home", "主页")) }
                        } else {
                            UiDiy.mergeTabs(current.homeTabs, classes)
                        }
                        if (index !in rows.indices) current else current.copy(homeTabs = rows.adjust(index, next))
                    }
                }
            }
            TvButton("恢复默认", modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                save { it.resetSection("diy") }
            }
        }
        Column(Modifier.weight(0.8f).padding(start = 16.dp)) {
            Text("预览", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
            DiyPreview(draft)
            Text(
                "${draft.shellLabel()} · ${draft.railLabel()} · ${draft.playerBarLabel()} · ${draft.wallpaperLabel()}",
                color = palette.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            PhoneQrCard()
        }
    }
}

@Composable
private fun RowScope.BadgeToggle(label: String, on: Boolean, onClick: () -> Unit) {
    ChoiceCard(if (on) label else "$label 关", on, Modifier.weight(1f), onClick = onClick)
}

private fun List<ShelfToggle>.adjust(index: Int, action: String): List<ShelfToggle> {
    if (index !in indices) return this
    return when (action) {
        "up" -> shifted(index, -1)
        "down" -> shifted(index, 1)
        else -> mapIndexed { at, item -> if (at == index) item.copy(visible = !item.visible) else item }
    }
}

@Composable
private fun OrderRow(index: Int, total: Int, item: ShelfToggle, onAction: (String) -> Unit) {
    val palette = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Text(
            "${index + 1}. ${item.title}",
            color = if (item.visible) palette.text else palette.muted,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
        )
        TvButton(if (item.visible) "显示" else "隐藏") { onAction("flip") }
        TvButton("上移", enabled = index > 0) { onAction("up") }
        TvButton("下移", enabled = index < total - 1) { onAction("down") }
    }
}

@Composable
private fun DiyPreview(settings: AppSettings) {
    val palette = LocalPalette.current
    val radius = settings.cornerRadius.coerceIn(0, 28).dp
    Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(radius))) {
        WallpaperLayer(settings, Modifier.fillMaxSize())
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("首页站源", color = Color.White, fontSize = 14.sp)
                if (settings.showClock) Text("09/28 周一  13:31", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                val tabs = UiDiy.visibleTabs(settings.homeTabs, listOf("movie" to "电影", "tv" to "剧集")).take(4)
                tabs.forEach { tab ->
                    Text(tab.title, color = palette.accent, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                UiDiy.actionsOf(settings).filter { it.visible }.take(6).forEach { action ->
                    Text(
                        action.title,
                        color = palette.text,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(radius))
                            .background(palette.surface.copy(alpha = settings.tileAlpha.coerceIn(30, 100) / 100f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                val count = settings.posterColumns.coerceIn(4, 6).coerceAtMost(4)
                val ratio = when (settings.posterSize) {
                    "small" -> 0.9f
                    "large" -> 0.62f
                    else -> 0.75f
                }
                repeat(count) { index ->
                    Box(
                        Modifier.weight(1f).height((72 / ratio).dp).clip(RoundedCornerShape(radius)).background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        if (index == 0 && settings.showDoubanBadge) {
                            Text("豆瓣热播", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(4.dp))
                        }
                        if (settings.showYear) {
                            Text("2026", color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                        if (settings.showQuality) {
                            Text("HD", color = palette.accent, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                        }
                        if (settings.showRating) {
                            Text("7.5", color = palette.accent, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                        }
                    }
                }
            }
        }
    }
}
