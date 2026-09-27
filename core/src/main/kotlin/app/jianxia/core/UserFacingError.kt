package app.jianxia.core

object UserFacingError {
    const val NOT_BACKUP = "这不是备份文件。如果要添加接口，请每行写一个网址。"
    const val DNS = "无法解析这个域名，请检查电视的网络或 DNS"
    const val TIMEOUT = "连接超时，请稍后重试"
    const val CONNECT = "连不上服务器"
    const val TLS = "安全连接失败"
    const val RETRY = "加载失败，可重试"

    fun message(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            val text = current.message.orEmpty()
            when {
                current is java.net.UnknownHostException -> return DNS
                text.contains("Unable to resolve host", ignoreCase = true) -> return DNS
                text.contains("No address associated", ignoreCase = true) -> return DNS
                current is java.net.SocketTimeoutException -> return TIMEOUT
                text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) -> return TIMEOUT
                current is java.net.ConnectException -> return CONNECT
                current is javax.net.ssl.SSLException -> return TLS
                text.contains("请求失败（") -> return text
                text.contains("Unexpected JSON") || text.contains("JSON input") || text.contains("Expected start of") -> return NOT_BACKUP
                text.contains("kotlinx.serialization") -> return NOT_BACKUP
            }
            current = current.cause
        }
        val chinese = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf { message -> message.any { it.code in 0x4E00..0x9FFF } } }
            .firstOrNull { it.contains("Exception").not() && it.contains("JSON").not() }
        return chinese ?: RETRY
    }
}
