package io.zer0.ai.core

import io.zer0.common.Logger

/**
 * Provider 能力矩阵(compatibility)。
 *
 * 不同 Provider / 中转 / 模型对 OpenAI 协议的支持程度不同(如 Anthropic 不支持
 * developer role、Gemini OpenAI 兼容层不支持 store、Zhipu 不支持 reasoning_effort)。
 * 本类把这些差异集中化,Provider 在请求构造前查询对应字段决定是否注入某些参数,
 * 避免在每个 Provider 内分散硬编码特判。
 *
 * 设计原则:
 *  - 保守默认值:不明确的字段一律 true(让 Provider 自己处理或忽略未知参数),
 *    仅在确证不支持时显式置 false。
 *  - 集中派生:所有差异判断集中在 [ProviderCompatRules.resolve],
 *    Provider 只消费结果,不重复推理。
 *
 * 字段语义:
 * @param supportsDeveloperRole 是否支持把 system role 切换为 developer role
 *   (OpenAI o1/o3 等推理模型要求用 developer role 传递系统指令)。
 * @param supportsStore 是否支持 OpenAI Chat Completions 的 store 参数
 *   (用于在 Assistants API 之外持久化对话)。
 * @param supportsReasoningEffort 是否支持 reasoning_effort 参数
 *   (OpenAI o 系列 / GPT-5 推理等级;DeepSeek/Anthropic/Gemini 各自用不同字段)。
 * @param supportsToolCalling 是否支持 function calling / tools 参数。
 * @param supportsVision 是否支持图片输入(多模态)。
 * @param supportsParallelToolCalls 是否支持 parallel_tool_calls 参数。
 * @param supportsJsonMode 是否支持 response_format={"type":"json_object"}。
 * @param supportsStreamOptions 是否支持 stream_options.include_usage。
 * @param supportsLogprobs 是否支持 logprobs 参数(默认 false,多数 Provider 不常用)。
 * @param maxContextWindow Provider 级上下文窗口上限(token),null 表示无统一上限。
 * @param defaultMaxTokens 默认 max_tokens,null 表示用 Provider 默认。
 *
 * 独立编写,参考 openhanako 项目的 core/provider-compat/ 目录实现思路。
 */
data class ProviderCompat(
    val supportsDeveloperRole: Boolean = true,
    val supportsStore: Boolean = true,
    val supportsReasoningEffort: Boolean = true,
    val supportsToolCalling: Boolean = true,
    val supportsVision: Boolean = true,
    val supportsParallelToolCalls: Boolean = true,
    val supportsJsonMode: Boolean = true,
    val supportsStreamOptions: Boolean = true,
    val supportsLogprobs: Boolean = false,
    val maxContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
)

/**
 * Provider 能力派生规则。
 *
 * 三层匹配策略(由粗到细,后者覆盖前者):
 *  1. 按 [ProviderType] 分发(基础规则,反映协议级差异)
 *  2. 按 baseUrl host 进一步细化(反映厂商 OpenAI 兼容层差异)
 *  3. 按 modelId 进一步细化(反映具体模型对工具调用/推理的支持情况)
 *
 * 任一层未命中时保持上一层的值,最终返回融合后的 [ProviderCompat]。
 *
 * 独立编写。
 */
object ProviderCompatRules {

    private const val TAG = "ProviderCompatRules"

    /**
     * 派生 [ProviderCompat]。
     *
     * @param providerType Provider 类型(决定协议级基础规则)
     * @param baseUrl Provider 的 baseUrl(已解析或原始均可,空串时跳过 host 匹配)
     * @param modelId 具体模型 id(可选,用于模型级细化)
     */
    fun resolve(
        providerType: ProviderType,
        baseUrl: String,
        modelId: String? = null,
    ): ProviderCompat {
        // 第 1 层:按 ProviderType 取基础规则
        var compat = baseForType(providerType)

        // 第 2 层:按 baseUrl host 细化
        val host = extractHost(baseUrl)
        if (host.isNotEmpty()) {
            compat = compat.overrideByHost(host)
        }

        // 第 3 层:按 modelId 细化
        if (!modelId.isNullOrBlank()) {
            compat = compat.overrideByModelId(modelId)
        }

        return compat
    }

    /**
     * 第 1 层:按 [ProviderType] 取基础规则。
     *
     * - OPENAI: 全部 true(默认,覆盖 OpenAI 官方 + Custom + 所有 OpenAI 兼容中转)
     * - ANTHROPIC: 不支持 developer role / store / json_mode / stream_options
     *   (Anthropic 走 Messages API,system 是顶层字段,无 store/json_mode/stream_options 概念)
     * - GEMINI: 不支持 store(OpenAI 兼容层限制) / reasoning_effort
     *   (Gemini 用 thinkingConfig.thinkingBudget 而非 reasoning_effort)
     *
     * 注:[ProviderType] 没有 CUSTOM 枚举值,Custom 走 OPENAI 分支(默认全 true),
     *   其差异由第 2 层 host 匹配处理。
     */
    private fun baseForType(providerType: ProviderType): ProviderCompat = when (providerType) {
        ProviderType.OPENAI -> ProviderCompat()
        ProviderType.ANTHROPIC -> ProviderCompat(
            supportsDeveloperRole = false,
            supportsStore = false,
            supportsJsonMode = false,
            supportsStreamOptions = false,
        )
        ProviderType.GEMINI -> ProviderCompat(
            supportsStore = false,
            supportsReasoningEffort = false,
        )
    }

    /**
     * 第 2 层:按 host 细化。
     *
     * 仅处理已知的国内/海外厂商 OpenAI 兼容端点差异:
     *  - api.deepseek.com: 支持 json_mode,不支持 reasoning_effort
     *    (DeepSeek 用 reasoning_content 返回思考链,不发 reasoning_effort 参数)
     *  - api.moonshot.cn: 支持 json_mode(Kimi 兼容 OpenAI json_object)
     *  - open.bigmodel.cn: 不支持 store,不支持 reasoning_effort
     *    (智谱 GLM 用 thinking 字段而非 reasoning_effort)
     *  - dashscope.aliyuncs.com: 不支持 store(阿里通义兼容层限制)
     *  - api.minimax.chat: 不支持 json_mode
     *  - api.siliconflow.cn: 默认全 true(SiliconFlow 聚合多数模型,默认透传)
     *  - openrouter.ai: 默认全 true(聚合各家,差异由 modelId 层处理)
     *
     * 未列出的 host 保持现有规则不变(保守默认 true)。
     */
    private fun ProviderCompat.overrideByHost(host: String): ProviderCompat {
        return when (host) {
            "api.deepseek.com" -> copy(
                supportsJsonMode = true,
                supportsReasoningEffort = false,
            )
            "api.moonshot.cn" -> copy(
                supportsJsonMode = true,
            )
            "open.bigmodel.cn" -> copy(
                supportsStore = false,
                supportsReasoningEffort = false,
            )
            "dashscope.aliyuncs.com" -> copy(
                supportsStore = false,
            )
            "api.minimax.chat" -> copy(
                supportsJsonMode = false,
            )
            "api.siliconflow.cn" -> this  // 默认全 true
            "openrouter.ai" -> this  // 默认全 true
            else -> this
        }
    }

    /**
     * 第 3 层:按 modelId 细化。
     *
     * 仅处理明确的模型级限制(保守,不臆测):
     *  - o1-preview / o1-mini: 早期 o1 模型不支持 tool calling(后续版本已支持)
     *  - deepseek-reasoner: DeepSeek-R1 推理模型,API 文档明确不支持 tool calling
     *
     * 其他 o1/o3/o4 系列保留 supportsToolCalling=true(后续版本已支持,
     * 不做版本猜测避免误关)。如后续发现具体版本不支持,可在此处补充。
     */
    private fun ProviderCompat.overrideByModelId(modelId: String): ProviderCompat {
        val id = modelId.lowercase()
        return when {
            // 早期 o1 推理模型不支持 tool calling(OpenAI 官方文档已声明)
            id == "o1-preview" || id == "o1-mini" -> copy(
                supportsToolCalling = false,
            )
            // DeepSeek-R1 推理模型(API id 为 deepseek-reasoner)不支持 tool calling
            id == "deepseek-reasoner" -> copy(
                supportsToolCalling = false,
            )
            else -> this
        }
    }

    /**
     * 从 baseUrl 提取 host(小写,去端口)。
     *
     * 兼容以下形式:
     *  - https://api.openai.com/v1
     *  - http://localhost:8080/v1
     *  - api.deepseek.com
     *  - 空串 / 异常 → 返回空串(由调用方判断跳过 host 匹配)
     */
    private fun extractHost(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { return "" }
        return try {
            // 简单实现:去协议前缀,取首个 / 之前的部分,去端口
            val noScheme = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
            noScheme.substringBefore(':')
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w(TAG, "extract host 失败(baseUrl=$baseUrl): ${t.message}")
            ""
        }
    }
}
