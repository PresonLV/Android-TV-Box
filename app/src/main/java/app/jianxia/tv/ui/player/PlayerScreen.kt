@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.jianxia.tv.ui.player

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jianxia.tv.player.EngineId
import app.jianxia.tv.player.PlayerPanel
import app.jianxia.tv.player.label
import app.jianxia.tv.player.parseEngine
import app.jianxia.tv.player.wire
import app.jianxia.tv.player.PlayerViewModel
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton
import app.jianxia.tv.ui.appViewModel
import app.jianxia.tv.ui.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val settings by LocalApp.current.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val vm: PlayerViewModel = appViewModel { PlayerViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    remember { vm.attach(context) }
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    var seekFocused by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { seekJob?.cancel() } }
    fun startSeek(direction: Int) {
        seekJob?.cancel()
        seekJob = scope.launch {
            var step = 0
            while (isActive) {
                val delta = when {
                    step < 2 -> 10_000L
                    step < 6 -> 30_000L
                    step < 12 -> 60_000L
                    else -> 180_000L
                }
                vm.seekBy(direction * delta)
                step += 1
                delay(if (step < 3) 360 else 150)
            }
        }
    }
    BackHandler(enabled = true) {
        when {
            confirmLeave -> confirmLeave = false
            ui.countdown != null -> vm.cancelCountdown()
            ui.panel != PlayerPanel.Hidden -> vm.hide()
            else -> confirmLeave = true
        }
    }
    LaunchedEffect(ui.panel) {
        if (ui.panel == PlayerPanel.Hidden) runCatching { rootFocus.requestFocus() }
        if (ui.panel == PlayerPanel.Main) runCatching { seekFocus.requestFocus() }
    }
    val frame = when (ui.aspect) {
        "16:9" -> Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
        "4:3" -> Modifier.fillMaxHeight().aspectRatio(4f / 3f, matchHeightConstraintsFirst = true)
        else -> Modifier.fillMaxSize()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val down = event.type == KeyEventType.KeyDown
                val seekable = ui.panel == PlayerPanel.Hidden || seekFocused
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                ) {
                    if (!seekable && ui.panel != PlayerPanel.Hidden) return@onPreviewKeyEvent false
                    if (!down) {
                        seekJob?.cancel()
                        return@onPreviewKeyEvent true
                    }
                    if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                    val direction = if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND) -1 else 1
                    if (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                        vm.seekBy(direction * 30_000L)
                    } else {
                        startSeek(direction)
                    }
                    return@onPreviewKeyEvent true
                }
                if (!down) return@onPreviewKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_MENU -> {
                        if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                        vm.panel(PlayerPanel.Menu)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        val longPress = event.nativeKeyEvent.isLongPress
                        if (longPress && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            vm.panel(PlayerPanel.Menu)
                            true
                        } else if (event.nativeKeyEvent.repeatCount > 0) {
                            true
                        } else if (ui.panel == PlayerPanel.Hidden) {
                            vm.playPause()
                            true
                        } else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                            vm.playPause()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (ui.panel == PlayerPanel.Hidden) {
                            vm.panel(PlayerPanel.Episodes)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (ui.panel == PlayerPanel.Hidden) {
                            vm.panel(PlayerPanel.Lines)
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { vm.next(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { vm.previous(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).also { vm.bindSurface(it) }
            },
            modifier = frame,
        )
        val overlay by vm.overlay.collectAsStateWithLifecycle()
        PlaybackOverlay(overlay, ui.positionMs, ui.playing && !ui.buffering)
        if (ui.empty) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有可播放的内容", color = Color.White, fontSize = 24.sp)
                TvButton("返回", modifier = Modifier.padding(top = 16.dp), primary = true, onClick = onBack)
            }
        }
        if (ui.panel != PlayerPanel.Hidden || ui.error != null || ui.countdown != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).padding(36.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (ui.countdown != null) {
                    Text("${ui.countdown} 秒后播放下一集", color = Color.White, fontSize = 22.sp)
                    TvButton("取消", modifier = Modifier.padding(top = 8.dp), onClick = vm::cancelCountdown)
                }
                ui.hint?.let { Text(it, color = palette.accent, modifier = Modifier.padding(bottom = 8.dp)) }
                ui.error?.let { Text(it, color = palette.danger, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                Text(ui.title, color = Color.White, fontSize = 28.sp)
                Text("${ui.episodeName}    ${ui.lineLabel}", color = Color(0xFFD9D3C7), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                val fraction = if (ui.durationMs > 0) ui.positionMs / ui.durationMs.toFloat() else 0f
                TvButton(
                    "${formatClock(ui.positionMs)} / ${formatClock(ui.durationMs)}",
                    onClick = vm::playPause,
                    modifier = Modifier.focusRequester(seekFocus).onFocusChanged { seekFocused = it.isFocused }.fillMaxWidth(),
                )
                Box(
                    Modifier.padding(vertical = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(palette.accent))
                }
                if (ui.probing) Text("正在测速", color = palette.muted, modifier = Modifier.padding(bottom = 8.dp))
                when (ui.panel) {
                    PlayerPanel.Episodes -> {
                        var episodePage by remember(ui.selectedLineId, ui.episodes.size) {
                            mutableStateOf((ui.episodeIndex / 40).coerceAtLeast(0))
                        }
                        val pages = ((ui.episodes.size + 39) / 40).coerceAtLeast(1)
                        val page = episodePage.coerceIn(0, pages - 1)
                        Column {
                            if (pages > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(pages) { index ->
                                        val start = index * 40 + 1
                                        val end = minOf(ui.episodes.size, (index + 1) * 40)
                                        SelectChip("$start-$end", index == page) { episodePage = index }
                                    }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = if (pages > 1) 8.dp else 0.dp),
                            ) {
                                ui.episodes.drop(page * 40).take(40).forEachIndexed { offset, name ->
                                    val index = page * 40 + offset
                                    SelectChip(name.ifBlank { "第 ${index + 1} 集" }, index == ui.episodeIndex) { vm.jumpEpisode(index) }
                                }
                                if (ui.episodes.isEmpty()) Text("这一线路没有分集", color = Color.White)
                            }
                        }
                    }
                    PlayerPanel.Menu -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton("倍速") { vm.panel(PlayerPanel.Speed) }
                        TvButton("画面比例") { vm.panel(PlayerPanel.Aspect) }
                        TvButton("播放器内核") { vm.panel(PlayerPanel.Engine) }
                        SelectChip("硬解", settings.decoder != "software") { vm.setDecoder(false) }
                        SelectChip("软解", settings.decoder == "software") { vm.setDecoder(true) }
                        TvButton("片头片尾") { vm.panel(PlayerPanel.Skip) }
                        TvButton("字幕") { vm.panel(PlayerPanel.Subtitle) }
                        TvButton("弹幕") { vm.panel(PlayerPanel.Danmaku) }
                        TvButton("换源") { vm.panel(PlayerPanel.Lines) }
                        TvButton("外部播放器") { vm.openExternal(context) }
                    }
                    PlayerPanel.Lines -> Column {
                        ui.lines.forEach { line ->
                            SelectChip(
                                listOfNotNull(line.label, line.detail).joinToString("  "),
                                line.id == ui.selectedLineId,
                                modifier = Modifier.padding(bottom = 8.dp),
                                onClick = { vm.chooseLine(line.id) },
                            )
                        }
                        TvButton("重新测速", onClick = vm::reprobe)
                    }
                    PlayerPanel.Speed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            SelectChip(if (speed == 1f) "正常" else "${speed}x", speed == ui.speed) { vm.setSpeed(speed) }
                        }
                    }
                    PlayerPanel.Aspect -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("fit" to "适应", "fill" to "铺满", "zoom" to "放大", "16:9" to "16:9", "4:3" to "4:3").forEach { (value, label) ->
                            SelectChip(label, value == ui.aspect) { vm.setAspect(value) }
                        }
                    }
                    PlayerPanel.Engine -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(EngineId.Vlc, EngineId.Exo).forEach { engine ->
                            SelectChip(engine.label(), engine.wire() == ui.engine) { vm.setKernel(engine.wire()) }
                        }
                    }
                    PlayerPanel.Danmaku -> Column {
                        Text(overlay.danmakuNote.ifBlank { "弹幕" }, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectChip(if (overlay.danmakuOn) "弹幕开" else "弹幕关", overlay.danmakuOn) { vm.toggleDanmaku() }
                            listOf(40 to "淡", 80 to "标准", 100 to "浓").forEach { (value, label) ->
                                SelectChip(label, overlay.opacity == value) { vm.setDanmaku(opacity = value) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            listOf("small" to "小字", "medium" to "中字", "large" to "大字").forEach { (value, label) ->
                                SelectChip(label, overlay.font == value) { vm.setDanmaku(font = value) }
                            }
                            listOf("slow" to "慢", "medium" to "中速", "fast" to "快").forEach { (value, label) ->
                                SelectChip(label, overlay.speed == value) { vm.setDanmaku(speed = value) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            listOf(30 to "稀疏", 60 to "正常", 100 to "密集").forEach { (value, label) ->
                                SelectChip(label, overlay.density == value) { vm.setDanmaku(density = value) }
                            }
                            listOf("quarter" to "上方", "half" to "半屏", "full" to "全屏").forEach { (value, label) ->
                                SelectChip(label, overlay.area == value) { vm.setDanmaku(area = value) }
                            }
                        }
                    }
                    PlayerPanel.Subtitle -> Column {
                        Text(overlay.subtitleNote.ifBlank { "字幕" }, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectChip("关闭", overlay.choice == "off") { vm.chooseSubtitle("off") }
                            overlay.embedded.forEach { track ->
                                SelectChip("内嵌 ${track.label}", overlay.choice == "embedded:${track.id}") {
                                    vm.chooseSubtitle("embedded:${track.id}")
                                }
                            }
                            overlay.files.forEach { (path, name) ->
                                SelectChip(name, overlay.choice == "file:$path") { vm.chooseSubtitle("file:$path") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            TvButton("提前 0.5 秒") { vm.nudgeSubtitle(-500) }
                            TvButton("延后 0.5 秒") { vm.nudgeSubtitle(500) }
                            TvButton("偏移归零") { vm.nudgeSubtitle(-overlay.offsetMs) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            listOf("small" to "小", "medium" to "标准", "large" to "大").forEach { (value, label) ->
                                SelectChip(label, overlay.size == value) { vm.setSubtitleLook(size = value) }
                            }
                            listOf("bottom" to "底部", "middle" to "中间", "top" to "顶部").forEach { (value, label) ->
                                SelectChip(label, overlay.position == value) { vm.setSubtitleLook(position = value) }
                            }
                        }
                    }
                    PlayerPanel.Skip -> Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            TvButton("当前位置设为片头") { vm.markSkipFromHere(true) }
                            TvButton("当前位置设为片尾") { vm.markSkipFromHere(false) }
                        }
                        Text("片头", color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            listOf(0, 15, 30, 60, 90, 120).forEach { sec ->
                                SelectChip(if (sec == 0) "关" else "${sec}秒", sec == ui.introSec) { vm.setSkip(sec, ui.outroSec) }
                            }
                        }
                        Text("片尾", color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            listOf(0, 15, 30, 60, 90, 120).forEach { sec ->
                                SelectChip(if (sec == 0) "关" else "${sec}秒", sec == ui.outroSec) { vm.setSkip(ui.introSec, sec) }
                            }
                        }
                    }
                    else -> when (settings.playerBar) {
                        "slim" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvButton(if (ui.playing) "暂停" else "播放", primary = true, onClick = vm::playPause)
                            TvButton("上一集", enabled = ui.canPrev, onClick = vm::previous)
                            TvButton("下一集", enabled = ui.canNext, onClick = vm::next)
                            TvButton("菜单") { vm.panel(PlayerPanel.Menu) }
                        }
                        "float" -> Row(
                            Modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvButton(if (ui.playing) "暂停" else "播放", primary = true, onClick = vm::playPause)
                            Text("${formatClock(ui.positionMs)} / ${formatClock(ui.durationMs)}", color = Color.White)
                            TvButton("下一集", enabled = ui.canNext, onClick = vm::next)
                            TvButton("菜单") { vm.panel(PlayerPanel.Menu) }
                        }
                        else -> FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TvButton(if (ui.playing) "暂停" else "播放", onClick = vm::playPause)
                            TvButton("上一集", enabled = ui.canPrev, onClick = vm::previous)
                            TvButton("下一集", enabled = ui.canNext, onClick = vm::next)
                            TvButton("线路") { vm.panel(PlayerPanel.Lines) }
                            TvButton("倍速") { vm.panel(PlayerPanel.Speed) }
                            TvButton("画面") { vm.panel(PlayerPanel.Aspect) }
                            TvButton("片头片尾") { vm.panel(PlayerPanel.Skip) }
                            TvButton(if (overlay.danmakuOn) "弹幕" else "弹幕关") { vm.panel(PlayerPanel.Danmaku) }
                            TvButton("字幕") { vm.panel(PlayerPanel.Subtitle) }
                            TvButton("内核 ${parseEngine(ui.engine).label()}") { vm.panel(PlayerPanel.Engine) }
                            TvButton("用外部播放器打开") { vm.openExternal(context) }
                        }
                    }
                }
            }
        }
        if (ui.buffering && ui.error == null && !ui.empty) {
            Text(
                if (ui.bufferSpeed.isBlank()) "正在缓冲" else "正在缓冲  ${ui.bufferSpeed}",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (confirmLeave) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(Color(0xE612151C)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("退出播放？", color = Color.White, fontSize = 22.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    TvButton("继续看", primary = true) { confirmLeave = false }
                    TvButton("退出") { onBack() }
                }
            }
        }
    }
}
