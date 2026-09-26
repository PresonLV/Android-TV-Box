package app.jianxia.tv.data.source

import app.jianxia.core.model.SiteKind
import app.jianxia.core.model.VodItem
import app.jianxia.core.model.VodPage
import app.jianxia.core.model.VodSiteDef
import app.jianxia.core.parser.MacCmsJsonParser
import app.jianxia.core.parser.MacCmsXmlParser
import app.jianxia.core.parser.macCmsUrl
import app.jianxia.tv.data.net.NetClient

interface VodCatalog {
    val def: VodSiteDef
    suspend fun list(page: Int, typeId: String?): VodPage
    suspend fun search(keyword: String): VodPage
    suspend fun detail(id: String): VodItem?
}

fun interface CatalogFactory {
    fun create(def: VodSiteDef, http: NetClient): VodCatalog?
}

class CatalogRegistry(private val http: NetClient) {
    private val factories = mutableListOf<CatalogFactory>(MacCmsCatalogFactory())

    /** 以后接入 JAR/JS 爬虫时，把工厂插到最前面即可。 */
    fun register(factory: CatalogFactory) {
        factories.add(0, factory)
    }

    fun create(def: VodSiteDef): VodCatalog {
        factories.forEach { factory ->
            factory.create(def, http)?.let { return it }
        }
        return UnsupportedVodCatalog(def)
    }
}

class MacCmsCatalogFactory : CatalogFactory {
    override fun create(def: VodSiteDef, http: NetClient): VodCatalog? {
        if (def.kind != SiteKind.MACCMS_JSON && def.kind != SiteKind.MACCMS_XML) return null
        if (def.api.isBlank() || def.unsupportedReason != null) return null
        return MacCmsCatalog(def, http)
    }
}

class UnsupportedVodCatalog(override val def: VodSiteDef) : VodCatalog {
    override suspend fun list(page: Int, typeId: String?) = VodPage.empty()
    override suspend fun search(keyword: String) = VodPage.empty()
    override suspend fun detail(id: String): VodItem? = null
}

class MacCmsCatalog(
    override val def: VodSiteDef,
    private val http: NetClient,
) : VodCatalog {
    override suspend fun list(page: Int, typeId: String?): VodPage {
        val params = linkedMapOf("ac" to "list", "pg" to page.toString())
        if (!typeId.isNullOrBlank()) params["t"] = typeId
        return fetch(params)
    }

    override suspend fun search(keyword: String): VodPage {
        val action = if (def.kind == SiteKind.MACCMS_XML) "videolist" else "detail"
        return fetch(mapOf("ac" to action, "wd" to keyword))
    }

    override suspend fun detail(id: String): VodItem? {
        val action = if (def.kind == SiteKind.MACCMS_XML) "videolist" else "detail"
        val page = fetch(mapOf("ac" to action, "ids" to id))
        val item = page.items.firstOrNull { it.id == id } ?: page.items.firstOrNull() ?: return null
        if (item.lines.isEmpty() && def.kind == SiteKind.MACCMS_XML) {
            return fetch(mapOf("ac" to "detail", "ids" to id)).items.firstOrNull { it.id == id } ?: item
        }
        return item
    }

    private suspend fun fetch(params: Map<String, String>): VodPage {
        val url = macCmsUrl(def.api, params)
        val text = http.text(url)
        return when (def.kind) {
            SiteKind.MACCMS_XML -> MacCmsXmlParser.parse(text, def.key, def.name, def.api)
            else -> MacCmsJsonParser.parse(text, def.key, def.name, def.api)
        }
    }
}
