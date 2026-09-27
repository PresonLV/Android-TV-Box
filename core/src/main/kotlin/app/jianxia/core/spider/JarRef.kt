package app.jianxia.core.spider

fun spiderBudgetMs(searchTimeoutSec: Int): Long =
    maxOf(searchTimeoutSec.coerceIn(3, 30), 12).coerceAtMost(20) * 1000L

data class JarRef(val url: String, val md5: String?)

private val HEX32 = Regex("[0-9a-fA-F]{32}")

/** 配置里的 jar 写成 `地址`、`地址;md5` 或 `地址;md5;哈希`。 */
fun parseJarRef(raw: String): JarRef? {
    val parts = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    val url = parts.firstOrNull().orEmpty()
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val md5 = when {
        parts.size >= 3 && parts[1].equals("md5", true) && HEX32.matches(parts[2]) -> parts[2].lowercase()
        parts.size >= 2 && HEX32.matches(parts[1]) -> parts[1].lowercase()
        else -> parts.drop(1).firstOrNull { HEX32.matches(it) }?.lowercase()
    }
    return JarRef(url, md5)
}

fun jarClassNames(api: String): List<String> {
    val name = api.trim()
    if (name.contains(".")) return listOf(name)
    val bare = name.removePrefix("csp_")
    return listOf(
        "com.github.catvod.spider.$bare",
        "com.github.catvod.spider.$name",
        name,
    ).distinct()
}
