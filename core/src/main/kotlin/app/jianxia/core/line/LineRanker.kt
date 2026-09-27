package app.jianxia.core.line

import app.jianxia.core.model.LineProbe

fun lineScore(probe: LineProbe): Long {
    if (!probe.ok) return Long.MAX_VALUE
    val resolutionBias = when (val height = probe.resolutionHeight) {
        null -> 0L
        in 2160..Int.MAX_VALUE -> -300L
        in 1080..2159 -> -200L
        in 720..1079 -> -80L
        in 480..719 -> 0L
        else -> 120L
    }
    return probe.connectMs.coerceAtLeast(0) + probe.firstByteMs.coerceAtLeast(0) + resolutionBias
}

/** 分数更低的线路排在前面。失败线路保持在末尾，同分保持原顺序。 */
fun rankLines(probes: List<LineProbe>): List<LineProbe> =
    probes.withIndex()
        .sortedWith(compareBy<IndexedValue<LineProbe>> { lineScore(it.value) }.thenBy { it.index })
        .map { it.value }
