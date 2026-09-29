package app.jianxia.tv.spider

import android.content.Context
import app.jianxia.core.model.AppSettings
import com.fongmi.android.tv.Setting

/** 把用户粘贴的网盘登录信息交给 JAR。播放时爬虫会读 Setting 或 SharedPreferences。 */
object DriveCookies {
    @Volatile var quark: String = ""
    @Volatile var uc: String = ""
    @Volatile var ali: String = ""

    fun apply(context: Context, settings: AppSettings) {
        val nextQuark = settings.quarkCookie.trim()
        val nextUc = settings.ucCookie.trim()
        val nextAli = settings.aliToken.trim()
        val changed = nextQuark != quark || nextUc != uc || nextAli != ali
        quark = nextQuark
        uc = nextUc
        ali = nextAli
        Setting.quark = nextQuark
        Setting.uc = nextUc
        Setting.ali = nextAli
        val packs = mapOf(
            "quark" to nextQuark,
            "quark_cookie" to nextQuark,
            "cookie" to nextQuark,
            "uc" to nextUc,
            "uc_cookie" to nextUc,
            "ali" to nextAli,
            "aliyun" to nextAli,
            "ali_token" to nextAli,
            "token" to nextAli,
        )
        listOf("setting", "pref", "sp_config", "tv").forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.edit().apply {
                packs.forEach { (key, value) ->
                    if (value.isNotEmpty()) putString(key, value) else remove(key)
                }
            }.apply()
        }
        if (changed) onChanged?.invoke()
    }

    var onChanged: (() -> Unit)? = null
}
