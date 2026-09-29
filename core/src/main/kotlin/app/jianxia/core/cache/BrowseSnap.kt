package app.jianxia.core.cache

import app.jianxia.core.model.MergedVod
import kotlinx.serialization.Serializable

@Serializable
data class BrowseSnap(
    val name: String = "",
    val classes: List<String> = emptyList(),
    val items: List<MergedVod> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
)
