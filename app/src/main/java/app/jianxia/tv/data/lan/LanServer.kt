package app.jianxia.tv.data.lan

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.jianxia.core.UserFacingError
import app.jianxia.core.model.AppearanceCatalog
import app.jianxia.core.model.AppearanceItem
import app.jianxia.core.model.resetSection
import app.jianxia.core.model.withAppearance
import app.jianxia.tv.data.repo.BackupRepository
import app.jianxia.tv.data.repo.SettingsRepository
import app.jianxia.tv.data.repo.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
                    request.method == "POST" && route == "/api/appearance" -> {
                        val obj = parseObject(body)
                        if (obj.str("reset") == "look") {
                            settings.update { it.resetSection("look") }
                        } else {
                            settings.update { current ->
                                current.withAppearance(
                                    themeMode = obj.str("themeMode"),
                                    accent = obj.str("accent"),
                                    backgroundType = obj.str("backgroundType"),
                                    wallpaperId = obj.str("wallpaperId"),
                                    solidColor = obj.str("solidColor"),
                                    backgroundImageUrl = if (obj.containsKey("backgroundImageUrl")) obj.raw("backgroundImageUrl").orEmpty() else null,
                                    wallpaperBlur = obj.int("wallpaperBlur"),
                                    wallpaperDim = obj.int("wallpaperDim"),
                                    fontScale = obj.str("fontScale"),
                                )
                            }
                        }
                        appearanceJson("已保存")
                    }
                    request.method == "GET" && route == "/api/export" -> backup.export()
                    request.method == "POST" && route == "/api/sources" -> {
                        val obj = parseObject(body)
                        val url = obj.str("url").orEmpty()
                        sources.add(url, obj.str("name"), obj.str("epg"))
                        sourcesJson("已添加")
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
            })
            put("wallpapers", catalog(AppearanceCatalog.wallpapers))
            put("accents", catalog(AppearanceCatalog.accents))
            put("modes", catalog(AppearanceCatalog.modes))
            put("fonts", catalog(AppearanceCatalog.fonts))
            put("solids", catalog(AppearanceCatalog.solids))
        }.toString()
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
        if (length > 1_000_000) return null
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

    private fun page(unlocked: Boolean): String = if (unlocked) UNLOCKED_PAGE else LOCKED_PAGE

    private companion object {
        private const val LOCKED_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>简匣</title>
            <style>body{margin:0;font-family:sans-serif;background:#12151c;color:#f4f1ea}main{max-width:640px;margin:0 auto;padding:24px}p{color:#b7b1a6}</style>
            </head><body><main><h1>简匣</h1><p>请扫描电视上的二维码打开这个页面。口令只显示在电视屏幕上。</p></main></body></html>
        """
        private const val UNLOCKED_PAGE = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>简匣</title>
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
            <h1>简匣</h1>
            <p>在这里粘贴接口地址。电视不内置任何片源。</p>
            <label>名称（可选）<input id="name" placeholder="例如：家里的配置"></label>
            <label>地址<input id="url" placeholder="https://"></label>
            <label>节目单地址（直播可选）<input id="epg" placeholder="XMLTV 地址"></label>
            <button onclick="add()">添加</button>
            <p id="msg"></p>
            <div id="list"></div>
            <h2>外观</h2>
            <p>和电视上的设置是同一份。选好后点保存，电视会马上换上。</p>
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
            <h2>导入 / 导出</h2>
            <textarea id="backup" rows="6" placeholder="粘贴备份 JSON，或每行写一个网址"></textarea>
            <div class="row">
              <button onclick="importBackup()">导入并覆盖</button>
              <button class="ghost" onclick="exportBackup()">下载备份</button>
            </div>
            <script>
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
            async function add(){
              say('添加中…');
              try {
                const data = await api('/api/sources', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({name:document.getElementById('name').value,url:document.getElementById('url').value,epg:document.getElementById('epg').value})});
                document.getElementById('url').value='';
                say(data.message && data.message.indexOf('加载失败') < 0 ? ('成功。' + data.message) : (data.message || '成功'));
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
              });
            }
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
