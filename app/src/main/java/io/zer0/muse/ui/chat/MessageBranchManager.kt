package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.chat.MessageNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * Message branch manager (RikkaHub message branching ViewModel integration port).
 *
 * Manages message branching state for the ChatViewModel.
 * Each assistant response position can have multiple branches (from regeneration).
 *
 * Usage:
 * 1. Call [onMessageSent] when user sends a message → creates branch tracking
 * 2. Call [onAssistantResponse] when assistant responds → adds to current branch
 * 3. Call [regenerate] to create a new branch at the last position
 * 4. Call [selectBranch] to switch between branches
 * 5. [displayMessages] flow provides the current view (selected branches only)
 */
class MessageBranchManager {

    private val _nodes = MutableStateFlow<List<MessageNode>>(emptyList())
    val nodes: StateFlow<List<MessageNode>> = _nodes.asStateFlow()

    private val _displayMessages = MutableStateFlow<List<UIMessage>>(emptyList())
    val displayMessages: StateFlow<List<UIMessage>> = _displayMessages.asStateFlow()

    /** Current branch state for each node position. */
    private val branchStates = mutableMapOf<String, Int>() // nodeId → selectedIndex

    /** Update from external message list (e.g., from DB load). */
    fun syncFromMessages(messages: List<UIMessage>) {
        val nodes = messages.map { msg ->
            MessageNode(
                id = msg.id.toString(),
                messages = listOf(msg),
                selectIndex = branchStates[msg.id.toString()] ?: 0,
            )
        }
        _nodes.value = nodes
        refreshDisplay()
    }

    /** Called when a new message is sent by the user. */
    fun onMessageSent(message: UIMessage) {
        val node = MessageNode.from(message)
        _nodes.update { it + node }
        branchStates[node.id] = 0
        refreshDisplay()
    }

    /** Called when an assistant response arrives. */
    fun onAssistantResponse(message: UIMessage, isNewBranch: Boolean = false) {
        if (isNewBranch) {
            // Add as new branch to the last assistant node
            _nodes.update { nodes ->
                if (nodes.isEmpty()) return@update nodes
                val lastNode = nodes.last()
                if (lastNode.messages.firstOrNull()?.role == MessageRole.ASSISTANT) {
                    val updated = lastNode.addBranch(message)
                    branchStates[updated.id] = updated.selectIndex
                    nodes.dropLast(1) + updated
                } else {
                    val node = MessageNode.from(message)
                    branchStates[node.id] = 0
                    nodes + node
                }
            }
        } else {
            // Replace current branch (streaming update)
            _nodes.update { nodes ->
                if (nodes.isEmpty()) return@update nodes
                val lastNode = nodes.last()
                if (lastNode.messages.firstOrNull()?.role == MessageRole.ASSISTANT &&
                    lastNode.messages.firstOrNull()?.id == message.id
                ) {
                    val updated = lastNode.replaceCurrent(message)
                    nodes.dropLast(1) + updated
                } else {
                    val node = MessageNode.from(message)
                    branchStates[node.id] = 0
                    nodes + node
                }
            }
        }
        refreshDisplay()
    }

    /** Switch to a different branch at the given node position. */
    fun selectBranch(nodeId: String, index: Int) {
        branchStates[nodeId] = index
        _nodes.update { nodes ->
            nodes.map { node ->
                if (node.id == nodeId) node.selectBranch(index) else node
            }
        }
        refreshDisplay()
    }

    /** Get the current branch index for a node. */
    fun getBranchIndex(nodeId: String): Int = branchStates[nodeId] ?: 0

    /** Get the total branch count for a node. */
    fun getBranchCount(nodeId: String): Int {
        return _nodes.value.firstOrNull { it.id == nodeId }?.branchCount ?: 1
    }

    /** Check if a node has multiple branches. */
    fun hasBranches(nodeId: String): Boolean = getBranchCount(nodeId) > 1

    /** Mark the last assistant position for regeneration. */
    fun prepareRegeneration(): Boolean {
        val nodes = _nodes.value
        // Find last assistant node
        val lastAssistantIdx = nodes.indexOfLast { node ->
            node.messages.firstOrNull()?.role == MessageRole.ASSISTANT
        }
        if (lastAssistantIdx < 0) return false

        // Keep the current branch but mark for new branch creation
        return true
    }

    /** Remove the last assistant branch (for regeneration). */
    fun removeLastBranch(): List<UIMessage> {
        val nodes = _nodes.value
        if (nodes.isEmpty()) return emptyList()

        val lastNode = nodes.last()
        if (lastNode.branchCount > 1 && lastNode.messages.firstOrNull()?.role == MessageRole.ASSISTANT) {
            // Remove current branch, select previous
            val newSelectIndex = (lastNode.selectIndex - 1).coerceAtLeast(0)
            val updatedNode = lastNode.copy(
                messages = lastNode.messages.filterIndexed { i, _ -> i != lastNode.selectIndex },
                selectIndex = newSelectIndex,
            )
            branchStates[updatedNode.id] = newSelectIndex
            _nodes.update { it.dropLast(1) + updatedNode }
        }
        refreshDisplay()
        return _displayMessages.value
    }

    private fun refreshDisplay() {
        _displayMessages.value = _nodes.value.map { it.currentMessage }
    }
}
