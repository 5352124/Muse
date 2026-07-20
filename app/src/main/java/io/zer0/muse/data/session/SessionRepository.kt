package io.zer0.muse.data.session

import android.content.Context
import androidx.room.withTransaction
import io.zer0.ai.core.MessageRole
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import io.zer0.muse.R

/**
 * 会话仓库:封装 SessionDao + MessageDao,提供领域模型 API。
 *
 * - 会话列表 / 创建 / 删除 / 切换
 * - 消息持久化(发送时写库,流式增量更新 assistant,编辑/重生成截断)
 * - [MessageEntity] ↔ [UIMessage] 双向转换
 *
 * H-SESS1: 跨表操作(messages + messages_fts + sessions)统一用 [database.withTransaction]
 * 包裹,保证崩溃后数据一致;[database] 由 Koin 注入(见 AppKoinModule)。
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val database: MuseDb,
    private val context: Context,
    private val messageImageStore: MessageImageStore,
    /** v1.135-A: 视觉辅助缓存清理(会话删除时清理 sidecar)。 */
    private val visionCache: io.zer0.muse.vision.VisionCache? = null,
    /** P3: 会话权限模式清理(会话删除时清理持久化设置)。 */
    private val sessionPermissionStore: SessionPermissionStore? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val urlListSerializer = ListSerializer(String.serializer())
    /** v1.133: RAG 引用列表序列化器(持久化到 messages.ragCitationsJson)。 */
    private val ragCitationListSerializer = ListSerializer(RagCitation.serializer())

    companion object {
        private const val TAG = "SessionRepo"
        /** H-SESS3: observeBySession 默认加载条数,与分页加载配合避免 OOM。 */
        private const val OBSERVE_LIMIT = 200
        /** 会话预览(lastMessagePreview)最大长度。 */
        private const val MESSAGE_PREVIEW_LENGTH = 50
        /** 首条 user 消息自动命名时取内容前缀长度。 */
        private const val AUTO_TITLE_LENGTH = 20
        /** 搜索片段匹配位置前后保留字数。 */
        private const val SNIPPET_RADIUS = 30
        /** 搜索片段无匹配时回退取前缀长度。 */
        private const val SNIPPET_FALLBACK_LENGTH = 60
    }

    /**
     * 观察未归档会话(置顶优先,再按 updatedAt 降序)。主列表用。
     * v1.28: 排除 Agent Tab 会话(isAgentSession=0),Agent 日常聊天不污染任务列表。
     */
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeTaskSessions()

    /** v0.45: 观察已归档会话(按 updatedAt 降序)。归档列表用。 */
    fun observeArchived(): Flow<List<SessionEntity>> = sessionDao.observeArchived()

    /** v0.45: 切换会话归档状态。 */
    suspend fun setArchived(sessionId: String, archived: Boolean) {
        sessionDao.setArchived(sessionId, archived)
    }

    // ── v2.0: 软删除 ──

    /** v2.0: 软删除会话。 */
    suspend fun softDeleteSession(id: String) {
        sessionDao.softDelete(id, System.currentTimeMillis())
    }

    /** v2.0: 恢复软删除的会话。 */
    suspend fun restoreSession(id: String) {
        sessionDao.restore(id)
    }

    /** v2.0: 观察 7 天内软删除的会话。 */
    fun observeDeletedSessions(): Flow<List<SessionEntity>> {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        return sessionDao.observeDeleted(cutoff)
    }

    /** v2.0: 永久过期清理(7 天前的软删除会话)。 */
    suspend fun purgeOldDeletedSessions() {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                messageDao.clearFts()
                sessionDao.permanentlyDeleteOldSessions(cutoff)
            }
        }
    }

    /** v2.0: 清空全部会话(硬删除,替代原 deleteAll 用于数据管理页)。 */
    suspend fun hardDeleteAllSessions() {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.clearFts()
                sessionDao.deleteAll()
            }
        }
    }

    /** 观察会话总数(统计面板用)。 */
    fun observeSessionCount(): Flow<Int> = sessionDao.observeCount()

    /** 观察消息总数(统计面板用)。 */
    fun observeMessageCount(): Flow<Int> = messageDao.observeCount()

    /** v2.0: 一次性获取会话总数。 */
    suspend fun getSessionCount(): Int = sessionDao.count()

    /** v2.0: 一次性获取消息总数。 */
    suspend fun getTotalMessageCount(): Int = messageDao.countMessages()

    /** 创建新会话。返回会话 id。标题默认"新会话"。 */
    suspend fun createSession(assistantId: String = "default"): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = context.getString(R.string.session_repo_default_title),
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
            )
        )
        return id
    }

    /** v1.28: 创建 Agent Tab 专用会话(独立于任务会话,不在任务列表显示)。 */
    suspend fun createAgentSession(assistantId: String = "default"): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = "Agent",
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
                isAgentSession = true,
            )
        )
        return id
    }

    /** v1.28: 获取最近的 Agent 会话(用于自动恢复)。 */
    suspend fun getLatestAgentSession(): SessionEntity? = sessionDao.getLatestAgentSession()

    /**
     * v1.58: 从源会话的某条消息处分叉,创建新会话并复制到该消息为止的全部历史。
     *
     * - 复用源会话的 assistantId / folderId
     * - 消息重新生成 id(避免主键冲突),保留原 createdAt 顺序
     * - 标题改为"分叉自 {源标题}"
     *
     * @return 新会话 id;源会话或锚点消息不存在时返回 null。
     */
    suspend fun forkSession(sourceSessionId: String, untilMessageId: String): String? {
        // H-SESS1: 跨表(sessions + messages + messages_fts)复制,用事务包裹,
        // 崩溃时整体回滚,避免分叉会话消息/FTS 索引不完整。
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val sourceSession = sessionDao.getById(sourceSessionId) ?: return@withTransaction null
                val anchor = messageDao.getById(sourceSessionId, untilMessageId) ?: return@withTransaction null
                val newId = Uuid.random().toString()
                val now = System.currentTimeMillis()
                sessionDao.insert(
                    SessionEntity(
                        id = newId,
                        title = context.getString(R.string.session_repo_fork_title, sourceSession.title.ifBlank { context.getString(R.string.session_repo_default_title) }),
                        createdAt = now,
                        updatedAt = now,
                        assistantId = sourceSession.assistantId,
                        folderId = sourceSession.folderId,
                        parentSessionId = sourceSessionId,
                    )
                )
                sessionDao.incrementChildCount(sourceSessionId)
                // 复制到锚点为止的全部消息(含锚点),重新生成 id 避免主键冲突
                // 调 appendMessageInternal 复用 FTS 同步 + 预览更新,且不另开事务(已在事务内)
                val messages = messageDao.getUpToBySession(sourceSessionId, anchor.createdAt)
                for (msg in messages) {
                    appendMessageInternal(newId, msg.toUIMessage().copy(id = Uuid.random()))
                }
                newId
            }
        }
    }

    /** v1.28: 观察任务会话(排除 Agent Tab 会话)。 */
    fun observeTaskSessions(): Flow<List<SessionEntity>> = sessionDao.observeTaskSessions()

    /** 删除会话(级联删消息 + FTS 索引)。 */
    suspend fun deleteSession(id: String) {
        // Phase 10.3: 先删 FTS 索引(依赖 messages 表子查询,必须在 messages 被级联删之前)
        // H-SESS1: 跨表(FTS + sessions)用事务包裹,保证一致性
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.deleteFtsBySession(id)
                sessionDao.deleteById(id)
            }
            // v1.135-A: 清理该会话的视觉辅助缓存 sidecar,避免孤儿文件
            runCatching { visionCache?.clearSession(id) }
                .onFailure { Logger.w(TAG, "清理视觉缓存失败: $id", it) }
            // P3: 清理该会话的权限模式设置
            runCatching { sessionPermissionStore?.clearMode(id) }
                .onFailure { Logger.w(TAG, "清理会话权限模式失败: $id", it) }
        }
    }

    /** 重命名会话。 */
    suspend fun renameSession(id: String, title: String) {
        // H-SESS2: 原子 UPDATE,避免读-改-写并发丢失更新
        sessionDao.updateTitle(id, title, System.currentTimeMillis())
    }

    /** Phase 8.2: 切换会话绑定的 Assistant。 */
    suspend fun setSessionAssistant(sessionId: String, assistantId: String) {
        sessionDao.setAssistantId(sessionId, assistantId)
    }

    /** Phase 8.2: 切换会话置顶状态。 */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned)
    }

    /** Phase 9.1 (M13): 切换会话所属文件夹(null = 移到未分组)。 */
    suspend fun setSessionFolder(sessionId: String, folderId: String?) {
        sessionDao.setFolderId(sessionId, folderId)
    }

    /** Phase 8.2: 取会话绑定的 Assistant id(无则默认 'default')。 */
    suspend fun getAssistantId(sessionId: String): String {
        return sessionDao.getById(sessionId)?.assistantId ?: "default"
    }

    /** Phase 8.3: 切换消息收藏状态(跨会话收藏面板通过 observeAllFavorites 聚合)。 */
    suspend fun setMessageFavorite(messageId: String, favorite: Boolean) {
        messageDao.setFavorite(messageId, favorite)
    }

    /** 功能1: 设置消息表情回应(null = 取消回应)。 */
    suspend fun setMessageReaction(messageId: String, reaction: String?) {
        messageDao.setReaction(messageId, reaction)
    }

    /** v1.104 U7: 设置收藏分组标签(null = 移到未分组)。 */
    suspend fun setMessageFavoriteTag(messageId: String, tag: String?) {
        messageDao.setMessageFavoriteTag(messageId, tag)
    }

    /** v1.104 U7: 观察所有已命名的收藏分组标签(去 NULL / 去重 / 升序)。 */
    fun observeAllFavoriteTags(): Flow<List<String>> {
        return messageDao.observeAllFavoriteTags()
    }

    /** v1.48: 按 id 删除单条消息(长按菜单"删除消息")。同时清理 FTS 索引。 */
    suspend fun deleteMessage(messageId: String) {
        // H-SESS1: 跨表(FTS + messages)用事务包裹,保证 FTS 与消息同步删除
        withContext(Dispatchers.IO) {
            database.withTransaction {
                // v1.107 冗余: 先查出 sessionId,用于维护冗余计数
                val entity = messageDao.getByMessageId(messageId)
                messageDao.deleteFts(messageId)
                messageDao.deleteById(messageId)
                // v1.107 冗余: 递减 sessions.messageCount
                if (entity != null) {
                    runCatching { sessionDao.incrementMessageCount(entity.sessionId, -1) }
                        .onFailure { Logger.w(TAG, "decrementMessageCount failed: ${it.message}") }
                }
            }
            // v1.134 P1-2: 清理该消息的图片文件,避免孤儿文件占用存储
            runCatching { messageImageStore.deleteByMessageId(messageId) }
                .onFailure { Logger.w(TAG, "deleteMessage images cleanup failed: ${it.message}") }
        }
    }

    /** Phase 8.3: 观察跨会话的全部收藏消息(收藏面板用)。 */
    fun observeAllFavorites(): Flow<List<UIMessage>> {
        return messageDao.observeAllFavorites()
            .map { entities -> entities.map { it.toUIMessage() } }
            .distinctUntilChanged()
    }

    /** 观察指定会话的消息列表(转 UIMessage)。 */
    fun observeMessages(sessionId: String): Flow<List<UIMessage>> {
        // H-SESS3: 限最近 OBSERVE_LIMIT 条,避免长会话全量加载 OOM;更早历史走分页加载
        return messageDao.observeRecentBySession(sessionId, OBSERVE_LIMIT)
            .map { entities -> entities.map { it.toUIMessage() } }
            .distinctUntilChanged()
    }

    /**
     * v1.53-A1: 分页加载 — 取指定会话最近 limit 条消息(按 createdAt 升序)。
     *
     * DAO 返回降序(最新在前),这里 reversed() 转为升序,便于直接渲染。
     * 用于初始加载,避免一次性加载全部消息导致卡顿/OOM。
     */
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<UIMessage> {
        return messageDao.getRecentBySession(sessionId, limit).reversed().map { it.toUIMessage() }
    }

    /**
     * v1.53-A1: 分页加载 — 取早于 beforeCreatedAt 的 limit 条消息(按 createdAt 升序)。
     *
     * 用于上滑加载更多:以当前列表最早一条消息的 createdAt 为锚点,取更早的历史。
     * 返回升序,可直接前置拼接到现有 messages 列表。
     */
    suspend fun getOlderMessages(sessionId: String, beforeCreatedAt: Long, limit: Int): List<UIMessage> {
        return messageDao.getOlderBySession(sessionId, beforeCreatedAt, limit).reversed().map { it.toUIMessage() }
    }

    /** v1.53-A1: 会话消息总数(分页判断 hasMoreHistory 用)。 */
    suspend fun getMessageCount(sessionId: String): Int = messageDao.countBySession(sessionId)

    /** 持久化一条消息,返回其 id。同时更新会话的 updatedAt + lastMessagePreview + FTS 索引。 */
    suspend fun appendMessage(sessionId: String, message: UIMessage): String {
        // H-SESS1: 跨表(messages + FTS + sessions)用事务包裹,保证一致性
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                appendMessageInternal(sessionId, message)
            }
        }
    }

    /**
     * appendMessage 的非事务版本,供已在事务内的调用方(如 [forkSession])复用,
     * 避免嵌套事务的 savepoint 开销。
     */
    private suspend fun appendMessageInternal(sessionId: String, message: UIMessage): String {
        val entity = message.toEntity(sessionId)
        messageDao.upsert(entity)
        // Phase 10.3: 同步 FTS 索引(toNgram 预处理中文 2-gram)
        // v1.63: 加前置 deleteFts 防御,避免未来误用 appendMessage 更新已存在 id 时 FTS 重复
        runCatching {
            messageDao.deleteFts(entity.id)
            messageDao.insertFts(entity.id, MessageFtsManager.toNgram(entity.content))
        }.onFailure { Logger.w(TAG, "FTS insert failed: ${it.message}") }
        updateSessionPreview(sessionId, message)
        // v1.107 冗余: 维护 sessions.messageCount(避免列表页 COUNT)
        runCatching { sessionDao.incrementMessageCount(sessionId, 1) }
            .onFailure { Logger.w(TAG, "incrementMessageCount failed: ${it.message}") }
        return entity.id
    }

    /**
     * 流式更新 assistant 消息(多次 upsert 同一 id)。每次先删后插保证 FTS 索引幂等。
     *
     * v1.97 (P1-2): 新增 [skipFts] 参数。流式周期性落盘(builder 还在增长)时传 true,
     * 跳过 toNgram + deleteFts + insertFts 的 CPU/IO 开销(长文本 toNgram 可达数十毫秒)。
     * FTS 是派生数据,流式结束后最终落盘或下次启动 ensureFtsIndexConsistent 会补齐。
     * 非流式路径(最终落盘 / 中断落盘 / 工具消息 / 引用消息)用默认 false,保证 FTS 同步。
     */
    suspend fun upsertMessage(sessionId: String, message: UIMessage, skipFts: Boolean = false) {
        // H-SESS1: 跨表(messages + FTS + sessions)用事务包裹,保证流式更新一致性
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val entity = message.toEntity(sessionId)
                messageDao.upsert(entity)
                // Phase 10.3: 同步 FTS(删后插,避免重复索引项)
                // v1.97 (P1-2): skipFts=true 时跳过,避免流式周期性落盘反复重建 FTS
                if (!skipFts) {
                    runCatching {
                        messageDao.deleteFts(entity.id)
                        messageDao.insertFts(entity.id, MessageFtsManager.toNgram(entity.content))
                    }.onFailure { Logger.w(TAG, "FTS upsert sync failed: ${it.message}") }
                }
                if (message.role == MessageRole.ASSISTANT && message.content.isNotEmpty()) {
                    updateSessionPreview(sessionId, message)
                }
            }
        }
    }

    /**
     * 编辑助手消息:直接更新指定消息的内容,并同步 FTS 索引。
     */
    suspend fun updateMessageContent(sessionId: String, messageId: Uuid, content: String) {
        // H-SESS1: 跨表(messages + FTS + sessions)用事务包裹
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val message = messageDao.getById(sessionId, messageId.toString()) ?: return@withTransaction
                val updated = message.copy(content = content, reasoning = null)
                messageDao.upsert(updated)
                // Phase 10.3: 同步 FTS 索引(删后插,避免重复索引项)
                runCatching {
                    messageDao.deleteFts(message.id)
                    messageDao.insertFts(message.id, MessageFtsManager.toNgram(content))
                }.onFailure { Logger.w(TAG, "FTS update failed: ${it.message}") }
                // 更新会话预览为最新的消息内容
                updateSessionPreview(sessionId, updated.toUIMessage())
            }
        }
    }

    /** 截断会话:删除 createdAt >= fromCreatedAt 的消息(编辑/重生成用)。同步删 FTS 索引。 */
    suspend fun truncateFrom(sessionId: String, fromCreatedAt: Long) {
        // H-SESS1: 跨表(FTS + messages)用事务包裹
        withContext(Dispatchers.IO) {
            database.withTransaction {
                truncateFromInternal(sessionId, fromCreatedAt)
            }
        }
    }

    /** truncateFrom 的非事务版本,供已在事务内的调用方(如 [deleteLastMessage])复用。 */
    private suspend fun truncateFromInternal(sessionId: String, fromCreatedAt: Long) {
        // Phase 10.3: 先删 FTS(依赖 messages 子查询,必须在 messages 删除之前)
        messageDao.deleteFtsBySessionAndCreatedAt(sessionId, fromCreatedAt)
        messageDao.deleteFromCreatedAt(sessionId, fromCreatedAt)
    }

    /** 删除会话内最后一条 assistant 消息(重生成用)。同步删 FTS 索引。 */
    suspend fun deleteLastMessage(sessionId: String) {
        // H-SESS1/H-SESS2: 跨表(messages + FTS + sessions)读-改-写,用事务包裹保证一致;
        // 预览更新改用原子 UPDATE(无读-改-写)。
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.getLastBySession(sessionId)?.let { last ->
                    // 复用 truncateFromInternal(含 FTS 同步),避免漏删索引
                    truncateFromInternal(sessionId, last.createdAt)
                }
                // 更新 preview 为新的最后一条(原子 UPDATE,无读-改-写)
                val newLast = messageDao.getLastBySession(sessionId)
                sessionDao.updatePreview(
                    id = sessionId,
                    preview = newLast?.content?.take(MESSAGE_PREVIEW_LENGTH) ?: "",
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    /** 取会话最后一条消息(一次性,非 Flow)。 */
    suspend fun getLastMessage(sessionId: String): UIMessage? {
        return messageDao.getLastBySession(sessionId)?.toUIMessage()
    }

    /**
     * 全文搜索消息(跨会话)。返回搜索结果列表(含会话标题 + 内容片段)。
     *
     * Phase 10.3 改造:
     * - 优先走 FTS4 MATCH 查询([MessageFtsManager.toMatchQuery] 转 ngram + 引号转义)
     * - JOIN messages + sessions 一次查出(修复 P5-B 的 N+1:原实现循环 sessionDao.getById)
     * - ngram 转换后为空(纯符号)或 FTS 查询异常时回退 LIKE([searchLike])
     * - 片段仍取匹配位置前后 30 字(基于原文 content,不是 ngram)
     */
    suspend fun searchMessages(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val matchQuery = MessageFtsManager.toMatchQuery(trimmed)
        val joins: List<MessageSearchJoin> = if (matchQuery.isBlank()) {
            // ngram 转换后为空(纯符号/纯空白),直接走 LIKE
            searchLikeAndJoin(trimmed)
        } else {
            runCatching { messageDao.searchFts(matchQuery) }
                .getOrElse {
                    Logger.w(TAG, "FTS search failed, fallback to LIKE: ${it.message}")
                    searchLikeAndJoin(trimmed)
                }
        }

        joins.map { join ->
            SearchResult(
                messageId = join.messageId,
                sessionId = join.sessionId,
                sessionTitle = join.sessionTitle,
                contentSnippet = buildSnippet(join.content, trimmed),
                role = join.role,
                createdAt = join.createdAt,
            )
        }
    }

    /** LIKE 回退路径:查 messages 后补查 session 标题(FTS 不可用时的兜底)。 */
    private suspend fun searchLikeAndJoin(query: String): List<MessageSearchJoin> {
        // M-SESS2: 转义 \ % _ 后包成 %...%,配合 DAO 的 ESCAPE '\' 子句
        val pattern = buildLikePattern(query)
        val messages = messageDao.searchLike(pattern)
        return messages.map { msg ->
            val sessionTitle = sessionDao.getById(msg.sessionId)?.title ?: ""
            MessageSearchJoin(
                messageId = msg.id,
                sessionId = msg.sessionId,
                content = msg.content,
                role = msg.role,
                createdAt = msg.createdAt,
                sessionTitle = sessionTitle,
            )
        }
    }

    /** M-SESS2: 构造 LIKE 模式串,转义通配符 \ % _ 后包成 %...%。 */
    private fun buildLikePattern(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * Phase 10.3: 检查 FTS 索引一致性,不一致则全量 rebuild。
     *
     * 触发场景:
     * - v8→v9 迁移后(messages_fts 空表,需首次全量索引)
     * - 外部修改 messages 表(备份导入后)
     * - 索引损坏(FTS 查询异常后下次启动自愈)
     *
     * 由 [ChatViewModel] init 在 app 启动时异步调用,IO 线程执行。
     * 万级消息 rebuild 约数百毫秒,不阻塞 UI。
     */
    suspend fun ensureFtsIndexConsistent() = withContext(Dispatchers.IO) {
        val msgCount = runCatching { messageDao.countMessages() }.getOrDefault(-1)
        val ftsCount = runCatching { messageDao.countFts() }.getOrDefault(-1)
        if (msgCount < 0 || ftsCount < 0) {
            Logger.w(TAG, "FTS count check failed: msg=$msgCount fts=$ftsCount")
            return@withContext
        }
        if (msgCount == ftsCount) {
            Logger.d(TAG, "FTS index consistent: $ftsCount rows")
            return@withContext
        }
        Logger.i(TAG, "FTS index inconsistent: msg=$msgCount fts=$ftsCount, rebuilding...")
        rebuildFtsIndex()
    }

    /**
     * Phase 10.3: 全量重建 FTS 索引。清空后遍历 messages 重新插入(toNgram 转换)。
     *
     * v1.114: 将 clearFts + 逐条 insert 包裹在 `database.withTransaction` 中,避免重建期间
     * FTS 查询返回不一致结果(清空后、插入完成前查询会返回空或不完整数据)。每条 insert 仍用
     * runCatching 容错,单条失败不影响其他条目,事务正常提交。
     * 调用方应在 IO 线程。
     */
    suspend fun rebuildFtsIndex() = withContext(Dispatchers.IO) {
        // v1.114: 事务包裹,保证 clearFts + insert 原子可见性,避免重建期间查询不一致
        database.withTransaction {
            messageDao.clearFts()
            val rows = messageDao.getAllForFtsRebuild()
            var ok = 0
            rows.forEach { row ->
                runCatching {
                    messageDao.insertFts(row.id, MessageFtsManager.toNgram(row.content))
                    ok++
                }.onFailure {
                    Logger.w(TAG, "FTS rebuild insert failed for ${row.id}: ${it.message}")
                }
            }
            Logger.i(TAG, "FTS rebuild done: $ok/${rows.size} messages indexed")
        }
    }

    /** 构建搜索片段:取匹配位置前后 [SNIPPET_RADIUS] 字。 */
    private fun buildSnippet(content: String, query: String): String {
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(SNIPPET_FALLBACK_LENGTH)
        val start = (idx - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (idx + query.length + SNIPPET_RADIUS).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end) + suffix
    }

    /**
     * 更新会话预览 + updatedAt。
     *
     * H-SESS2: 改用原子 UPDATE(含 CASE 条件标题),彻底避免读-改-写竞态。
     * 首条 user 消息自动用内容前 [AUTO_TITLE_LENGTH] 字作标题(若标题仍是默认"新会话")。
     */
    private suspend fun updateSessionPreview(sessionId: String, message: UIMessage) {
        val preview = message.content.take(MESSAGE_PREVIEW_LENGTH).ifBlank { "…" }
        val isUser = if (message.role == MessageRole.USER) 1 else 0
        val defaultTitle = context.getString(R.string.session_repo_default_title)
        val autoTitle = message.content.take(AUTO_TITLE_LENGTH).ifBlank { defaultTitle }
        sessionDao.updatePreviewAndTitle(
            id = sessionId,
            preview = preview,
            now = System.currentTimeMillis(),
            defaultTitle = defaultTitle,
            isUser = isUser,
            autoTitle = autoTitle,
        )
    }

    // ── 转换工具 ──────────────────────────────────────────────────────────

    /** MessageEntity → UIMessage。 */
    private fun MessageEntity.toUIMessage(): UIMessage = UIMessage(
        // Phase 8.5 修复: 非 UUID 格式的 id 不崩溃(数据导入/旧版本兼容),回退到随机 UUID
        id = runCatching { Uuid.parse(id) }.getOrElse {
            Logger.w(TAG, "invalid message id (not UUID): $id")
            Uuid.random()
        },
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
        content = content,
        reasoning = reasoning,
        modelId = modelId,
        createdAt = createdAt,
        imageUrls = parseImageUrls(imageUrlsJson),
        favorite = favorite,
        // v1.104 U7: 收藏分组标签往返(默认 NULL,未分组)
        favoriteTag = favoriteTag,
        citationUrls = parseImageUrls(citationUrlsJson),
        // v1.133: RAG 引用列表持久化往返
        ragCitations = parseRagCitations(ragCitationsJson),
        // Phase 8.6: 多模态图片 base64 列表
        // v1.134 P1-2: MessageImageStore 把 "file://" 路径转回 base64,
        // 旧数据(纯 base64)原样返回,向后兼容
        imageBase64List = messageImageStore.toBase64List(parseImageUrls(imageBase64Json)),
        // v1.43: Artifact id 列表
        artifactIds = parseImageUrls(artifactIdsJson),
        // v1.103: MOOD / reflection 持久化往返(之前任务会话/Agent 落盘时丢失)
        mood = mood,
        reflection = reflection,
        // 功能1: 消息表情回应往返
        reaction = reaction,
    )

    /** UIMessage → MessageEntity。 */
    private fun UIMessage.toEntity(sessionId: String): MessageEntity = MessageEntity(
        id = id.toString(),
        sessionId = sessionId,
        role = role.name,
        content = content,
        reasoning = reasoning,
        modelId = modelId,
        createdAt = createdAt,
        imageUrlsJson = encodeImageUrls(imageUrls),
        favorite = favorite,
        // v1.104 U7: 收藏分组标签往返(默认 NULL,未分组)
        favoriteTag = favoriteTag,
        citationUrlsJson = encodeImageUrls(citationUrls),
        // v1.133: RAG 引用列表持久化往返
        ragCitationsJson = encodeRagCitations(ragCitations),
        // Phase 8.6: 多模态图片 base64 列表
        // v1.134 P1-2: MessageImageStore 把大图片 base64 落盘到 filesDir/muse_images/,
        // DB 只存 "file://xxx" 路径,避免 messages 表行体积膨胀;
        // 短数据(< 1024 字符)仍存 base64,旧数据读取时无 "file://" 前缀直接返回
        imageBase64Json = encodeImageUrls(messageImageStore.toPersistable(id.toString(), imageBase64List)),
        // v1.43: Artifact id 列表
        artifactIdsJson = encodeImageUrls(artifactIds),
        // v1.103: MOOD / reflection 持久化(之前 toEntity 丢弃导致切页后消失)
        mood = mood,
        reflection = reflection,
        // 功能1: 消息表情回应往返
        reaction = reaction,
    )

    private fun encodeImageUrls(urls: List<String>): String =
        // v1.100: 空列表短路,避免周期性落盘(每 300 字符)时对 4 个空字段
        // 各做一次 JSON 序列化(虽然开销小,但高频累积)
        if (urls.isEmpty()) "[]" else runCatching { json.encodeToString(urlListSerializer, urls) }.getOrDefault("[]")

    private fun parseImageUrls(s: String): List<String> =
        runCatching { json.decodeFromString(urlListSerializer, s) }.getOrDefault(emptyList())

    /** v1.133: 序列化 RAG 引用列表(空列表短路,避免高频落盘开销)。 */
    private fun encodeRagCitations(citations: List<RagCitation>): String =
        if (citations.isEmpty()) "[]" else runCatching { json.encodeToString(ragCitationListSerializer, citations) }.getOrDefault("[]")

    private fun parseRagCitations(s: String): List<RagCitation> =
        runCatching { json.decodeFromString(ragCitationListSerializer, s) }.getOrDefault(emptyList())
}
