package io.zer0.muse.tools

/**
 * 工具审批状态机（移植自 RikkaHub GenerationHandler.kt）。
 *
 * 控制工具调用在执行前是否需要用户审批。
 *
 * 流程：
 *  - AUTO：工具自动审批通过（始终允许）
 *  - PENDING：等待用户决定
 *  - APPROVED：用户批准了本次调用
 *  - DENIED：用户拒绝了本次调用（可附原因）
 *  - ANSWERED：用户提供了自定义回答而非执行工具
 */
sealed class ToolApprovalState {
    /** 自动审批通过，立即执行。 */
    data object Auto : ToolApprovalState()

    /** 等待用户审批。生成循环应在此中断。 */
    data object Pending : ToolApprovalState()

    /** 用户批准了本次工具调用。 */
    data object Approved : ToolApprovalState()

    /** 用户拒绝了本次工具调用。 */
    data class Denied(val reason: String = "") : ToolApprovalState()

    /** 用户提供了自定义文本回答，而非执行该工具。 */
    data class Answered(val answer: String) : ToolApprovalState()

    val isTerminal: Boolean
        get() = this is Approved || this is Denied || this is Answered

    val isExecutable: Boolean
        get() = this is Auto || this is Approved
}

/**
 * 针对单个工具的审批策略，持久化到 DataStore。
 */
enum class ToolApprovalPolicy {
    /** 始终自动批准（如 get_time 等安全工具的默认值）。 */
    ALWAYS_ALLOW,
    /** 始终拒绝（相当于禁用该工具）。 */
    ALWAYS_DENY,
    /** 每次都询问用户。 */
    ASK_EVERY_TIME,
}
