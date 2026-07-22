package io.zer0.muse.tools

import io.zer0.muse.ui.taskcard.DelegationNodeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * v1.201: 委派链路追踪器。
 *
 * 维护当前会话内所有委派请求的链路状态,供 UI 实时展示。
 * 一个 requestId 对应一个链路节点(可能包含 subResults 形成树形结构)。
 */
class DelegationChainTracker {

    /** 单个委派节点(对应一次 delegate_agent 调用)。 */
    data class ChainNode(
        val requestId: String,
        val parentRequestId: String?,
        val task: String,
        val targetType: String,         // "assistant" | "team"
        val targetId: String,
        val targetName: String,
        val status: DelegationNodeStatus,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val errorMessage: String? = null,
        val resultPreview: String? = null,  // 结果预览(前 200 字)
        val subNodes: List<ChainNode> = emptyList(),
    )

    private val _chains = MutableStateFlow<Map<String, ChainNode>>(emptyMap())
    val chains: StateFlow<Map<String, ChainNode>> = _chains.asStateFlow()

    /** 记录委派开始。 */
    fun onDelegationStarted(
        requestId: String,
        parentRequestId: String?,
        task: String,
        targetType: String,
        targetId: String,
        targetName: String,
    ) {
        val node = ChainNode(
            requestId = requestId,
            parentRequestId = parentRequestId,
            task = task,
            targetType = targetType,
            targetId = targetId,
            targetName = targetName,
            status = DelegationNodeStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        _chains.update { it + (requestId to node) }
    }

    /** 记录委派完成。 */
    fun onDelegationFinished(
        requestId: String,
        success: Boolean,
        resultText: String,
        error: String? = null,
        subResults: List<DelegationContract.DelegationResult> = emptyList(),
    ) {
        _chains.update { current ->
            val node = current[requestId] ?: return@update current
            val updated = node.copy(
                status = if (success) DelegationNodeStatus.COMPLETED
                         else DelegationNodeStatus.FAILED,
                finishedAt = System.currentTimeMillis(),
                errorMessage = error,
                resultPreview = resultText.take(200),
            )
            current + (requestId to updated)
        }
    }

    /** 清空链路(切换会话时调用)。 */
    fun clear() {
        _chains.value = emptyMap()
    }

    /** 获取顶级链路节点(parentRequestId == null)。 */
    fun getRoots(): List<ChainNode> = _chains.value.values
        .filter { it.parentRequestId == null }
        .sortedBy { it.startedAt }
}
