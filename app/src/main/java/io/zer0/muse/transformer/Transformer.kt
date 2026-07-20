package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage

/**
 * 消息 Transformer 接口(Phase 8.1 H1)。
 *
 * 独立编写。
 * 所有对消息列表的预处理/后处理都抽象为 Transformer,
 * 按 [TransformerPipeline] 顺序串成管道执行。
 *
 * 设计原则:
 *  - 纯函数: 同输入同输出,无副作用(除 suspend 标记的 IO)
 *  - 可组合: 多个 Transformer 串联,顺序明确
 *  - 可测试: 每个 Transformer 独立单元测试
 *  - 可配置: 由调用方决定启用哪些 Transformer(如 Assistant 配置)
 *
 * 用法:
 * ```
 * class MyTransformer : Transformer {
 *     override val name = "MyTransformer"
 *     override suspend fun transform(messages: List<UIMessage>): List<UIMessage> = ...
 * }
 *
 * val pipeline = TransformerPipeline(listOf(MyTransformer(), AnotherTransformer()))
 * val processed = pipeline.execute(rawMessages)
 * ```
 *
 * 注意: Transformer 处理的是"待发送的消息列表",不是流式增量。
 * 流式响应由 Provider 直接发 ChatStreamEvent,不走 Transformer。
 */
interface Transformer {
    /** Transformer 唯一名(用于调试/日志)。 */
    val name: String

    /**
     * 处理消息列表,返回新的列表(不可变,原列表不变)。
     * suspend 允许做 IO(如读 memory.db / 调用 LLM 压缩)。
     *
     * @param context 当前请求的上下文(模型/温度/会话 id 等)
     */
    suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage>
}

/**
 * Transformer 执行上下文。
 *
 * @param sessionId 当前会话 id(用于 memory 读写)
 * @param modelId 当前模型 id(用于决定是否启用 reasoning 等)
 * @param temperature 采样温度
 * @param maxTokens 输出上限
 * @param extras 额外参数(各 Transformer 自定义 key)。类型不安全,取值时须由调用方自行校验/转换。
 *  已知 key(供各 Transformer 参考,非穷尽):
 *   - "memory_enabled": Boolean — 是否注入长期记忆摘要
 *   - "compress_threshold": Int — 触发历史压缩的消息条数阈值
 *   - "lorebook_entries": List<*> — Lorebook 条目(世界书/L2 关键词触发注入)
 *   - "prompt_injections": List<String> — 运行时附加注入的提示片段
 *   - "time_reminder_enabled": Boolean — 是否启用时间提醒 section
 *   - "pinned_memories_enabled": Boolean — 是否注入 Pinned Memories
 *   - "tool_manifest_enabled": Boolean — 是否注入工具清单
 *   - "group_chat": Boolean — 是否处于群聊环境
 *  各 Transformer 自定义新 key 时请在对应 Transformer 的 KDoc 中声明,避免命名冲突。
 */
data class TransformContext(
    val sessionId: String? = null,
    val modelId: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val extras: Map<String, Any?> = emptyMap(),
) {
    /** 便捷取值。 */
    fun extra(key: String): Any? = extras[key]
}
