package io.zer0.muse.tools

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ToolCallRecord
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/** 工具调用超时阈值(2 分钟),超时则终止,避免阻塞流式输出。 */
internal const val TOOL_TIMEOUT_MS = 120_000L

/** 单个工具结果送入 LLM 上下文的最大字符数,防止超长结果撑爆上下文。 */
internal const val MAX_TOOL_RESULT_CHARS = 8000

/** 工具调用循环内 conversationHistory 的工具链部分最大消息条数。 */
internal const val MAX_TOOL_CHAIN_MESSAGES = 30

/** 连续工具失败早停阈值,避免跑满 maxToolRounds 白耗 API 额度。 */
internal const val MAX_CONSECUTIVE_TOOL_FAILURES = 3

/**
 * 单轮 LLM 流式请求的输入参数。
 *
 * @param round 当前轮次(从 1 开始)
 * @param history 本轮要发送的对话历史(已含 system prefix 和工具结果)
 * @param currentAssistantId 当前占位 assistant 消息的 id
 * @param builder assistant 正文累积器
 * @param reasoningBuilder assistant reasoning/think 累积器
 */
data class StreamRoundParams(
    val round: Int,
    val history: List<UIMessage>,
    val currentAssistantId: Uuid,
    val builder: StringBuilder,
    val reasoningBuilder: StringBuilder,
    // v1.0.1 (P4): 每轮工具调用的流式重试计数,递归调用 streamRound 时递增。
    //   原计数器声明在 launchStream 外层,跨所有 tool round 共享,导致第 1 轮耗尽后后续轮次无法重试。
    //   移入 params 后每轮独立,且递归重试通过 copy(retryCount = ...) 传递。
    val retryCount: Int = 0,
)

/**
 * 单轮 LLM 流式请求的结果。
 */
sealed class StreamRoundResult {
    /**
     * 流式成功结束。
     *
     * @param assistantMessage 最终 finalized 的 assistant 消息(含 toolCalls,如果有)
     * @param hasToolCalls 本轮是否产生了工具调用
     * @param contentLength 本轮 assistant 正文字符数(用于性能统计)
     * @param firstTokenTime 首 token 到达时的系统时间,未到达则为 0
     */
    data class Success(
        val assistantMessage: UIMessage,
        val hasToolCalls: Boolean,
        val contentLength: Int,
        val firstTokenTime: Long,
    ) : StreamRoundResult()

    /**
     * 流式失败(网络/限流/API 错误等,且已耗尽自动重试)。
     *
     * @param type 错误类型
     * @param message 用户友好错误消息
     * @param partialContent 已接收的部分正文
     * @param partialReasoning 已接收的部分 reasoning
     */
    data class Error(
        val type: ChatErrorType,
        val message: String,
        val partialContent: String? = null,
        val partialReasoning: String? = null,
    ) : StreamRoundResult()
}

/**
 * 工具调用循环的宿主回调。
 *
 * 由 [ChatViewModel] 实现,负责真正的流式请求、UI 更新、工具审批等。
 * [ToolOrchestrator] 只编排轮次,不直接操作 UI。
 */
interface ToolLoopHost {
    /**
     * 执行一轮 LLM 流式请求并返回结果。
     *
     * 实现方需要:
     *  - 调用 chatService.streamChat
     *  - 收集 ContentDelta / ReasoningDelta / ToolCallDelta / ImageDelta
     *  - 实时更新 UI(节流)
     *  - 处理 NETWORK/RATE_LIMIT 自动重试
     *  - finalize assistant 消息(mood/reflection/think 提取)
     *  - 返回含/不含 toolCalls 的 assistant 消息
     */
    suspend fun streamRound(params: StreamRoundParams): StreamRoundResult

    /**
     * 请求用户审批工具调用,挂起直到用户做出决定。
     */
    suspend fun requestToolApproval(
        toolName: String,
        toolCallId: String,
        argsPreview: String,
    ): ToolApprovalState

    /**
     * 工具调用循环内部发生非致命错误时回调(如 DB 落盘失败)。
     */
    fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean = true)
}

/**
 * 工具调用循环的参数。
 *
 * @param sessionId 当前会话 id
 * @param initialAssistantId 初始占位 assistant 消息 id
 * @param baseHistorySize 不可截断的初始上下文大小(system prefix + 历史)
 * @param maxRounds 最大工具调用轮次
 * @param tools 本轮暴露给 LLM 的工具定义列表
 * @param skillMap 已启用 skill 的 id → SkillEntity 映射
 * @param model 实际使用的模型
 * @param providerConfig 实际使用的 Provider 配置
 * @param temperature 温度
 * @param maxTokens 最大 token 数
 * @param reasoningLevel 推理等级
 * @param webSearchEnabled 是否启用联网搜索(用于从 web_search 工具结果提取 citation URL)
 * @param experiments 实验配置(仅用于 debug 日志)
 * @param assistant 当前 Assistant 配置(可选,用于 delegate_agent 等)
 */
data class ToolLoopParams(
    val sessionId: String,
    val initialAssistantId: Uuid,
    val baseHistorySize: Int,
    val maxRounds: Int,
    val tools: List<ToolDefinition>,
    val skillMap: Map<String, SkillEntity>,
    val model: Model?,
    val providerConfig: ProviderConfig?,
    val temperature: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val webSearchEnabled: Boolean = false,
    val experiments: ExperimentsConfig = ExperimentsConfig(),
    val assistant: AssistantEntity? = null,
)

/**
 * 工具调用循环的错误信息。
 */
data class ToolLoopError(
    val type: ChatErrorType,
    val message: String,
    val partialContent: String? = null,
    val partialReasoning: String? = null,
)

/**
 * 工具调用循环的结果。
 */
data class ToolLoopResult(
    val finalAssistantId: Uuid,
    val round: Int,
    val totalToolCallCount: Int,
    val totalCharCount: Int,
    val firstTokenTime: Long,
    val citationUrls: List<String>,
    val success: Boolean,
    val error: ToolLoopError? = null,
    /** 工具调用循环正常结束时(无 tool_calls)的最终 assistant 消息;达到轮次上限等异常退出时为 null。 */
    val finalAssistantMessage: UIMessage? = null,
)

/**
 * Phase 2: 工具调用循环编排器。
 *
 * 把 [ChatViewModel] 中厚重的 `while (hasToolCalls && round < maxToolRounds)` 循环抽离出来,
 * 职责:
 *  - 控制多轮工具调用流程(截断、轮次上限、连续失败早停)
 *  - 并行执行多个工具调用并回填结果
 *  - 维护任务卡(TaskCard)状态
 *  - 从 web_search 工具结果中提取 citation URL,供最终 assistant 消息引用
 *
 * 真正的流式请求、UI 更新、工具审批通过 [ToolLoopHost] 回调交给 ChatViewModel。
 */
class ToolOrchestrator(
    private val toolRegistry: ToolRegistry,
    private val skillRepository: SkillRepository,
    private val skillExecutor: SkillExecutor,
    private val assistantRepository: AssistantRepository,
    private val sessionRepository: SessionRepository,
    private val accessor: ChatStateAccessor,
    private val taskCardCoordinator: ChatTaskCardCoordinator,
) {

    private data class ToolExecResult(
        val idx: Int,
        val tc: ToolCall,
        val finalToolResult: String,
        val isSuccess: Boolean,
    )

    /**
     * 运行工具调用循环,直到 LLM 不再调用工具、达到轮次上限或连续失败早停。
     *
     * @param params 循环参数
     * @param conversationHistory 可变对话历史(会在此方法内被追加 tool_calls/tool 消息)
     * @param host 宿主回调
     */
    suspend fun runLoop(
        params: ToolLoopParams,
        conversationHistory: MutableList<UIMessage>,
        host: ToolLoopHost,
    ): ToolLoopResult {
        var round = 0
        var consecutiveToolFailures = 0
        var currentAssistantId = params.initialAssistantId
        var totalToolCallCount = 0
        var totalCharCount = 0
        var firstTokenTime = 0L
        val citationUrls = mutableListOf<String>()
        var hasToolCalls = true
        var finalAssistantMessage: UIMessage? = null

        while (hasToolCalls && round < params.maxRounds) {
            round++

            // C1-2: 工具链过长时截断,保留初始上下文 + 最近工具链
            val toolChainSize = conversationHistory.size - params.baseHistorySize
            if (toolChainSize > MAX_TOOL_CHAIN_MESSAGES) {
                val keepHead = conversationHistory.subList(0, params.baseHistorySize).toList()
                val keepTail = conversationHistory.subList(
                    conversationHistory.size - MAX_TOOL_CHAIN_MESSAGES,
                    conversationHistory.size,
                ).toList()
                val truncatedList = keepHead + listOf(
                    UIMessage(
                        role = MessageRole.SYSTEM,
                        content = "(较早的工具调用历史已省略,仅保留最近 $MAX_TOOL_CHAIN_MESSAGES 条)",
                    ),
                ) + keepTail
                conversationHistory.clear()
                conversationHistory.addAll(truncatedList)
                if (params.experiments.debugMode) {
                    Logger.d(
                        "ToolOrchestrator",
                        "tool-chain truncated | round=$round | size ${params.baseHistorySize + toolChainSize} → ${conversationHistory.size}",
                    )
                }
            }

            // 每轮重置流式累积器
            val builder = StringBuilder()
            val reasoningBuilder = StringBuilder()

            val outcome = host.streamRound(
                StreamRoundParams(
                    round = round,
                    history = conversationHistory.toList(),
                    currentAssistantId = currentAssistantId,
                    builder = builder,
                    reasoningBuilder = reasoningBuilder,
                )
            )

            when (outcome) {
                is StreamRoundResult.Error -> {
                    return ToolLoopResult(
                        finalAssistantId = currentAssistantId,
                        round = round,
                        totalToolCallCount = totalToolCallCount,
                        totalCharCount = totalCharCount,
                        firstTokenTime = firstTokenTime,
                        citationUrls = citationUrls.toList(),
                        success = false,
                        error = ToolLoopError(
                            type = outcome.type,
                            message = outcome.message,
                            partialContent = outcome.partialContent,
                            partialReasoning = outcome.partialReasoning,
                        ),
                    )
                }

                is StreamRoundResult.Success -> {
                    totalCharCount += outcome.contentLength
                    if (outcome.firstTokenTime > 0L && firstTokenTime == 0L) {
                        firstTokenTime = outcome.firstTokenTime
                    }

                    if (!outcome.hasToolCalls) {
                        hasToolCalls = false
                        finalAssistantMessage = outcome.assistantMessage
                        break
                    }

                    val assistantToolMsg = outcome.assistantMessage
                    val toolCallList = assistantToolMsg.toolCalls ?: emptyList()
                    totalToolCallCount += toolCallList.size

                    // 把带 tool_calls 的 assistant 消息加入历史并持久化
                    conversationHistory.add(assistantToolMsg)
                    persistAssistantToolMsg(params.sessionId, assistantToolMsg, host)

                    // 断点续传:持久化未完成的工具调用
                    savePendingToolCalls(params.sessionId, toolCallList, host)

                    // 构建任务卡并切换到 EXECUTING
                    val taskCardId = currentAssistantId.toString()
                    val taskCard = TaskCardData.fromToolCalls(
                        currentAssistantId,
                        toolCallList.map { it.name to it.arguments },
                    )
                    accessor.update {
                        it.copy(taskCards = it.taskCards + (taskCardId to taskCard))
                    }
                    taskCardCoordinator.updateTaskCardPhase(taskCardId, TaskCardPhase.EXECUTING)

                    // 并行/串行执行工具调用
                    val executeToolCall: suspend (Int, ToolCall) -> ToolExecResult = { idx, tc ->
                        executeSingleToolCall(params, taskCardId, tc, idx, host)
                    }

                    val execResults: List<ToolExecResult> = if (toolCallList.size <= 1) {
                        toolCallList.mapIndexed { idx, tc -> executeToolCall(idx, tc) }
                    } else {
                        coroutineScope {
                            toolCallList.mapIndexed { idx, tc ->
                                async { executeToolCall(idx, tc) }
                            }.awaitAll()
                        }.sortedBy { it.idx }
                    }

                    // 按顺序回填结果到历史和 UI
                    for (result in execResults) {
                        val (idx, tc, finalToolResult, isSuccess) = result
                        if (isSuccess) {
                            consecutiveToolFailures = 0
                        } else {
                            consecutiveToolFailures++
                        }

                        val toolMsg = UIMessage(
                            role = MessageRole.TOOL,
                            content = finalToolResult,
                            toolCallId = tc.id,
                        )
                        conversationHistory.add(toolMsg)

                        val toolDisplay = UIMessage(
                            role = MessageRole.ASSISTANT,
                            content = "调用工具 `${tc.name}`:\n参数: ${tc.arguments}\n结果: $finalToolResult",
                            toolCallInfo = ToolCallInfo(
                                toolName = tc.name,
                                arguments = tc.arguments,
                                result = finalToolResult,
                                isSuccess = isSuccess,
                            ),
                        )
                        val record = ToolCallRecord(
                            toolName = tc.name,
                            arguments = tc.arguments,
                            result = finalToolResult,
                            isSuccess = isSuccess,
                            timestamp = System.currentTimeMillis(),
                        )

                        val snapshot = accessor.snapshot
                        val isCurrentDisplayedSession = if (snapshot.isAgentMode) {
                            snapshot.agentSessionId == params.sessionId
                        } else {
                            snapshot.currentSessionId == params.sessionId
                        }
                        if (isCurrentDisplayedSession) {
                            accessor.update {
                                it.copy(
                                    messages = it.messages + toolDisplay,
                                    toolCallHistory = it.toolCallHistory + record,
                                )
                            }
                        } else {
                            Logger.d(
                                "ToolOrchestrator",
                                "toolDisplay skipped (detached): sessionId=${params.sessionId}, " +
                                    "current=${snapshot.currentSessionId}, agent=${snapshot.agentSessionId}, " +
                                    "isAgent=${snapshot.isAgentMode}",
                            )
                        }

                        // 从 web_search 结果中提取 citation URL
                        if (tc.name == "web_search") {
                            citationUrls.addAll(extractWebSearchUrls(finalToolResult))
                        }

                        // C1-3: 连续失败早停
                        if (consecutiveToolFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                            Logger.w(
                                "ToolOrchestrator",
                                "连续 $consecutiveToolFailures 次工具失败,提前终止工具调用循环 " +
                                    "(round=$round, tool=${tc.name})",
                            )
                            hasToolCalls = false
                            break
                        }
                    }

                    taskCardCoordinator.updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)

                    // 同步 Agent 工作流计划到 UI
                    // v1.137: 为计划关联当前助手消息 ID,使计划卡固定在创建它的消息上随消息滚动,
                    // 而不是始终"跳"到最后一条助手消息(用户反馈"列表固定在底部不跟随滚动")。
                    val latestPlans = skillExecutor.getActivePlans().mapValues { (_, plan) ->
                        if (plan.messageId == null) plan.copy(messageId = currentAssistantId.toString()) else plan
                    }
                    if (latestPlans.isNotEmpty()) {
                        accessor.update { it.copy(agentPlans = latestPlans) }
                    }

                    // 创建新的占位 assistant 消息接收下一轮流式回复
                    if (hasToolCalls && round < params.maxRounds) {
                        val nextAssistant = UIMessage(role = MessageRole.ASSISTANT, content = "")
                        val snapshot = accessor.snapshot
                        val isCurrentDisplayedSession2 = if (snapshot.isAgentMode) {
                            snapshot.agentSessionId == params.sessionId
                        } else {
                            snapshot.currentSessionId == params.sessionId
                        }
                        if (isCurrentDisplayedSession2) {
                            accessor.update {
                                it.copy(messages = it.messages + nextAssistant)
                            }
                        }
                        currentAssistantId = nextAssistant.id
                    }
                }
            }
        }

        return ToolLoopResult(
            finalAssistantId = currentAssistantId,
            round = round,
            totalToolCallCount = totalToolCallCount,
            totalCharCount = totalCharCount,
            firstTokenTime = firstTokenTime,
            citationUrls = citationUrls.toList(),
            success = true,
            finalAssistantMessage = finalAssistantMessage,
        )
    }

    private suspend fun executeSingleToolCall(
        params: ToolLoopParams,
        taskCardId: String,
        tc: ToolCall,
        idx: Int,
        host: ToolLoopHost,
    ): ToolExecResult {
        // 工具审批检查
        val approvalState = host.requestToolApproval(tc.name, tc.id, tc.arguments.take(200))
        if (approvalState is ToolApprovalState.Denied) {
            val deniedResult = """{"error": "Tool denied by user", "reason": "${approvalState.reason}"}"""
            taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                s.copy(
                    status = TaskStepStatus.FAILED,
                    result = deniedResult,
                    finishedAt = System.currentTimeMillis(),
                )
            }
            try {
                PendingToolCallStore.remove(tc.id)
            } catch (e: Exception) {
                Logger.w("ToolOrchestrator", "PendingToolCallStore.remove(denied) 失败: ${e.message}", e)
            }
            return ToolExecResult(idx, tc, deniedResult, false)
        }

        val stepStartedAt = System.currentTimeMillis()

        // delegate_agent 步骤启动前解析助手名,更新步骤标题/进度文本
        val delegateAgentInfo = if (tc.name == "delegate_agent") {
            resultOf {
                TaskCardData.parseDelegateAgentArgs(tc.arguments)
            }.getOrNull()
        } else null
        val assistantName = delegateAgentInfo?.assistantId?.takeIf { it.isNotBlank() }?.let { id ->
            resultOf { assistantRepository.getById(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
        }
        val stepTitle = if (assistantName != null) "委托给 $assistantName" else tc.name
        val stepProgress = if (assistantName != null) "正在委托给 $assistantName..." else null
        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
            s.copy(
                title = stepTitle,
                status = TaskStepStatus.RUNNING,
                startedAt = stepStartedAt,
                progressText = stepProgress,
            )
        }

        // 执行工具:skill 走 SkillExecutor,本地工具走 ToolRegistry
        val toolResult = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
            val skill = params.skillMap[tc.name]
            if (skill != null) {
                skillExecutor.execute(
                    skill = skill,
                    argumentsJson = tc.arguments,
                    onProgress = { msg ->
                        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
                            s.copy(progressText = msg)
                        }
                    },
                )
            } else {
                withContext(Dispatchers.IO) {
                    toolRegistry.executeFromJson(tc.name, tc.arguments)
                }
            }
        } ?: "[超时] 工具 ${tc.name} ${TOOL_TIMEOUT_MS / 1000} 秒未响应,已终止"

        val isSuccess = taskCardCoordinator.isToolResultSuccess(toolResult)
        val rawFinal = if (isSuccess) {
            toolResult
        } else {
            "$toolResult\n\n[提示] 此工具调用失败。请判断:1) 是否需要重试(最多 1 次);2) 换用其他工具或方案;3) 若无法解决,直接告知用户具体失败原因,不要硬撑。"
        }
        val finalToolResult = if (rawFinal.length > MAX_TOOL_RESULT_CHARS) {
            rawFinal.take(MAX_TOOL_RESULT_CHARS) +
                "\n\n…(结果已截断,如需完整结果请使用分页或限定范围的工具)"
        } else {
            rawFinal
        }

        taskCardCoordinator.updateTaskCardStep(taskCardId, idx) { s ->
            s.copy(
                status = if (isSuccess) TaskStepStatus.SUCCESS else TaskStepStatus.FAILED,
                result = finalToolResult,
                finishedAt = System.currentTimeMillis(),
            )
        }

        try {
            PendingToolCallStore.remove(tc.id)
        } catch (e: Exception) {
            Logger.w("ToolOrchestrator", "PendingToolCallStore.remove 失败: ${e.message}", e)
        }

        return ToolExecResult(idx, tc, finalToolResult, isSuccess)
    }

    private suspend fun persistAssistantToolMsg(
        sessionId: String,
        msg: UIMessage,
        host: ToolLoopHost,
    ) {
        try {
            sessionRepository.upsertMessage(sessionId, msg)
        } catch (e: Exception) {
            Logger.e("ToolOrchestrator", "upsertMessage(toolCalls) failed", e)
            host.onToolLoopError(
                ChatErrorType.TOOL_ERROR,
                "工具调用记录保存失败: ${e.message ?: "未知错误"}",
            )
        }
    }

    private suspend fun savePendingToolCalls(
        sessionId: String,
        toolCalls: List<ToolCall>,
        host: ToolLoopHost,
    ) {
        if (toolCalls.isEmpty()) return
        val now = System.currentTimeMillis()
        val pendings = toolCalls.map { tc ->
            PendingToolCallStore.PendingToolCall(
                chatId = sessionId,
                toolCallId = tc.id,
                toolName = tc.name,
                arguments = tc.arguments,
                createdAt = now,
            )
        }
        try {
            PendingToolCallStore.saveAll(pendings)
        } catch (e: Exception) {
            Logger.w("ToolOrchestrator", "PendingToolCallStore.saveAll 失败: ${e.message}", e)
        }
    }

    private fun extractWebSearchUrls(result: String): List<String> {
        val regex = Regex("""^\s*URL:\s*(.+)$""", RegexOption.MULTILINE)
        return regex.findAll(result).map { it.groupValues[1].trim() }.toList()
    }
}
