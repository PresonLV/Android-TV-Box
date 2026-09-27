package app.jianxia.core.spider

/**
 * Android 的 32 位链接器只接受硬浮点 ARM。部分爬虫自带的 .so 标成了软浮点，
 * dlopen 会直接失败。这里只改 ELF 头里的 ABI 标记，不改指令。
 */
object ArmElf {
    private const val EM_ARM = 40
    private const val SOFT = 0x200
    private const val HARD = 0x400

    fun preferHardFloat(bytes: ByteArray): ByteArray {
        if (bytes.size < 40 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
            return bytes
        }
        if (bytes[4] != 1.toByte()) return bytes
        val machine = u16(bytes, 18)
        if (machine != EM_ARM) return bytes
        val flags = u32(bytes, 36)
        if (flags and SOFT == 0 || flags and HARD != 0) return bytes
        val copy = bytes.copyOf()
        putU32(copy, 36, (flags and SOFT.inv()) or HARD)
        return copy
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
