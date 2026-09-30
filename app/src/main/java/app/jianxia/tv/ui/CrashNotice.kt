package app.jianxia.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jianxia.tv.CrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CrashNotice() {
    val context = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val files = remember { context.applicationContext.filesDir }
    var text by remember { mutableStateOf(CrashStore.notice(files)) }
    var safe by remember { mutableStateOf(CrashStore.safeMode) }
    if (text.isBlank() && !safe) return
    val focus = remember { FocusRequester() }
    val palette = LocalPalette.current
    LaunchedEffect(Unit) {
        delay(64)
        runCatching { focus.requestFocus() }
    }
    val message = text.ifBlank {
        "已进入安全模式：这次不读首页缓存，也不自动加载爬虫。连续两次启动没有完成就会这样。"
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).background(palette.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (safe) "安全模式" else "上次闪退", color = palette.text, fontSize = 28.sp)
            Text(message, color = palette.muted, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("知道了", primary = true, modifier = Modifier.focusRequester(focus)) {
                    CrashStore.acknowledge(files)
                    text = ""
                    safe = false
                }
                TvButton("复制日志") {
                    val raw = CrashStore.read(files).ifBlank { message }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("crash", raw))
                }
                if (safe) {
                    TvButton("退出安全模式") {
                        CrashStore.leaveSafeMode()
                        scope.launch(Dispatchers.IO) {
                            app.spiders.setEnabled(app.settings.state.value.spiderEnabled)
                        }
                        CrashStore.acknowledge(files)
                        text = ""
                        safe = false
                    }
                }
            }
        }
    }
}
