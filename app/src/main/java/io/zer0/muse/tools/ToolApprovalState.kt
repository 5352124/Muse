package io.zer0.muse.tools

/**
 * Tool approval state machine (RikkaHub GenerationHandler.kt port).
 *
 * Controls whether a tool call needs user approval before execution.
 *
 * Flow:
 *  - AUTO: Tool is auto-approved (always allow)
 *  - PENDING: Waiting for user decision
 *  - APPROVED: User approved this call
 *  - DENIED: User denied this call (with optional reason)
 *  - ANSWERED: User provided a custom answer instead of executing
 */
sealed class ToolApprovalState {
    /** Auto-approved, execute immediately. */
    data object Auto : ToolApprovalState()

    /** Waiting for user approval. Generation loop should break here. */
    data object Pending : ToolApprovalState()

    /** User approved this tool call. */
    data object Approved : ToolApprovalState()

    /** User denied this tool call. */
    data class Denied(val reason: String = "") : ToolApprovalState()

    /** User provided a custom text answer instead of executing the tool. */
    data class Answered(val answer: String) : ToolApprovalState()

    val isTerminal: Boolean
        get() = this is Approved || this is Denied || this is Answered

    val isExecutable: Boolean
        get() = this is Auto || this is Approved
}

/**
 * Per-tool approval policy, persisted to DataStore.
 */
enum class ToolApprovalPolicy {
    /** Always auto-approve (default for safe tools like get_time). */
    ALWAYS_ALLOW,
    /** Always deny (tool effectively disabled). */
    ALWAYS_DENY,
    /** Ask user every time. */
    ASK_EVERY_TIME,
}
