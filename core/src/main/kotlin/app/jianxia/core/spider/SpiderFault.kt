package app.jianxia.core.spider

/** 站点状态页要看到真实异常，而不是一律写成「爬虫执行失败」或「连接超时」。 */
object SpiderFault {
    fun explain(error: Throwable?): String {
        if (error == null) return "没有返回内容"
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        if (chain.isEmpty()) return "没有返回内容"
        return chain.joinToString(" ← ") { item ->
            val name = item.javaClass.simpleName.ifBlank { "Exception" }
            val message = item.message.orEmpty().replace('\n', ' ').trim().take(140)
            when {
                message.isBlank() || message == name -> name
                else -> "$name: $message"
            }
        }.take(420)
    }
}
