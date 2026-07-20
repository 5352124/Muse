package io.zer0.muse.tools

/**
 * 工具权限解析器。
 *
 * 综合三要素决定一次工具调用是否需要审批:
 *  1. 当前会话的 [SessionPermissionMode]
 *  2. 工具自身的 [ToolRiskLevel]
 *  3. 用户针对该工具单独设置的 [ToolApprovalPolicy]
 *
 * 优先级:单工具 ALWAYS_DENY > 会话 STRICT > 单工具 ALWAYS_ALLOW > 会话 TRUSTED/ASK。
 */
object ToolPermissionResolver {

    /**
     * 解析工具调用的初始审批状态。
     *
     * @param toolName 工具名
     * @param risk 工具风险等级(UNKNOWN 时使用 [fallbackRiskFor] 推断)
     * @param mode 当前会话权限模式
     * @param perToolPolicy 用户对该工具设置的持久化策略,未设置为 null
     */
    fun resolve(
        toolName: String,
        risk: ToolRiskLevel?,
        mode: SessionPermissionMode,
        perToolPolicy: ToolApprovalPolicy?,
    ): ToolApprovalState {
        val effectiveRisk = risk ?: fallbackRiskFor(toolName)
        // 1. 用户显式禁用某工具时,任何模式都拒绝
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_DENY) {
            return ToolApprovalState.Denied("工具 $toolName 已被用户禁用")
        }

        // 2. 严格模式:安全工具之外全部审批;安全工具中若涉及外部应用/网络也审批
        if (mode == SessionPermissionMode.STRICT) {
            return if (effectiveRisk == ToolRiskLevel.SAFE && toolName in STRICT_SAFE_ALLOWLIST) {
                ToolApprovalState.Auto
            } else {
                ToolApprovalState.Pending
            }
        }

        // 3. 用户显式允许某工具时,信任模式下自动执行;询问模式下仍需审批(除非为安全工具)
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_ALLOW) {
            return when (mode) {
                SessionPermissionMode.TRUSTED -> ToolApprovalState.Auto
                SessionPermissionMode.ASK -> if (effectiveRisk == ToolRiskLevel.SAFE) ToolApprovalState.Auto else ToolApprovalState.Pending
                SessionPermissionMode.STRICT -> ToolApprovalState.Pending // 上面已处理,不会到达
            }
        }

        // 4. 默认策略(按会话模式 + risk)
        return when (mode) {
            SessionPermissionMode.TRUSTED -> when (effectiveRisk) {
                ToolRiskLevel.SAFE -> ToolApprovalState.Auto
                ToolRiskLevel.NORMAL -> ToolApprovalState.Auto
                ToolRiskLevel.HIGH -> ToolApprovalState.Pending
            }
            SessionPermissionMode.ASK -> when (effectiveRisk) {
                ToolRiskLevel.SAFE -> ToolApprovalState.Auto
                ToolRiskLevel.NORMAL -> ToolApprovalState.Pending
                ToolRiskLevel.HIGH -> ToolApprovalState.Pending
            }
            SessionPermissionMode.STRICT -> ToolApprovalState.Pending // 上面已处理
        }
    }

    /**
     * 对未在 [ToolRegistry] 注册的工具(如 Skill 工具)做风险等级兜底。
     *
     * 按工具 ID 的语义归类:只读/查询为 SAFE,涉及外部网络或本地副作用为 NORMAL,
     * 可执行代码/安装/跨助手/文件写删/浏览器自动化归为 HIGH。
     */
    private fun fallbackRiskFor(toolName: String): ToolRiskLevel = when (toolName) {
        // 只读/查询类
        "web_search", "web_fetch", "knowledge_search", "arxiv_search",
        "read_file", "http_get", "current_status", "recall_experience",
        -> ToolRiskLevel.SAFE
        // 本地副作用/写入类(可逆)
        "write_file", "http_post", "pin_memory", "unpin_memory",
        "todo_write", "show_card", "notify", "record_experience",
        "channel_reply", "channel_pass", "channel_read_context",
        -> ToolRiskLevel.NORMAL
        // 高风险:执行代码/安装/跨助手/文件删除/浏览器自动化/系统状态改变
        "install_skill", "delegate_agent", "execute_javascript",
        "browser_navigate", "browser_click", "browser_type",
        "browser_extract", "browser_scroll_bottom", "browser_get_html",
        "workspace_write", "workspace_delete", "workspace_mkdir", "workspace_move",
        -> ToolRiskLevel.HIGH
        // 未知工具保守按 NORMAL 处理(ASK 模式会询问)
        else -> ToolRiskLevel.NORMAL
    }

    /**
     * 严格模式下仍自动允许的安全工具白名单。
     * 仅包含纯本地只读、无网络、无外部应用跳转的工具。
     */
    private val STRICT_SAFE_ALLOWLIST = setOf(
        "get_current_time",
        "calculator",
        "echo",
    )
}
