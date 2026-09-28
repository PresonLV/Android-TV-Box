package app.jianxia.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ShelfToggle(
    val id: String,
    val title: String,
    val visible: Boolean = true,
)

object UiDiy {
    val defaultActions: List<ShelfToggle> = listOf(
        ShelfToggle("history", "历史"),
        ShelfToggle("live", "直播"),
        ShelfToggle("search", "搜索"),
        ShelfToggle("push", "推送"),
        ShelfToggle("favorite", "收藏"),
        ShelfToggle("settings", "设置"),
    )

    fun sanitizeActions(saved: List<ShelfToggle>): List<ShelfToggle> = mergeKnown(defaultActions, saved)

    fun sanitizeTabs(saved: List<ShelfToggle>): List<ShelfToggle> =
        saved.mapNotNull { item ->
            val id = item.id.trim().take(80)
            if (id.isEmpty()) null else item.copy(id = id, title = item.title.trim().ifBlank { id }.take(24))
        }.distinctBy { it.id }.take(24)

    fun mergeTabs(saved: List<ShelfToggle>, classes: List<Pair<String, String>>): List<ShelfToggle> {
        val incoming = buildList {
            add(ShelfToggle("home", "主页"))
            classes.forEach { (id, name) ->
                val key = id.trim().take(80)
                if (key.isNotEmpty() && key != "home") add(ShelfToggle(key, name.trim().ifBlank { key }.take(24)))
            }
        }.distinctBy { it.id }
        if (saved.isEmpty()) return incoming
        val titles = incoming.associate { it.id to it.title }
        val known = titles.keys
        val kept = saved.filter { it.id in known }.distinctBy { it.id }.map { item ->
            item.copy(title = titles[item.id] ?: item.title)
        }
        val present = kept.map { it.id }.toSet()
        return kept + incoming.filter { it.id !in present }
    }

    fun visibleTabs(saved: List<ShelfToggle>, classes: List<Pair<String, String>>): List<ShelfToggle> {
        val shown = mergeTabs(saved, classes).filter { it.visible }
        return shown.ifEmpty { listOf(ShelfToggle("home", "主页")) }
    }

    fun actionsOf(settings: AppSettings): List<ShelfToggle> = sanitizeActions(settings.homeActions)

    private fun mergeKnown(defaults: List<ShelfToggle>, saved: List<ShelfToggle>): List<ShelfToggle> {
        val titles = defaults.associate { it.id to it.title }
        val kept = saved.filter { it.id in titles }.distinctBy { it.id }.map { item ->
            item.copy(title = titles.getValue(item.id))
        }
        val present = kept.map { it.id }.toSet()
        return kept + defaults.filter { it.id !in present }
    }
}

fun <T> List<T>.shifted(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
