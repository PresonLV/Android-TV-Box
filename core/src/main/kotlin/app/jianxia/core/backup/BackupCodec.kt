package app.jianxia.core.backup

import app.jianxia.core.model.AppSettings
import app.jianxia.core.model.BackupBundle
import app.jianxia.core.model.BackupSource
import kotlinx.serialization.json.Json

object BackupCodec {
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    fun decode(raw: String): BackupBundle {
        val bundle = json.decodeFromString(BackupBundle.serializer(), raw.trim().removePrefix("\uFEFF"))
        if (bundle.version !in 1..CURRENT_VERSION) {
            throw IllegalArgumentException("无法识别的备份版本")
        }
        return bundle.copy(
            settings = bundle.settings.sanitized(),
            sources = bundle.sources.mapNotNull { it.cleaned() },
        )
    }

    private fun BackupSource.cleaned(): BackupSource? {
        val url = url.trim()
        if (url.isEmpty()) return null
        return copy(
            name = name.trim().ifBlank { url },
            url = url,
            kind = kind.trim(),
            epgUrl = epgUrl.trim(),
        )
    }

    const val CURRENT_VERSION = 1
}

fun AppSettings.withRecentSearch(query: String): AppSettings {
    val cleaned = query.trim()
    if (cleaned.isEmpty()) return this
    return copy(recentSearches = (listOf(cleaned) + recentSearches.filterNot { it == cleaned }).take(12)).sanitized()
}
