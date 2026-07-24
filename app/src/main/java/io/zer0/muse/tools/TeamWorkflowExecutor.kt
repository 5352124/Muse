package io.zer0.muse.tools

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * v1.200: 团队工作流执行器。
 *
 * 支持三种编排模式:
 * - 串行(SEQUENTIAL):按节点顺序依次执行,前序结果作为后序上下文
 * - 并行(PARALLEL):无依赖节点同时执行,最后按聚合策略合并
 * - 条件(CONDITIONAL):根据前序结果判断是否执行(当前实现为简单开关)
 *
 * 若团队未配置工作流,则退化到按 memberIds 顺序串行执行。
 *
 * v1.201: 新增 [llmAggregator] 参数,LLM_REVIEW 聚合策略时由 [LlmAggregator.review]
 * 完成综合评审;llmAggregator 为 null 时 LLM_REVIEW 降级为 EXPERT_REVIEW。
 * v1.201: 新增 [pauseManager] + [pausePolicy] 参数,支持团队工作流执行前/每个成员执行前的
 * 人机协作暂停点;pauseManager 为 null 时跳过所有暂停点。
 */
class TeamWorkflowExecutor(
    /** 执行单个委派请求的回调,通常传入 SkillExecutor::delegateAgent。 */
    private val delegate: suspend (DelegationContract.DelegationRequest) -> DelegationContract.DelegationResult,
    /** v1.201: LLM 综合评审聚合器,LLM_REVIEW 策略时使用;为 null 时降级为 EXPERT_REVIEW。 */
    private val llmAggregator: LlmAggregator? = null,
    /** v1.201: 委派暂停管理器,null 时跳过所有暂停点。 */
    private val pauseManager: DelegationPauseManager? = null,
    /** v1.201: 暂停策略,仅在 pauseManager 非 null 时生效。 */
    private val pausePolicy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
) {

    /**
     * 执行团队工作流。
     *
     * @param workflow 工作流定义
     * @param teamTask 团队总体任务描述
     * @param parentRequestId 父请求 id,用于生成子请求 id
     * @param teamMembers 团队成员 assistantId 列表,作为 workflow.nodes 为空时的退化顺序
     * @param baseContext 共享上下文消息(可选)
     * @return 汇总后的委派结果,subResults 包含每个节点的结果
     */
    suspend fun execute(
        workflow: DelegationContract.TeamWorkflow,
        teamTask: String,
        parentRequestId: String,
        teamMembers: List<String>,
        baseContext: List<UIMessage> = emptyList(),
    ): DelegationContract.DelegationResult {
        val startedAt = System.currentTimeMillis()
        val nodes = workflow.nodes.takeIf { it.isNotEmpty() }
            ?: teamMembers.mapIndexed { idx, assistantId ->
                DelegationContract.TeamWorkflowNode(
                    id = "step-$idx",
                    assistantId = assistantId,
                    name = "团队步骤 $idx",
                    taskTemplate = teamTask,
                )
            }

        if (nodes.isEmpty()) {
            return errorResult(parentRequestId, "团队没有成员,无法执行工作流", startedAt)
        }

        // 校验 assistantId
        val invalidNodes = nodes.filter { it.assistantId.isBlank() }
        if (invalidNodes.isNotEmpty()) {
            return errorResult(parentRequestId, "工作流节点缺少 assistantId: ${invalidNodes.map { it.id }}", startedAt)
        }

        // H-TWE1: 用 ConcurrentHashMap 替代 mutableMapOf,async 并发写入安全
        val executed = java.util.concurrent.ConcurrentHashMap<String, DelegationContract.DelegationResult>()
        val errors = mutableListOf<String>()

        try {
            // v1.201: 团队执行前暂停(pauseBeforeTeam)
            if (pauseManager != null && pausePolicy.pauseBeforeTeam) {
                if (pauseManager.isCancelled(parentRequestId)) {
                    return errorResult(parentRequestId, "团队工作流被用户取消", startedAt)
                }
                val pauseReq = DelegationPauseManager.PauseRequest(
                    requestId = "pause-$parentRequestId-before-team",
                    taskId = parentRequestId,
                    taskTitle = "团队工作流执行前确认",
                    taskDescription = teamTask,
                    targetType = "team",
                    targetName = parentRequestId,
                    reason = "团队工作流执行前确认",
                    options = listOf(
                        DelegationPauseManager.PauseOption.APPROVE,
                        DelegationPauseManager.PauseOption.REJECT,
                        DelegationPauseManager.PauseOption.CANCEL,
                    ),
                )
                val resp = pauseManager.awaitPauseDecision(pauseReq, pausePolicy)
                when (resp.decision) {
                    DelegationPauseManager.PauseDecision.CANCEL,
                    DelegationPauseManager.PauseDecision.REJECT ->
                        return errorResult(parentRequestId, "用户取消团队工作流", startedAt)
                    DelegationPauseManager.PauseDecision.APPROVE,
                    DelegationPauseManager.PauseDecision.MODIFY -> { /* 继续 */ }
                }
            }

            // 按依赖拓扑分层:无依赖的先执行,完成后解锁依赖它的节点
            val pending = nodes.toMutableList()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { node ->
                    node.dependsOn.all { executed.containsKey(it) }
                }
                if (ready.isEmpty()) {
                    return errorResult(
                        parentRequestId,
                        "工作流存在循环依赖或无法解析的依赖: ${pending.map { it.id }}",
                        startedAt,
                    )
                }

                // v1.201: 每个成员执行前暂停(pauseBeforeEachMember)
                if (pauseManager != null && pausePolicy.pauseBeforeEachMember) {
                    if (pauseManager.isCancelled(parentRequestId)) {
                        return errorResult(parentRequestId, "团队工作流被用户取消", startedAt)
                    }
                    ready.forEach { node ->
                        val pauseReq = DelegationPauseManager.PauseRequest(
                            requestId = "pause-$parentRequestId-${node.id}",
                            taskId = "$parentRequestId/${node.id}",
                            taskTitle = node.name.ifBlank { node.id },
                            taskDescription = node.taskTemplate.ifBlank { teamTask },
                            targetType = "assistant",
                            targetName = node.assistantId,
                            reason = "团队成员 ${node.name.ifBlank { node.id }} 执行前确认",
                            options = listOf(
                                DelegationPauseManager.PauseOption.APPROVE,
                                DelegationPauseManager.PauseOption.REJECT,
                                DelegationPauseManager.PauseOption.CANCEL,
                            ),
                        )
                        val resp = pauseManager.awaitPauseDecision(pauseReq, pausePolicy)
                        when (resp.decision) {
                            DelegationPauseManager.PauseDecision.CANCEL ->
                                return errorResult(parentRequestId, "用户取消团队工作流", startedAt)
                            DelegationPauseManager.PauseDecision.REJECT -> {
                                // 跳过该节点,标记为失败
                                executed[node.id] = errorResult(
                                    "$parentRequestId/${node.id}",
                                    "用户拒绝执行该节点",
                                    System.currentTimeMillis(),
                                )
                                pending.remove(node)
                            }
                            DelegationPauseManager.PauseDecision.APPROVE,
                            DelegationPauseManager.PauseDecision.MODIFY -> { /* 继续执行 */ }
                        }
                    }
                    // 如果所有 ready 节点都被拒绝,继续下一轮
                    if (ready.all { executed.containsKey(it.id) }) {
                        pending.removeAll(ready)
                        continue
                    }
                }

                // 同一层节点按 mode 决定串行或并行
                val sequential = ready.first().mode == DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL
                val layerResults: List<DelegationContract.DelegationResult> = if (sequential) {
                    ready.map { node ->
                        executed[node.id] ?: executeNode(node, teamTask, parentRequestId, baseContext, executed).also {
                            executed[node.id] = it
                        }
                    }
                } else {
                    executeParallel(ready, teamTask, parentRequestId, baseContext, executed).also {
                        executed.putAll(it)
                    }.values.toList()
                }

                errors.addAll(layerResults.flatMap { it.collectErrors() })
                pending.removeAll(ready)
            }

            val orderedResults = nodes.mapNotNull { executed[it.id] }
            val resultText = aggregateResults(workflow.aggregationStrategy, orderedResults, teamTask)
            val finishedAt = System.currentTimeMillis()

            return DelegationContract.DelegationResult(
                requestId = parentRequestId,
                success = errors.isEmpty(),
                resultText = resultText,
                error = errors.firstOrNull(),
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    durationMs = finishedAt - startedAt,
                ),
                subResults = orderedResults,
            )
        } catch (e: Exception) {
            return errorResult(parentRequestId, "工作流执行异常: ${e.message}", startedAt)
        }
    }

    private suspend fun executeNode(
        node: DelegationContract.TeamWorkflowNode,
        teamTask: String,
        parentRequestId: String,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): DelegationContract.DelegationResult {
        val task = buildNodeTask(node, teamTask, executed)
        val contextMessages = buildNodeContext(node, baseContext, executed)
        val request = DelegationContract.DelegationRequest(
            requestId = "$parentRequestId/${node.id}",
            task = task,
            targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
            targetId = node.assistantId,
            parentSessionId = parentRequestId,
            contextMessages = contextMessages,
            timeoutSec = 120,
        )
        return delegate(request)
    }

    private suspend fun executeParallel(
        nodes: List<DelegationContract.TeamWorkflowNode>,
        teamTask: String,
        parentRequestId: String,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): Map<String, DelegationContract.DelegationResult> = coroutineScope {
        nodes.associate { node ->
            node.id to async {
                executeNode(node, teamTask, parentRequestId, baseContext, executed)
            }
        }.mapValues { it.value.await() }
    }

    private fun buildNodeTask(
        node: DelegationContract.TeamWorkflowNode,
        teamTask: String,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): String {
        val base = node.taskTemplate.ifBlank { teamTask }
        if (node.dependsOn.isEmpty()) return base
        val dependencySummary = node.dependsOn.joinToString("\n\n") { depId ->
            val dep = executed[depId]
            val header = "## 前置步骤 $depId 结果"
            when {
                dep == null -> "$header: (未执行)"
                dep.success -> "$header:\n${dep.resultText.take(2000)}"
                else -> "$header:\n错误: ${dep.error ?: "未知错误"}"
            }
        }
        return """$base

$dependencySummary""".trimIndent()
    }

    private fun buildNodeContext(
        node: DelegationContract.TeamWorkflowNode,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): List<UIMessage> {
        val messages = baseContext.toMutableList()
        if (node.mode == DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL && node.dependsOn.isNotEmpty()) {
            // 条件节点:把前置成功结果作为 system 提示注入,辅助判断是否执行/如何执行
            val conditionContext = node.dependsOn.mapNotNull { executed[it] }
                .filter { it.success }
                .joinToString("\n") { "[${it.requestId}]: ${it.resultText.take(500)}" }
            if (conditionContext.isNotBlank()) {
                messages.add(
                    UIMessage(
                        role = MessageRole.SYSTEM,
                        content = "以下条件成立时继续执行:\n$conditionContext",
                    ),
                )
            }
        }
        return messages
    }

    private suspend fun aggregateResults(
        strategy: DelegationContract.TeamWorkflow.AggregationStrategy,
        results: List<DelegationContract.DelegationResult>,
        teamTask: String,
    ): String {
        val successful = results.filter { it.success && it.resultText.isNotBlank() }
        if (successful.isEmpty()) return "(无可用结果)"

        return when (strategy) {
            DelegationContract.TeamWorkflow.AggregationStrategy.MERGE -> {
                buildString {
                    appendLine("团队执行结果汇总:")
                    successful.forEachIndexed { idx, r ->
                        appendLine()
                        appendLine("--- 子结果 ${idx + 1} [${r.metadata.assistantName ?: r.metadata.assistantId ?: r.requestId}] ---")
                        appendLine(r.resultText)
                    }
                }.trimEnd()
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.VOTE -> {
                // 简单投票:结果去重后统计频次,返回最高频结果
                val votes = successful.groupingBy { it.resultText }.eachCount()
                val best = votes.maxByOrNull { it.value }
                "投票结果(共 ${successful.size} 票): 最高频结果出现 ${best?.value} 次\n\n${best?.key ?: ""}"
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.EXPERT_REVIEW -> {
                // 专家评审:取结果最长的作为"详尽版"返回(后续可接入 LLM 综合评审)
                val expert = successful.maxByOrNull { it.resultText.length }
                "专家评审选定结果:\n\n${expert?.resultText ?: ""}"
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.FIRST_SUCCESS -> {
                successful.first().resultText
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.LLM_REVIEW -> {
                // v1.201: LLM 综合评审 — 把 DelegationResult 转换为 Candidate,
                // 调用 AgentResultAggregator.aggregate 并传入 llmAggregator?.review 作为 llmReviewer。
                // llmAggregator 为 null 或 LLM 调用失败时,AgentResultAggregator 内部降级为 EXPERT_REVIEW。
                val candidates = successful.map { r ->
                    AgentResultAggregator.Candidate(
                        source = r.metadata.assistantName
                            ?: r.metadata.assistantId
                            ?: r.requestId,
                        content = r.resultText,
                        confidence = null,
                    )
                }
                val aggregation = AgentResultAggregator.aggregate(
                    candidates = candidates,
                    strategy = AgentResultAggregator.Strategy.LLM_REVIEW,
                    question = teamTask,
                    llmReviewer = llmAggregator?.let { agg -> { c, q -> agg.review(c, q) } },
                )
                aggregation.output
            }
        }
    }

    private fun errorResult(
        requestId: String,
        error: String,
        startedAt: Long,
    ): DelegationContract.DelegationResult {
        val finishedAt = System.currentTimeMillis()
        return DelegationContract.DelegationResult(
            requestId = requestId,
            success = false,
            error = error,
            metadata = DelegationContract.DelegationResult.ResultMetadata(
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt - startedAt,
            ),
        )
    }
}
