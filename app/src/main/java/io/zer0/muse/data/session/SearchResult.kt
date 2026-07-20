package io.zer0.muse.data.session

/**
 * 搜索结果项。
 *
 * @param messageId 匹配的消息 id
 * @param sessionId 所属会话 id(点击跳转用)
 * @param sessionTitle 会话标题(结果显示)
 * @param contentSnippet 内容片段(匹配关键词前后 30 字)
 * @param role 消息角色
 * @param createdAt 创建时间戳
 */
data class SearchResult(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val contentSnippet: String,
    val role: String,
    val createdAt: Long,
)
