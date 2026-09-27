package app.jianxia.core.hls

import java.net.URI

data class HlsRewrite(
    val text: String,
    val removed: Int,
    val master: Boolean,
)

/**
 * 改写 HLS 播放列表，去掉夹在不连续标记里、时长或主机明显异常的切片。
 * 用户规则是地址上的正则。去掉的比例过高时只保留用户规则，避免把正片删掉。
 */
object HlsAdFilter {
    fun compileRules(raw: String): List<Regex> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line -> runCatching { Regex(line, RegexOption.IGNORE_CASE) }.getOrNull() }
        .take(40)
        .toList()

    fun looksLikePlaylist(body: String): Boolean = body.contains("#EXTM3U", ignoreCase = true)

    fun rewrite(
        playlist: String,
        baseUrl: String,
        rules: List<Regex>,
        nestPlaylist: (String) -> String = { it },
    ): HlsRewrite {
        if (!looksLikePlaylist(playlist)) return HlsRewrite(playlist, 0, false)
        val lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val master = lines.any { it.trim().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        if (master) return HlsRewrite(rewriteMaster(lines, baseUrl, nestPlaylist), 0, true)
        return rewriteMedia(lines, baseUrl, rules, nestPlaylist)
    }

    private fun rewriteMaster(lines: List<String>, baseUrl: String, nestPlaylist: (String) -> String): String {
        val out = ArrayList<String>(lines.size)
        var expectVariant = false
        for (raw in lines) {
            val line = raw.trim()
            if (expectVariant) {
                expectVariant = false
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    out += nestPlaylist(resolve(baseUrl, line))
                    continue
                }
            }
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                expectVariant = true
                out += line
                continue
            }
            out += rewriteQuotedUris(line, baseUrl, nestPlaylist)
        }
        return out.joinToString("\n")
    }

    private fun rewriteMedia(
        lines: List<String>,
        baseUrl: String,
        rules: List<Regex>,
        nestPlaylist: (String) -> String,
    ): HlsRewrite {
        val head = ArrayList<String>()
        val segments = ArrayList<Segment>()
        var prelude = ArrayList<String>()
        var discontinuity = false
        var duration = 0.0
        var sawInf = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    duration = EXTINF.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    sawInf = true
                    prelude += line
                }
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> discontinuity = true
                line.startsWith("#") -> {
                    if (sawInf) prelude += rewriteQuotedUris(line, baseUrl) { it }
                    else head += rewriteQuotedUris(line, baseUrl, nestPlaylist)
                }
                sawInf -> {
                    segments += Segment(
                        duration = duration,
                        url = resolve(baseUrl, line),
                        discontinuityBefore = discontinuity,
                        prelude = prelude,
                    )
                    prelude = ArrayList()
                    discontinuity = false
                    duration = 0.0
                    sawInf = false
                }
                else -> head += resolve(baseUrl, line)
            }
        }
        if (segments.isEmpty()) return HlsRewrite(lines.joinToString("\n"), 0, false)
        val drop = decide(segments, rules)
        if (drop.none { it }) return HlsRewrite(emit(head, segments, drop), 0, false)
        val removed = drop.count { it }
        if (removed >= segments.size) return HlsRewrite(lines.joinToString("\n"), 0, false)
        return HlsRewrite(emit(head, segments, drop), removed, false)
    }

    private fun decide(segments: List<Segment>, rules: List<Regex>): BooleanArray {
        val drop = BooleanArray(segments.size)
        segments.forEachIndexed { index, segment ->
            if (rules.any { runCatching { it.containsMatchIn(segment.url) }.getOrDefault(false) }) drop[index] = true
        }
        val durations = segments.map { it.duration }.filter { it > 0.2 }
        val median = durations.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val hosts = segments.map { hostOf(it.url) }.filter { it.isNotEmpty() }
        val dominant = hosts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val dominantShare = if (hosts.isEmpty() || dominant.isEmpty()) 0.0 else hosts.count { it == dominant } / hosts.size.toDouble()
        if (median >= 2.0 && segments.size >= 4) {
            var index = 0
            while (index < segments.size) {
                val start = index
                index += 1
                while (index < segments.size && !segments[index].discontinuityBefore) index += 1
                val run = segments.subList(start, index)
                val bounded = segments[start].discontinuityBefore || index < segments.size
                if (!bounded || run.size >= segments.size / 2) continue
                val avg = run.map { it.duration }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
                val runHost = run.map { hostOf(it.url) }.filter { it.isNotEmpty() }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val short = avg > 0 && avg < median * 0.55
                val foreign = dominantShare >= 0.6 && runHost.isNotEmpty() && runHost != dominant
                if (short || (foreign && avg > 0 && avg < median * 0.85)) {
                    for (offset in start until index) drop[offset] = true
                }
            }
            segments.forEachIndexed { index, segment ->
                val odd = segment.duration > 0 && segment.duration < minOf(1.2, median * 0.3)
                val foreign = dominantShare >= 0.6 && hostOf(segment.url).let { it.isNotEmpty() && it != dominant }
                val beside = segment.discontinuityBefore || segments.getOrNull(index + 1)?.discontinuityBefore == true
                if (odd && beside && foreign) drop[index] = true
            }
        }
        val heuristic = segments.indices.count { index ->
            drop[index] && rules.none { runCatching { it.containsMatchIn(segments[index].url) }.getOrDefault(false) }
        }
        if (segments.isNotEmpty() && heuristic * 100 / segments.size > 35) {
            segments.forEachIndexed { index, segment ->
                drop[index] = rules.any { runCatching { it.containsMatchIn(segment.url) }.getOrDefault(false) }
            }
        }
        return drop
    }

    private fun emit(head: List<String>, segments: List<Segment>, drop: BooleanArray): String {
        val out = ArrayList<String>()
        out += head
        var lastDisc = false
        segments.forEachIndexed { index, segment ->
            if (drop[index]) return@forEachIndexed
            if (segment.discontinuityBefore && out.isNotEmpty() && !lastDisc) {
                out += "#EXT-X-DISCONTINUITY"
                lastDisc = true
            } else if (!segment.discontinuityBefore) {
                lastDisc = false
            }
            segment.prelude.forEach { line ->
                if (!line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true)) out += line
            }
            out += segment.url
            lastDisc = false
        }
        return out.joinToString("\n")
    }

    private fun rewriteQuotedUris(line: String, baseUrl: String, nest: (String) -> String = { it }): String {
        if (!line.contains("URI=", ignoreCase = true)) return line
        return URI_ATTR.replace(line) { match ->
            val resolved = resolve(baseUrl, match.groupValues[1])
            val next = if (resolved.contains(".m3u8", ignoreCase = true)) nest(resolved) else resolved
            "URI=\"$next\""
        }
    }

    fun resolve(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    private data class Segment(
        val duration: Double,
        val url: String,
        val discontinuityBefore: Boolean,
        val prelude: List<String>,
    )

    private val EXTINF = Regex("""#EXTINF:([0-9.]+)""", RegexOption.IGNORE_CASE)
    private val URI_ATTR = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
}
