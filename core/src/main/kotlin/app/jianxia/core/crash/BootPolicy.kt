package app.jianxia.core.crash

/** 连续两次启动没走完就退出，下一次进入安全模式。 */
object BootPolicy {
    const val MARKER_BOOTING = "booting"
    const val MARKER_READY = "ready"
    const val SAFE_AFTER = 2

    fun nextCount(previousMarker: String, previousCount: Int): Int {
        val count = previousCount.coerceAtLeast(0)
        return if (previousMarker == MARKER_BOOTING) count + 1 else count
    }

    fun safeMode(count: Int): Boolean = count >= SAFE_AFTER
}
