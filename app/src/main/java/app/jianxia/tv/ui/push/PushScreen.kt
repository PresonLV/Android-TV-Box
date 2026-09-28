package app.jianxia.tv.ui.push

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import app.jianxia.core.model.MergedVod
import app.jianxia.core.model.PlayLine
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.Episode
import app.jianxia.tv.PlayRequest
import app.jianxia.tv.ui.Keycap
import app.jianxia.tv.ui.LocalApp
import app.jianxia.tv.ui.LocalPalette
import app.jianxia.tv.ui.PhoneQrCard
import app.jianxia.tv.ui.ScreenPadding
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
    var note by remember { mutableStateOf("把 m3u8 或 mp4 地址推到电视上播放。配置仍建议用右侧二维码。") }
    BackHandler(onBack = onBack)
    Row(Modifier.fillMaxSize().padding(ScreenPadding)) {
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
            Text("推送", color = palette.text, fontSize = 28.sp)
            Text(note, color = palette.muted, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            Text(url.ifBlank { "播放地址" }, color = if (url.isBlank()) palette.muted else palette.accent, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
            listOf("http://", "https://", ".m3u8", ".mp4").chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { token -> TvButton(token) { url += token } }
                }
            }
            "abcdefghijklmnopqrstuvwxyz".chunked(7).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { char -> Keycap(char.toString()) { url += char } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TvButton("退格") { url = url.dropLast(1) }
                TvButton("清空") { url = "" }
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
        PhoneQrCard(modifier = Modifier.padding(start = 24.dp))
    }
}
