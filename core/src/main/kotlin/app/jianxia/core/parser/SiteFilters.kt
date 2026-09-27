package app.jianxia.core.parser

import app.jianxia.core.model.FilterChoice
import app.jianxia.core.model.FilterGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** TVBox 根配置和爬虫 homeContent 里的 filters，收成同一组芯片。 */
object SiteFilters {
    fun parseRoot(element: JsonElement?): Map<String, Map<String, List<FilterGroup>>> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (siteKey, value) ->
            val types = parseAttached(value)
            if (siteKey.isBlank() || types.isEmpty()) null else siteKey to types
        }.toMap()
    }

    fun parseAttached(element: JsonElement?): Map<String, List<FilterGroup>> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (typeId, value) ->
            val groups = parseGroups(value)
            if (groups.isEmpty()) null else typeId to groups
        }.toMap()
    }

    fun parseGroups(element: JsonElement?): List<FilterGroup> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val key = obj.text("key") ?: return@mapNotNull null
            val name = obj.text("name") ?: key
            val choices = (obj["value"] as? JsonArray).orEmpty().mapNotNull { choice ->
                val row = choice as? JsonObject ?: return@mapNotNull null
                val label = row.text("n", "name") ?: return@mapNotNull null
                val value = row.text("v", "value").orEmpty()
                if (label.isBlank() || label == "全部" || value.isBlank()) return@mapNotNull null
                FilterChoice(label, value)
            }
            if (choices.isEmpty()) null else FilterGroup(key, name, choices)
        }
    }
}

fun cleanScore(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty().removeSuffix("分")
    val number = trimmed.toDoubleOrNull() ?: return null
    if (number <= 0.0 || number > 10.0) return null
    val scaled = (number * 10).toInt().coerceIn(0, 100)
    return "${scaled / 10}.${scaled % 10}"
}
