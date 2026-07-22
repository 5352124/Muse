package io.zer0.muse.tools

import io.zer0.ai.core.UIMessage
import kotlinx.serialization.Serializable

/**
 * v1.200: 结构化委派上下文与返回契约。
 *
 * 统一多 Agent 协作中"主助手 → 子助手/团队"的输入输出格式,
 * 使任务委派具备可追溯、可聚合、可隔离的能力。
 */
object DelegationContract {

    /**
     * 委派请求。
     *
     * @param requestId 请求唯一 id,由调用方生成,用于链路追踪
     * @param task 任务描述(自然语言)
     * @param targetType 目标类型:assistant / team
     * @param targetId 目标 assistantId 或 teamId
     * @param parentSessionId 父会话 id,用于上下文隔离与结果回填
     * @param contextMessages 携带的精简上下文消息列表(非完整聊天记录)
     * @param attachments 附件/产物 id 列表,如 artifactIds / 文件路径
     * @param timeoutSec 执行超时(秒)
     * @param responseFormat 期望返回格式:text / json / markdown / code
     * @param capabilityHints 期望子助手具备的能力标签(用于校验或自动路由)
     * @param requireApproval 是否要求用户显式审批(高风险任务)
     * @param metadata 扩展元数据
     */
    @Serializable
    data class DelegationRequest(
        val requestId: String,
        val task: String,
        val targetType: TargetType,
        val targetId: String,
        val parentSessionId: String? = null,
        val contextMessages: List<UIMessage> = emptyList(),
        val attachments: List<String> = emptyList(),
        val timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
        val responseFormat: ResponseFormat = ResponseFormat.TEXT,
        val capabilityHints: List<String> = emptyList(),
        val requireApproval: Boolean = false,
        /** v1.201: 显式声明的暂停点列表,可选值: "before_start" / "after_each_member" / "on_intermediate"。
         *  为空时由 PausePolicy 决定是否暂停;非空时优先级高于 PausePolicy。 */
        val pausePoints: List<String> = emptyList(),
        val metadata: RequestMetadata = RequestMetadata(),
    ) {
        enum class TargetType { ASSISTANT, TEAM }
        enum class ResponseFormat { TEXT, JSON, MARKDOWN, CODE }

        @Serializable
        data class RequestMetadata(
            val sourceToolCallId: String? = null,
            val createdAt: Long = System.currentTimeMillis(),
            val correlationId: String? = null,
        )

        companion object {
            const val DEFAULT_TIMEOUT_SEC = 60
        }
    }

    /**
     * 委派结果。
     *
     * @param requestId 对应请求 id
     * @param success 是否成功
     * @param resultText 结果文本(成功时的主要输出)
     * @param error 错误信息(success=false 时非空)
     * @param metadata 执行元数据(耗时/token/模型等)
     * @param subResults 团队/工作流执行时,各成员子结果
     * @param artifacts 本次委派产生或引用的产物 id
     */
    @Serializable
    data class DelegationResult(
        val requestId: String,
        val success: Boolean,
        val resultText: String = "",
        val error: String? = null,
        val metadata: ResultMetadata = ResultMetadata(),
        val subResults: List<DelegationResult> = emptyList(),
        val artifacts: List<String> = emptyList(),
    ) {
        @Serializable
        data class ResultMetadata(
            val startedAt: Long = 0L,
            val finishedAt: Long = 0L,
            val durationMs: Long = 0L,
            val tokenCount: Int? = null,
            val modelId: String? = null,
            val assistantId: String? = null,
            val assistantName: String? = null,
        )

        /** 是否存在可阅读的文本结果。 */
        val hasResultText: Boolean get() = resultText.isNotBlank()

        /** 汇总自身与所有子结果的错误信息。 */
        fun collectErrors(): List<String> = buildList {
            if (!success && !error.isNullOrBlank()) add(error)
            subResults.forEach { addAll(it.collectErrors()) }
        }

        /** 汇总所有子结果的文本(用于团队结果聚合)。 */
        fun collectResultTexts(): List<String> = buildList {
            if (hasResultText) add(resultText)
            subResults.forEach { addAll(it.collectResultTexts()) }
        }
    }

    /**
     * 团队工作流节点。
     *
     * 用于描述团队内多 Agent 的执行顺序与依赖关系,
     * 是后续串行/并行/条件分支编排的基础结构。
     */
    @Serializable
    data class TeamWorkflowNode(
        val id: String,
        val assistantId: String,
        val name: String = "",
        val taskTemplate: String = "",
        val dependsOn: List<String> = emptyList(),
        val mode: Mode = Mode.SEQUENTIAL,
    ) {
        enum class Mode { SEQUENTIAL, PARALLEL, CONDITIONAL }
    }

    /**
     * 团队工作流定义。
     */
    @Serializable
    data class TeamWorkflow(
        val nodes: List<TeamWorkflowNode> = emptyList(),
        val aggregationStrategy: AggregationStrategy = AggregationStrategy.MERGE,
    ) {
        enum class AggregationStrategy { MERGE, VOTE, EXPERT_REVIEW, FIRST_SUCCESS, LLM_REVIEW }
    }

    /** 把 [DelegationResult] 序列化为 JSON 字符串。 */
    fun resultToJson(result: DelegationResult): String {
        return io.zer0.common.AppJson.encodeToString(DelegationResult.serializer(), result)
    }

    /** 从 JSON 字符串解析 [DelegationResult]。 */
    fun resultFromJson(json: String): DelegationResult? {
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(DelegationResult.serializer(), json)
        }.getOrNull()
    }

    /** 把 [DelegationRequest] 序列化为 JSON 字符串。 */
    fun requestToJson(request: DelegationRequest): String {
        return io.zer0.common.AppJson.encodeToString(DelegationRequest.serializer(), request)
    }

    /** 从 JSON 字符串解析 [DelegationRequest]。 */
    fun requestFromJson(json: String): DelegationRequest? {
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(DelegationRequest.serializer(), json)
        }.getOrNull()
    }
}
