package app.jianxia.tv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.SpiderMode
import app.jianxia.core.parser.TvBoxConfigParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** 设备上统计 JAR 爬虫。配置地址由 adb 参数 url 传入，不写进安装包。 */
@RunWith(AndroidJUnit4::class)
class SpiderDeviceProbe {
    @Test
    fun jarSites() {
        val url = InstrumentationRegistry.getArguments().getString("url").orEmpty()
        check(url.startsWith("http")) { "缺少 url 参数" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianXiaApp
        runBlocking { app.container.settings.update { it.copy(spiderEnabled = true) } }
        app.container.spiders.setEnabled(true)
        check(app.container.spiders.enabled) { "爬虫没有打开" }
        val document = app.container.http.fetchConfig(url)
        val config = TvBoxConfigParser.parse(document.text, document.finalUrl)
        val jars = config.sites.filter { it.kind == SiteKind.SPIDER && it.spiderMode == SpiderMode.JAR }
        var home = 0
        var category = 0
        var search = 0
        var play = 0
        Log.i(TAG, "jar count=${jars.size} url=$url")
        jars.forEach { site ->
            val homePage = runCatching { app.container.spiders.home(site) }
            val homeOk = homePage.getOrNull()?.let { it.items.isNotEmpty() || it.classes.isNotEmpty() } == true
            if (homeOk) home += 1
            val typeId = homePage.getOrNull()?.classes?.firstOrNull()?.id
            val categoryPage = if (typeId == null) null else {
                runCatching { app.container.spiders.category(site, typeId, 1) }.getOrNull()
            }
            val categoryOk = categoryPage?.items?.isNotEmpty() == true
            if (categoryOk) category += 1
            val keyword = categoryPage?.items?.firstOrNull()?.title?.take(2)?.ifBlank { null } ?: "爱"
            val searchOk = runCatching { app.container.spiders.search(site, keyword) }.getOrNull()?.items?.isNotEmpty() == true
            if (searchOk) search += 1
            val sample = homePage.getOrNull()?.items?.firstOrNull() ?: categoryPage?.items?.firstOrNull()
            val playOk = runCatching {
                val direct = sample?.lines?.firstOrNull()?.episodes?.firstOrNull()
                val picked = if (direct != null) {
                    sample.lines.first().name to direct.url
                } else if (sample != null) {
                    val detail = app.container.spiders.detail(site, sample.id)
                    val line = detail?.lines?.firstOrNull()
                    val url = line?.episodes?.firstOrNull()?.url
                    if (line == null || url.isNullOrBlank()) null else line.name to url
                } else {
                    null
                }
                picked != null && app.container.spiders.play(site, picked.first, picked.second).url.isNotBlank()
            }.getOrDefault(false)
            if (playOk) play += 1
            val note = homePage.exceptionOrNull()?.message?.take(120) ?: "items=${homePage.getOrNull()?.items?.size ?: 0}"
            Log.i(TAG, "${site.name} home=$homeOk category=$categoryOk search=$searchOk play=$playOk $note")
        }
        Log.i(TAG, "JAR summary home=$home/${jars.size} category=$category/${jars.size} search=$search/${jars.size} play=$play/${jars.size}")
    }

    private companion object {
        const val TAG = "SpiderProbe"
    }
}
