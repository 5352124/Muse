package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * Provider 厂商类型。决定 [ProviderRegistry] 走哪个分支构造 Provider。
 *
 * - [OPENAI]: OpenAI 官方 + 所有 OpenAI 兼容协议(Azure / 中转 / Ollama 等),走 Chat Completions
 * - [ANTHROPIC]: Anthropic Claude,走 Messages API
 * - [GEMINI]: Google Gemini,走 generateContent / streamGenerateContent
 */
@Serializable
enum class ProviderType {
    OPENAI,
    ANTHROPIC,
    GEMINI;

    companion object {
        /** 默认类型(向后兼容旧配置无 type 字段时)。 */
        val DEFAULT = OPENAI
    }
}
