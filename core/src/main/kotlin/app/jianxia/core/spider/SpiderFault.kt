package app.jianxia.core.spider

/** 站点状态页要看到真实异常，而不是一律写成「爬虫执行失败」或「连接超时」。 */
object SpiderFault {
    fun explain(error: Throwable?): String {
        if (error == null) return "没有返回内容"
        val root = generateSequence(error) { it.cause }.last()
        val name = root.javaClass.simpleName.ifBlank { "Exception" }
        val message = (root.message ?: error.message).orEmpty().replace('\n', ' ').trim()
        val clipped = message.take(90)
        return when {
            clipped.isBlank() -> name
            clipped == name -> name
            else -> "$name: $clipped"
        }
    }
}
