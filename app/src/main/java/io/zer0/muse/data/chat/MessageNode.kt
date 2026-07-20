package io.zer0.muse.data.chat

import io.zer0.ai.core.UIMessage

/**
 * Message branching node (RikkaHub MessageNode port).
 *
 * Each position in a conversation can hold multiple alternative messages (branches).
 * [selectIndex] determines which branch is currently active.
 *
 * Example: User sends "hello" → Assistant responds with branch A.
 * User clicks "regenerate" → Assistant responds with branch B.
 * MessageNode holds [A, B] with selectIndex=1 (showing B).
 * User can switch back to A by changing selectIndex=0.
 */
data class MessageNode(
    val id: String,
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
) {
    /** The currently active message for this node. */
    val currentMessage: UIMessage
        get() = messages.getOrNull(selectIndex.coerceIn(0, messages.lastIndex))
            ?: messages.last()

    /** Number of branches. */
    val branchCount: Int get() = messages.size

    /** Whether there are multiple branches to switch between. */
    val hasBranches: Boolean get() = messages.size > 1

    /** Switch to a different branch. Returns new node with updated selectIndex. */
    fun selectBranch(index: Int): MessageNode {
        val safeIndex = index.coerceIn(0, messages.lastIndex)
        return copy(selectIndex = safeIndex)
    }

    /** Add a new branch (e.g., from regeneration). Auto-selects the new branch. */
    fun addBranch(message: UIMessage): MessageNode {
        return copy(
            messages = messages + message,
            selectIndex = messages.size, // select the new one
        )
    }

    /** Replace the current branch message. */
    fun replaceCurrent(message: UIMessage): MessageNode {
        val updated = messages.toMutableList()
        if (selectIndex in updated.indices) {
            updated[selectIndex] = message
        }
        return copy(messages = updated)
    }

    companion object {
        /** Create a node from a single message. */
        fun from(message: UIMessage): MessageNode =
            MessageNode(
                id = message.id.toString(),
                messages = listOf(message),
                selectIndex = 0,
            )
    }
}

/**
 * Convert a list of UIMessages to a list of MessageNodes.
 * Each user message starts a new node group (user + assistant pair).
 */
fun List<UIMessage>.toNodes(): List<MessageNode> {
    if (isEmpty()) return emptyList()
    return map { MessageNode.from(it) }
}

/**
 * Flatten nodes back to a list of UIMessages (using selected branches).
 */
fun List<MessageNode>.toMessages(): List<UIMessage> =
    map { it.currentMessage }
