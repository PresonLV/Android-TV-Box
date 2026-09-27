package app.jianxia.core.parser

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 把常见的 TVBox 配置正文还原成可解析的文本。
 * 只做解码，不下载、不执行其中的脚本。
 */
object ConfigDecoder {
    private val marker = Regex("[A-Za-z0-9]{8}\\*\\*")

    fun decode(bytes: ByteArray, contentType: String? = null): String {
        if (bytes.isEmpty()) return ""
        if (isImage(bytes, contentType)) {
            val embedded = extractMarker(bytes) ?: return ""
            return normalize(String(embedded, Charsets.UTF_8))
        }
        val asText = decodeBytes(bytes, contentType)
        if (startsDocument(asText)) return normalize(asText)
        val embedded = extractMarker(bytes)
        if (embedded != null) return normalize(String(embedded, Charsets.UTF_8))
        return normalize(asText)
    }

    /** 文本层面的还原：Base64、2423 密文、注释和尾逗号。 */
    fun normalize(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF")
        for (ignored in 0 until 4) {
            val next = unwrapText(text) ?: break
            if (next == text) break
            text = next.trim().removePrefix("\uFEFF")
        }
        return if (looksLikeJson(text)) relaxJson(text) else text
    }

    fun relaxJson(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        var inString = false
        var escape = false
        while (index < raw.length) {
            val char = raw[index]
            if (inString) {
                out.append(char)
                when {
                    escape -> escape = false
                    char == '\\' -> escape = true
                    char == '"' -> inString = false
                }
                index += 1
                continue
            }
            if (char == '"') {
                inString = true
                out.append(char)
                index += 1
                continue
            }
            if (char == '/' && index + 1 < raw.length && raw[index + 1] == '/') {
                index += 2
                while (index < raw.length && raw[index] != '\n') index += 1
                continue
            }
            if (char == '/' && index + 1 < raw.length && raw[index + 1] == '*') {
                index += 2
                while (index + 1 < raw.length && !(raw[index] == '*' && raw[index + 1] == '/')) index += 1
                index = minOf(raw.length, index + 2)
                continue
            }
            if (char == ',') {
                var look = index + 1
                while (look < raw.length && raw[look].isWhitespace()) look += 1
                if (look < raw.length && (raw[look] == '}' || raw[look] == ']')) {
                    index += 1
                    continue
                }
            }
            out.append(char)
            index += 1
        }
        return out.toString()
    }

    private fun unwrapText(text: String): String? {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (startsDocument(trimmed)) return null
        val compact = trimmed.replace(Regex("\\s+"), "")
        if (compact.startsWith("2423")) {
            val plain = decrypt2423(compact) ?: return null
            return String(plain, Charsets.UTF_8)
        }
        if (marker.containsMatchIn(trimmed)) {
            val embedded = extractMarker(trimmed.toByteArray(Charsets.ISO_8859_1))
            if (embedded != null) return String(embedded, Charsets.UTF_8)
        }
        val decoded = tryBase64(compact) ?: return null
        return String(decoded, Charsets.UTF_8)
    }

    private fun startsDocument(text: String): Boolean {
        val trimmed = text.trimStart().removePrefix("\uFEFF")
        return trimmed.startsWith("{") ||
            trimmed.startsWith("[") ||
            trimmed.startsWith("<") ||
            trimmed.startsWith("#")
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart().removePrefix("\uFEFF")
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun isImage(bytes: ByteArray, contentType: String?): Boolean {
        val type = contentType.orEmpty().lowercase()
        if (type.contains("image/jpeg") || type.contains("image/png") || type.contains("image/jpg")) return true
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return true
        }
        return false
    }

    private fun extractMarker(bytes: ByteArray): ByteArray? {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val match = marker.find(latin) ?: return null
        val tail = latin.substring(match.range.last + 1)
        val alphabet = buildString {
            for (char in tail) {
                if (char.isLetterOrDigit() || char == '+' || char == '/' || char == '=' || char == '-' || char == '_') {
                    append(char)
                } else if (char.isWhitespace()) {
                    continue
                } else {
                    break
                }
            }
        }
        if (alphabet.length < 8) return null
        return Base64Text.decode(alphabet)
    }

    private fun tryBase64(compact: String): ByteArray? {
        if (compact.length < 16 || compact.length % 4 == 1) return null
        if (!compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) return null
        val decoded = Base64Text.decode(compact) ?: return null
        val preview = String(decoded, Charsets.UTF_8).trimStart().removePrefix("\uFEFF")
        val useful = preview.startsWith("{") ||
            preview.startsWith("[") ||
            preview.startsWith("2423") ||
            marker.containsMatchIn(preview.take(80))
        return decoded.takeIf { useful }
    }

    /**
     * 2423 开头的十六进制正文：`$#` 与 `#$` 之间是密钥，末尾 13 个字符是 IV，
     * 中间是 AES/CBC 密文。密钥和 IV 用字符 0 补到 16 字节。
     */
    private fun decrypt2423(hex: String): ByteArray? {
        val cleaned = hex.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (!cleaned.startsWith("2423") || cleaned.length < 40 || cleaned.length % 2 != 0) return null
        val raw = hexToBytes(cleaned) ?: return null
        val decoded = String(raw, Charsets.ISO_8859_1).lowercase()
        val keyStart = decoded.indexOf("$#")
        val keyEnd = decoded.indexOf("#$")
        if (keyStart < 0 || keyEnd <= keyStart + 2) return null
        val key = pad16(decoded.substring(keyStart + 2, keyEnd))
        if (decoded.length < 13) return null
        val iv = pad16(decoded.takeLast(13))
        val mark = cleaned.indexOf("2324")
        if (mark < 0) return null
        val contentEnd = cleaned.length - 26
        if (contentEnd <= mark + 4) return null
        val cipher = hexToBytes(cleaned.substring(mark + 4, contentEnd)) ?: return null
        return runCatching {
            val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
            aes.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
            )
            aes.doFinal(cipher)
        }.getOrNull()
    }

    private fun pad16(value: String): String = (value + "0000000000000000").take(16)

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}

/** 不依赖 API 26 的 Base64，方便在 Android 5 上解码配置。 */
internal object Base64Text {
    private val values = IntArray(128) { -1 }.also { table ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { index, char -> table[char.code] = index }
        table['-'.code] = table['+'.code]
        table['_'.code] = table['/'.code]
    }

    fun decode(input: String): ByteArray? {
        val clean = buildString(input.length) {
            input.forEach { char ->
                if (char.isLetterOrDigit() || char == '+' || char == '/' || char == '-' || char == '_') append(char)
            }
        }
        if (clean.length < 2) return null
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in clean) {
            if (char.code >= 128) return null
            val value = values[char.code]
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }
}

fun resolveAgainst(baseUrl: String?, value: String): String {
    val raw = value.trim()
    if (raw.isEmpty() || baseUrl.isNullOrBlank()) return raw
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    if (raw.startsWith("//")) return "https:$raw"
    val relative = raw.startsWith("./") || raw.startsWith("../") || raw.startsWith("/")
    if (!relative) return raw
    return runCatching { java.net.URI(baseUrl).resolve(raw).toString() }.getOrDefault(raw)
}
