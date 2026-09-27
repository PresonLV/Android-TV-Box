package app.jianxia.core.player

/**
 * 32 位 ARM 上 libVLC 更容易把进程打崩，未手动选过内核时默认用 ExoPlayer。
 */
fun preferredEngineWire(playerEngine: String, chosen: Boolean, abis: List<String>): String {
    if (chosen) return if (playerEngine == "exo") "exo" else "vlc"
    val primary = abis.firstOrNull().orEmpty().lowercase()
    val arm32 = primary.contains("armeabi") && !primary.contains("arm64") && !primary.contains("64")
    return if (arm32) "exo" else if (playerEngine == "exo") "exo" else "vlc"
}
