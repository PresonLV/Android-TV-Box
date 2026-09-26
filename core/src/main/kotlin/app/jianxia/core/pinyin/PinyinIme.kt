package app.jianxia.core.pinyin

class PinyinIme(dictText: String) {
    private val dict: Map<String, String>

    init {
        val merged = linkedMapOf<String, StringBuilder>()
        dictText.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@forEach
            val key = parts[0].lowercase()
            val chars = parts[1].replace(" ", "")
            merged.getOrPut(key) { StringBuilder() }.append(chars)
        }
        dict = merged.mapValues { it.value.toString() }
    }

    fun candidates(buffer: String, limit: Int = 10): List<String> {
        val key = buffer.lowercase().filter { it in 'a'..'z' }
        if (key.isEmpty()) return emptyList()
        val exact = dict[key]
        if (exact != null) return exact.map { it.toString() }.distinct().take(limit)
        return dict.entries
            .asSequence()
            .filter { it.key.startsWith(key) }
            .sortedWith(compareBy({ it.key.length }, { it.key }))
            .flatMap { it.value.asSequence() }
            .distinct()
            .take(limit)
            .map { it.toString() }
            .toList()
    }

    companion object {
        fun loadDefault(): PinyinIme {
            val text = PinyinIme::class.java.getResourceAsStream("/pinyin_dict.txt")
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            return PinyinIme(text)
        }
    }
}
