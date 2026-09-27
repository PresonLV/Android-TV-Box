package app.jianxia.core.subtitle

enum class SubAlign { Bottom, Middle, Top }

data class SubCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val align: SubAlign = SubAlign.Bottom,
    val color: Int? = null,
)

object Subtitles {
    fun parse(name: String, body: String): List<SubCue> {
        val lower = name.lowercase()
        val text = body.trimStart('\uFEFF')
        return when {
            lower.endsWith(".ass") || lower.endsWith(".ssa") || text.contains("[Script Info]", ignoreCase = true) -> ass(text)
            lower.endsWith(".vtt") || text.startsWith("WEBVTT", ignoreCase = true) -> vtt(text)
            lower.endsWith(".srt") || SRT_TIME.containsMatchIn(text) -> srt(text)
            else -> emptyList()
        }.filter { it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs }
    }

    fun visible(cues: List<SubCue>, positionMs: Long, offsetMs: Long): List<SubCue> {
        val at = positionMs + offsetMs
        return cues.filter { at in it.startMs until it.endMs }.take(4)
    }

    fun srt(body: String): List<SubCue> = blocks(body).mapNotNull { block ->
        val times = SRT_TIME.find(block) ?: return@mapNotNull null
        val text = block.substring(times.range.last + 1).trim().lines()
            .filter { it.isNotBlank() && !it.trim().all { char -> char.isDigit() } }
            .joinToString("\n") { cleanText(it) }
        cue(clock(times.groupValues[1]), clock(times.groupValues[2]), text, SubAlign.Bottom, null)
    }

    fun vtt(body: String): List<SubCue> = blocks(body).mapNotNull { block ->
        val times = VTT_TIME.find(block) ?: return@mapNotNull null
        val text = block.substring(times.range.last + 1).trim().lines()
            .filter { it.isNotBlank() && !it.startsWith("NOTE") && !it.startsWith("STYLE") }
            .joinToString("\n") { cleanText(it) }
        val align = when {
            block.contains("align:start", true) || block.contains("line:0%", true) -> SubAlign.Top
            block.contains("line:50%", true) -> SubAlign.Middle
            else -> SubAlign.Bottom
        }
        cue(clock(times.groupValues[1]), clock(times.groupValues[2]), text, align, null)
    }

    fun ass(body: String): List<SubCue> {
        val styles = HashMap<String, AssStyle>()
        var format: List<String> = DEFAULT_FORMAT
        var inStyles = false
        var inEvents = false
        val cues = ArrayList<SubCue>()
        body.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.equals("[V4+ Styles]", true) || line.equals("[V4 Styles]", true) -> {
                    inStyles = true
                    inEvents = false
                }
                line.equals("[Events]", true) -> {
                    inEvents = true
                    inStyles = false
                }
                line.startsWith("[") -> {
                    inStyles = false
                    inEvents = false
                }
                inStyles && line.startsWith("Format:", true) -> format = fields(line.substringAfter(":"))
                inStyles && line.startsWith("Style:", true) -> {
                    val values = fields(line.substringAfter(":"))
                    val mapped = format.zip(values).toMap()
                    val name = mapped["Name"].orEmpty()
                    if (name.isNotEmpty()) {
                        styles[name] = AssStyle(
                            align = alignment(mapped["Alignment"]?.toIntOrNull() ?: 2),
                            color = assColor(mapped["PrimaryColour"]),
                        )
                    }
                }
                inEvents && line.startsWith("Format:", true) -> format = fields(line.substringAfter(":"))
                inEvents && (line.startsWith("Dialogue:", true) || line.startsWith("Comment:", true)) -> {
                    if (line.startsWith("Comment:", true)) return@forEach
                    val values = splitDialogue(line.substringAfter(":"))
                    val mapped = HashMap<String, String>()
                    format.forEachIndexed { index, key ->
                        if (index < values.size - 1 || index == values.lastIndex) {
                            mapped[key] = if (index == format.lastIndex) values.drop(index).joinToString(",") else values.getOrElse(index) { "" }
                        }
                    }
                    val text = mapped["Text"].orEmpty()
                    val style = styles[mapped["Style"].orEmpty()]
                    val rendered = renderAss(text)
                    cue(
                        assClock(mapped["Start"].orEmpty()),
                        assClock(mapped["End"].orEmpty()),
                        rendered.text,
                        rendered.align ?: style?.align ?: SubAlign.Bottom,
                        rendered.color ?: style?.color,
                    )?.let { cues += it }
                }
            }
        }
        return cues
    }

    private fun renderAss(raw: String): Rendered {
        var align: SubAlign? = null
        var color: Int? = null
        val an = Regex("""\\an([1-9])""").find(raw)
        if (an != null) align = alignment(an.groupValues[1].toInt())
        val c = Regex("""\\c&H([0-9A-Fa-f]{6})""").find(raw)
        if (c != null) color = c.groupValues[1].toIntOrNull(16)
        val text = raw
            .replace(Regex("""\{[^}]*}"""), "")
            .replace("\\N", "\n", ignoreCase = true)
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()
        return Rendered(text, align, color)
    }

    private fun cue(start: Long, end: Long, text: String, align: SubAlign, color: Int?): SubCue? {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || end <= start) return null
        return SubCue(start, end, cleaned, align, color)
    }

    private fun blocks(body: String): List<String> =
        body.replace("\r\n", "\n").replace('\r', '\n').split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun clock(value: String): Long {
        val match = CLOCK.find(value.trim()) ?: return 0
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLongOrNull() ?: 0
        val seconds = match.groupValues[3].toLongOrNull() ?: 0
        val fraction = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction
    }

    private fun assClock(value: String): Long {
        val match = ASS_CLOCK.find(value.trim()) ?: return 0
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLongOrNull() ?: 0
        val seconds = match.groupValues[3].toLongOrNull() ?: 0
        val fraction = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction
    }

    private fun cleanText(line: String): String = line.replace(Regex("""</?[^>]+>"""), "").trim()

    private fun fields(value: String): List<String> = value.split(',').map { it.trim() }

    private fun splitDialogue(value: String): List<String> = value.trim().split(',')

    private fun alignment(value: Int): SubAlign = when (value) {
        7, 8, 9 -> SubAlign.Top
        4, 5, 6 -> SubAlign.Middle
        else -> SubAlign.Bottom
    }

    private fun assColor(raw: String?): Int? {
        val hex = raw?.trim()?.removePrefix("&H")?.removePrefix("&h")?.trimEnd('&') ?: return null
        val digits = hex.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (digits.length < 6) return null
        val bgr = digits.takeLast(6)
        return (bgr.substring(4, 6) + bgr.substring(2, 4) + bgr.substring(0, 2)).toIntOrNull(16)
    }

    private data class AssStyle(val align: SubAlign, val color: Int?)
    private data class Rendered(val text: String, val align: SubAlign?, val color: Int?)

    private val DEFAULT_FORMAT = listOf(
        "Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text",
    )
    private val CLOCK = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{1,3})""")
    private val ASS_CLOCK = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{1,2})""")
    private val SRT_TIME = Regex("""(\d+:\d{2}:\d{2}[.,]\d{1,3})\s*-->\s*(\d+:\d{2}:\d{2}[.,]\d{1,3})""")
    private val VTT_TIME = Regex("""(\d+:\d{2}:\d{2}\.\d{1,3})\s*-->\s*(\d+:\d{2}:\d{2}\.\d{1,3})""")
}
