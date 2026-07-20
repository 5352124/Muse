package io.zer0.common

/**
 * v1.56: 轻量级性能追踪工具。
 *
 * 设计目标:
 *  - 零依赖(仅依赖 Logger),可在所有模块使用
 *  - 零开销:关闭时 track() 块内的 lambda 仍执行,但不计时不记录
 *  - 简洁:track("name") { ... } 一行搞定
 *  - 可控:通过 [enabled] 开关全局控制(默认 true,release 可关)
 *
 * 使用示例:
 *  ```
 *  val result = Perf.track("rag-index") {
 *      ragService.indexDocument(docId, content, config)
 *  }
 *  ```
 *
 * 日志格式: `[Perf] rag-index: 1234ms`
 * 慢操作(>500ms)会用 WARN 等级记录,否则 DEBUG。
 */
object Perf {

    @Volatile
    var enabled: Boolean = true

    /** 慢操作阈值(ms),超过此值用 WARN 等级记录。 */
    const val SLOW_THRESHOLD_MS = 500L

    /**
     * 计时执行 [block] 并记录耗时。
     *
     * @param name 操作名称(如 "rag-index" / "stream-first-token")
     * @param block 要计时的代码块
     * @return block 的返回值
     */
    inline fun <T> track(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - start
            log(name, elapsed)
        }
    }

    /**
     * 计时执行 suspend [block] 并记录耗时。
     *
     * @param name 操作名称
     * @param block 要计时的 suspend 代码块
     * @return block 的返回值
     */
    suspend inline fun <T> trackSuspend(name: String, crossinline block: suspend () -> T): T {
        if (!enabled) return block()
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - start
            log(name, elapsed)
        }
    }

    /**
     * 手动记录一个耗时点(用于不方便用 track 包裹的场景)。
     *
     * @param name 操作名称
     * @param elapsedMs 耗时(毫秒)
     */
    fun log(name: String, elapsedMs: Long) {
        if (!enabled) return
        if (elapsedMs >= SLOW_THRESHOLD_MS) {
            Logger.w("Perf", "$name: ${elapsedMs}ms (slow)")
        } else {
            Logger.d("Perf", "$name: ${elapsedMs}ms")
        }
    }

    /**
     * 创建一个计时器(用于需要分段的场景)。
     *
     * 示例:
     *  ```
     *  val t = Perf.start("stream-round")
     *  // ... 第一阶段 ...
     *  t.split("embedding")
     *  // ... 第二阶段 ...
     *  t.split("search")
     *  // ... 第三阶段 ...
     *  t.end()  // 记录总耗时 + 各分段
     *  ```
     */
    fun start(name: String): Timer = Timer(name)

    /**
     * 分段计时器。
     */
    class Timer internal constructor(private val name: String) {
        private val startTime = System.currentTimeMillis()
        private val splits = mutableListOf<Pair<String, Long>>()

        /**
         * 记录一个分段点。
         * @param splitName 分段名称(如 "embedding" / "search")
         */
        fun split(splitName: String) {
            if (!enabled) return
            val now = System.currentTimeMillis()
            val lastTime = splits.lastOrNull()?.second ?: startTime
            splits.add(splitName to (now - lastTime))
        }

        /**
         * 结束计时,记录总耗时和各分段。
         */
        fun end() {
            if (!enabled) return
            val total = System.currentTimeMillis() - startTime
            if (splits.isEmpty()) {
                log(name, total)
            } else {
                val splitStr = splits.joinToString(", ") { "${it.first}=${it.second}ms" }
                if (total >= SLOW_THRESHOLD_MS) {
                    Logger.w("Perf", "$name: ${total}ms (slow) [$splitStr]")
                } else {
                    Logger.d("Perf", "$name: ${total}ms [$splitStr]")
                }
            }
        }
    }
}
