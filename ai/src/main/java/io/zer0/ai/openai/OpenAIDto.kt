package io.zer0.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI Chat Completions API 的请求/响应 DTO。
 *
 * Phase 7 扩展:tool_calls / tool_call_id / tools 字段(function calling)。
 * Phase 8.6 扩展:content 改为 JsonElement,支持多模态(string 或 parts 数组)。
 * 字段命名严格对齐 OpenAI 官方文档。
 */

@Serializable
internal data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Float? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = true,
    /** Phase 7: 工具定义列表,启用 function calling。 */
    val tools: List<OpenAITool>? = null,
    /**
     * Phase 8.5 修复: 推理等级(o1/gpt-5 系列的 reasoning_effort 字段)。
     * 仅当 reasoningLevel != OFF/AUTO 且 effort 非空时发送,值 "minimal"/"low"/"medium"/"high"。
     */
    val reasoning_effort: String? = null,
    // v1.80 (L-OAI10): 删除 response_format/OpenAIResponseFormat 死代码。
    //   原字段从未被赋值(始终 null),且 OpenAI JSON mode 需配合 schema 说明,
    //   当前业务无 JSON mode 需求,如需启用应单独设计入口而非保留死字段。
)

/**
 * OpenAI 消息体。
 *
 * - role=assistant 且 LLM 决策调用工具时,[toolCalls] 非空,[content] 可为空串
 * - role=tool 时(回填工具结果),[toolCallId] 必填,[content] 是工具执行结果文本
 *
 * Phase 8.6: [content] 改为 [JsonElement] 以支持多模态:
 *  - 纯文本: `JsonPrimitive("text")`
 *  - 多模态: `JsonArray([{type:"text", text:"..."}, {type:"image_url", image_url:{url:"data:..."}}])`
 */
@Serializable
internal data class OpenAIMessage(
    val role: String,
    val content: JsonElement,
    /** assistant 消息携带的工具调用请求。 */
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
    /** tool 消息回填时对应的 tool_call_id。 */
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
)

/** OpenAI 工具定义(type=function)。 */
@Serializable
internal data class OpenAITool(
    val type: String = "function",
    val function: OpenAIToolFunction,
)

@Serializable
internal data class OpenAIToolFunction(
    val name: String,
    val description: String,
    /** 参数 JSON Schema(原始 JSON,由 Provider 用 Json.decodeFromString(JsonElement.serializer()) 解析)。 */
    val parameters: JsonElement,
)

/** assistant 消息中的工具调用请求。 */
@Serializable
internal data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIToolCallFunction,
)

@Serializable
internal data class OpenAIToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OpenAIStreamChunk(
    val choices: List<OpenAIChoice> = emptyList(),
)

@Serializable
internal data class OpenAIChoice(
    val index: Int = 0,
    val delta: OpenAIDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/**
 * 流式增量。
 *
 * Phase 7: [toolCalls] 是流式工具调用增量数组,每个元素有 index/id/function{name/arguments}。
 * arguments 是分片字符串,需按 index 累积拼接。
 */
@Serializable
internal data class OpenAIDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIDeltaToolCall>? = null,
)

/** 流式 tool_call 增量片段。 */
@Serializable
internal data class OpenAIDeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAIDeltaToolCallFunction? = null,
)

@Serializable
internal data class OpenAIDeltaToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

/** 非流式响应(stream=false 时返回)。 */
@Serializable
internal data class OpenAICompletionResponse(
    val choices: List<OpenAICompletionChoice> = emptyList(),
)

@Serializable
internal data class OpenAICompletionChoice(
    val index: Int = 0,
    val message: OpenAICompletionMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAICompletionMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
)

/** 非流式错误响应(仅在 SSE 流里夹带 HTTP 错误体时用到)。 */
@Serializable
internal data class OpenAIErrorBody(
    val error: OpenAIErrorDetail? = null,
)

@Serializable
internal data class OpenAIErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)

/** GET /models 响应。data[].id 为模型 id。 */
@Serializable
internal data class OpenAIModelsResponse(
    val data: List<OpenAIModelInfo> = emptyList(),
)

/**
 * 上游 /models 单条模型信息。
 *
 * v1.132 (H-OAI3): 扩展字段以支持 OpenRouter / Together / DeepInfra 等
 * 返回丰富元数据的 OpenAI 兼容服务。所有扩展字段均带默认值,标准 OpenAI
 * /models 接口未返回时回退到默认值(向后兼容)。
 *
 * - [id]: 模型 id(必有)
 * - [context_length]: OpenRouter/Together 等返回的上下文窗口(token 数)
 * - [top_provider]: OpenRouter 的 provider 信息(含 max_completion_tokens)
 * - [pricing]: OpenRouter 的定价信息(prompt/completion per token)
 * - [capabilities]: P2-3 新增,服务端声明的模型能力列表(如 ["vision","tools"])。
 *   Ollama 自 0.4.0 起在 /api/show 返回 capabilities,部分聚合服务也会在
 *   /models 响应中携带;有值时优先于本地推断(见 [io.zer0.ai.ollama.OllamaVisionInferrer])。
 *   标准服务未返回时为 null,不影响向后兼容。
 */
@Serializable
internal data class OpenAIModelInfo(
    val id: String = "",
    val context_length: Int? = null,
    val max_completion_tokens: Int? = null,
    val top_provider: OpenAIModelTopProvider? = null,
    val pricing: OpenAIModelPricing? = null,
    val capabilities: List<String>? = null,
)

@Serializable
internal data class OpenAIModelTopProvider(
    val max_completion_tokens: Int? = null,
)

@Serializable
internal data class OpenAIModelPricing(
    /** prompt 价格(per token,字符串形式避免精度丢失)。 */
    val prompt: String? = null,
    /** completion 价格(per token)。 */
    val completion: String? = null,
)
