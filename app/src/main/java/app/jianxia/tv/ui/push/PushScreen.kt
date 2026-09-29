package app.jianxia.tv.ui.push

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jianxia.core.model.Episode
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.PlayLine
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodItem
import app.jianxia.tv.PlayRequest
import app.jianxia.tv.ui.Keycap
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ScreenPadding
import app.jianxia.tv.ui.SelectChip
import app.jianxia.tv.ui.TvButton

internal fun directPlay(url: String): PlayRequest {
    val item = MergedVod(
        key = "push:$url",
        title = "推送",
        variants = listOf(
            VodItem(
                sourceKey = "push",
                sourceName = "推送",
                api = "",
                siteKind = SiteKind.UNSUPPORTED,
                id = url,
                title = "推送",
                lines = listOf(PlayLine("播放", listOf(Episode("正片", url)))),
            ),
        ),
    )
    return PlayRequest(item, 0, 0, null)
}

@Composable
fun PushScreen(onBack: () -> Unit, onPlay: () -> Unit) {
    val app = LocalApp.current
    val palette = LocalPalette.current
    var url by remember { mutableStateOf("") }
    var buffer by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("pinyin") }
    var note by remember { mutableStateOf("遥控器可以输入拼音。手机扫右侧二维码，可以直接推送播放地址或搜索片名。") }
    val candidates = if (mode == "pinyin") app.pinyin.candidates(buffer) else emptyList()
    BackHandler(onBack = onBack)
    Row(
        Modifier.fillMaxSize().padding(ScreenPadding()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("推送", color = palette.text, fontSize = 32.sp)
                Text(note, color = palette.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                Text(url.ifBlank { "播放地址" }, color = if (url.isBlank()) palette.muted else palette.accent, fontSize = 18.sp)
                Text(if (buffer.isBlank()) "拼音" else buffer, color = palette.accent, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                if (candidates.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        candidates.forEach { char ->
                            SelectChip(char, false) {
                                url += char
                                buffer = ""
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    listOf("http://", "https://", ".m3u8", ".mp4").forEach { token -> TvButton(token) { url += token } }
                }
                val rows = if (mode == "symbol") listOf(".:/?=", "&-_#@", "%+~,") else "abcdefghijklmnopqrstuvwxyz".chunked(7)
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { char ->
                            Keycap(char.toString()) {
                                if (mode == "pinyin") buffer += char else url += char
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    "1234567890".forEach { digit -> Keycap(digit.toString()) { url += digit } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TvButton(when (mode) { "pinyin" -> "拼音"; "symbol" -> "符号"; else -> "ABC" }) {
                        mode = when (mode) {
                            "pinyin" -> "abc"
                            "abc" -> "symbol"
                            else -> "pinyin"
                        }
                        buffer = ""
                    }
                    TvButton("退格") {
                        if (buffer.isNotEmpty()) buffer = buffer.dropLast(1) else url = url.dropLast(1)
                    }
                    TvButton("清空") {
                        url = ""
                        buffer = ""
                    }
                    TvButton("播放", primary = true) {
                        val target = url.trim()
                        if (!target.startsWith("http://") && !target.startsWith("https://")) {
                            note = "没有找到片源：地址需要以 http:// 或 https:// 开头"
                            return@TvButton
                        }
                        app.session.request = directPlay(target)
                        onPlay()
                    }
                    TvButton("返回", onClick = onBack)
                }
            }
        }
        PhoneQrCard()
    }
}
