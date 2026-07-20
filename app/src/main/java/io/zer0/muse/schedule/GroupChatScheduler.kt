package io.zer0.muse.schedule

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.util.MusePatterns
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.transformer.SystemPromptAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群聊调度器 — 用户发消息后串行触发群聊中的 Agent 轮转发言。
 *
 * 设计参考 OpenHanako 的多 Agent 群聊模型:
 *  1. 用户在群聊中发送一条消息
 *  2. 调度器取出群聊的 memberIds,对每个 assistant 串行执行:
 *     a. 构造上下文(最近 N 条消息 + 群聊身份提示)
 *     b. 调 [ChatService.streamChat] 流式调用(60s 超时,累积 ContentDelta)
 *     c. LLM 返回的文本就是该 agent 的发言
 *     d. 调 [GroupChatRepository.sendMessage] 保存 agent 回复
 *     e. 如果 LLM 返回 "[PASS]" 或空文本,跳过该 agent
 *  3. 返回本轮所有 agent 的回复列表
 *
 * v1.134 P1-4: 改为 streamChat 流式调用,消除 completeText 的 60s 整包阻塞。
 *
 * 错误处理:
 *  - 群聊不存在时返回空列表
 *  - Agent 不存在时跳过(记录日志)
 *  - LLM 调用失败时记录日志并跳过该 agent
 *  - 超时(60s)时跳过该 agent
 *
 * @param groupChatRepository 群聊仓库(读取群聊/消息、保存 agent 回复)
 * @param assistantRepository 取 assistant 配置(systemPrompt / temperature / maxTokens)
 * @param chatService 流式调用 LLM(v1.134 P1-4 改为 streamChat)
 * @param settings 读取当前选中的 Model(activeProvider)
 * @param appScope v1.111: 应用级协程,群聊轮转运行于此(切页/后台不中断)
 * @param appContext v1.111: 启动/停止前台服务
 * @param chatGenerationManager v1.111: 复用单聊的保活机制(前台服务通知/心跳/状态)
 */
class GroupChatScheduler(
    private val groupChatRepository: GroupChatRepository,
    private val assistantRepository: AssistantRepository,
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val appContext: Context,
    private val chatGenerationManager: ChatGenerationManager,
) {

    /**
     * v1.111: 群聊活跃生成状态(供 ViewModel 订阅,切页后恢复 UI)。
     *
     * 与 [ChatGenerationManager.ActiveGeneration] 的区别:
     *  - ActiveGeneration 是单聊语义(assistantId = 占位消息 id),群聊用 "group" 占位
     *  - 本类额外暴露 currentSpeakerId/Name,供 UI 显示"谁在思考"
     */
    data class ActiveGroupGeneration(
        val chatId: String,
        val chatName: String,
        val isResponding: Boolean = true,
        val currentSpeakerId: String? = null,
        val currentSpeakerName: String? = null,
        val lastUpdatedAt: Long = System.currentTimeMillis(),
    )

    private val _activeGroupGeneration = MutableStateFlow<ActiveGroupGeneration?>(null)
    val activeGroupGeneration: StateFlow<ActiveGroupGeneration?> = _activeGroupGeneration.asStateFlow()

    /** v1.111: 是否有指定群聊的活跃生成(用于防重入)。v1.113: 改为按 chatId 精确检查。 */
    fun hasActiveGeneration(chatId: String): Boolean =
        chatGenerationManager.isStreaming("group:$chatId")

    companion object {
        private const val TAG = "GroupChatScheduler"
        /** 单个 agent 的 LLM 调用超时(毫秒)。 */
        private const val AGENT_TIMEOUT_MS = 60_000L
        /** 默认上下文消息条数。 */
        private const val DEFAULT_CONTEXT_SIZE = 20
        /** LLM 返回此标记表示跳过本轮发言。 */
        private const val PASS_MARKER = "[PASS]"
        /** 默认采样温度。 */
        private const val DEFAULT_TEMPERATURE = 0.7f
        /** 默认最大 token 数。 */
        private const val DEFAULT_MAX_TOKENS = 1000
        /** v1.97: 单成员最大调用次数(含决策修复重试),防死循环。 */
        private const val MAX_INVOCATIONS_PER_MEMBER = 2
        /** v1.97: @mention 正则 — 匹配 @name 形式,name 为非空白非标点字符序列。 */
        private val MENTION_REGEX = Regex("@([^\\s@,，。.!！?？:：;；()（）\\[\\]【】]+)")

        /**
         * 群聊 mood 格式要求 — 让 Agent 在回复前先输出内部腹稿。
         *
         * 与单聊[SystemPromptAssembler.MOOD_FORMAT_SECTION]保持一致,
         * UI 会自动剥离 <mood> 和 <think> 块并支持展开/折叠。
         */
        private val GROUP_CHAT_MOOD_SECTION = """
            MOOD 格式要求(每次回复必须遵守):
            每次回复前必须先写一个 <mood>...</mood> 块作为内部腹稿,然后再写正文。
            MOOD 块格式如下(4 个字段,每字段一行,内容简短):

            <mood>
            Vibe: <用户当前心情状态,1 句>
            Sparks: <这个问题触发什么联想,1 句>
            Reflections: <不确定的点或可能的坑,1 句>
            Will: <想怎么推进这段对话,1 句>
            </mood>

            正文(直接跟在 </mood> 后,不要空行)

            规则:
            - MOOD 是你的思考热身,不展示给用户看(系统会自动剥离)
            - 4 个字段都要写,哪怕一句话
            - 写完 MOOD 再写正文,正文遵守输出风格约束
            - 不要在正文里重复 MOOD 的内容
            - 如需展示深度推理,可在正文前写 <think>...</think> 块,系统同样会剥离并支持折叠展示
        """.trimIndent()
    }

    /**
     * v1.111: 在应用级协程中启动群聊轮转(切页/后台不中断)。
     *
     * 复用 [ChatGenerationManager] 的保活机制 + [ChatGenerationService] 前台服务。
     * 生成运行在 appScope,不依赖 ViewModel 生命周期。
     *
     * 流程:
     *  1. 通过 [ChatGenerationManager.launchGeneration] 在 appScope 启动(自动取消旧生成)
     *  2. 异步读取用户名 + 群聊名,更新通知标题
     *  3. 启动前台服务
     *  4. 保存用户消息到 DB
     *  5. 调 [triggerAgentRoundRobin] 串行触发各 Agent 发言
     *  6. finally 停止前台服务 + 清空活跃状态
     *
     * @param chatId 群聊 id
     * @param text 用户消息正文
     * @param images 待发送图片(base64 列表,可为空)
     */
    fun launchRoundRobin(chatId: String, text: String, images: List<String>) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:$chatId",
            assistantId = "group",
            sessionTitle = "群聊生成中",
        ) {
            try {
                // 1. 读取用户名 + 群聊信息
                val userName = resultOf { settings.accountStateFlow.first().userName }
                    .getOrNull()?.ifBlank { "我" } ?: "我"
                val chat = groupChatRepository.getChat(chatId)
                val chatName = chat?.name ?: "群聊"
                chatGenerationManager.updateSessionTitle(chatName)

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chatName,
                    isResponding = true,
                )

                // 2. 启动前台服务(切后台保活)
                runCatching { ChatGenerationService.start(appContext) }
                    .onFailure { Logger.w(TAG, "群聊前台服务启动失败", it) }

                // 3. 保存用户消息
                val imageBase64Json = AppJson.encodeToString(
                    ListSerializer(String.serializer()),
                    images,
                )
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "local_user",
                    senderName = userName,
                    body = text,
                    imageBase64Json = imageBase64Json,
                )

                // 4. 触发 Agent 轮转
                val replies = triggerAgentRoundRobin(
                    chatId,
                    onSpeakerChange = { speaker ->
                        _activeGroupGeneration.update {
                            it?.copy(
                                currentSpeakerId = speaker.id,
                                currentSpeakerName = speaker.name,
                            )
                        }
                    },
                )

                if (replies.isEmpty()) {
                    Logger.i(TAG, "群聊「$chatName」本轮所有助手未发言")
                }
            } catch (ce: CancellationException) {
                Logger.i(TAG, "群聊 $chatId 轮转被取消(用户停止/新生成抢占)")
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "群聊 $chatId 轮转失败", t)
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }

    /**
     * v1.111: 用户手动停止群聊生成。
     *
     * 取消 chatGenerationManager 的 streamJob(触发 block 的 CancellationException → finally 清理)。
     * v1.113: 只停止群聊的生成(sessionId="group:$chatId"),不影响单聊。
     */
    fun stop(chatId: String? = null) {
        if (chatId != null) {
            chatGenerationManager.stop("group:$chatId")
        } else {
            // 兼容旧调用:无 chatId 时取消所有群聊生成
            chatGenerationManager.stop(null)
        }
        _activeGroupGeneration.value = null
        runCatching { ChatGenerationService.stop(appContext) }
    }

     /**
      * 触发群聊 Agent 轮转发言。
      *
      * v1.97 改进(参考 openhanako-orig):
      *  - @mention 解析:从最近用户消息中提取 @agentName,被提及的 agent 优先发言
      *  - 决策修复:被 @提及的 agent 如果返回 [PASS],重试一次提示"你被@提及了"
      *  - Guard limit:单成员最多 MAX_INVOCATIONS_PER_MEMBER 次调用,防死循环
      *
      * @param chatId 群聊 id
      * @return 本轮所有 agent 的回复列表(已保存到 DB)
      */
    suspend fun triggerAgentRoundRobin(
        chatId: String,
        /** v1.104: 每个 agent 开始发言时回调(用于 UI 显示"谁在思考") */
        onSpeakerChange: ((AssistantEntity) -> Unit)? = null,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        // 1. 取群聊配置
        val chat = groupChatRepository.getChat(chatId)
        if (chat == null) {
            Logger.w(TAG, "群聊 $chatId 不存在,跳过轮转")
            return@withContext emptyList()
        }

        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "群聊「${chat.name}」无成员,跳过轮转")
            return@withContext emptyList()
        }

        // 2. 解析成员显示名(用于群聊提示)
        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
                ?: run { Logger.w(TAG, "Agent $id 不存在,跳过"); null }
        }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "群聊「${chat.name}」无有效成员,跳过轮转")
            return@withContext emptyList()
        }
        val memberNames = assistants.map { it.name }

        // 3. 获取当前选中的 Model(供 LLM 调用使用)
        val model = resultOf { settings.getSelectedModel() }.getOrNull()

        // v1.97: 4. 解析 @mention — 从最近用户消息中提取被提及的 agent
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)
        if (mentionedAgentIds.isNotEmpty()) {
            Logger.i(TAG, "群聊「${chat.name}」@提及: ${mentionedAgentIds.joinToString(", ")}")
        }

        // v1.97: 5. 重排 agent 顺序 — 被提及的优先,然后是其他成员
        val orderedAssistants = assistants.sortedByDescending { it.id in mentionedAgentIds }

        val replies = mutableListOf<GroupChatMessageEntity>()

        // 6. 串行触发每个 agent(被提及的有决策修复)
        for (assistant in orderedAssistants) {
            val isMentioned = assistant.id in mentionedAgentIds
            // v1.104: 通知 UI 当前轮到谁发言
            onSpeakerChange?.invoke(assistant)
            val reply = invokeAgent(chat, chatId, assistant, memberNames, model, isMentioned)

            // v1.97: 决策修复 — 被提及的 agent 如果 PASS,重试一次
            if (reply == null && isMentioned) {
                Logger.i(TAG, "Agent「${assistant.name}」被@提及但 PASS,决策修复重试")
                val retryReply = invokeAgent(chat, chatId, assistant, memberNames, model, isMentioned = true, isRepair = true)
                if (retryReply != null) {
                    replies.add(retryReply)
                }
            } else if (reply != null) {
                replies.add(reply)
            }
        }

        Logger.i(TAG, "群聊「${chat.name}」本轮轮转完成,${replies.size}/${assistants.size} 个 agent 发言")
        replies
    }

    /**
     * v1.97: 从最近消息中解析 @mention,返回被提及的 assistant id 列表。
     *
     * 匹配规则:在最近用户消息中查找 @name,name 与 assistant.name 或 assistant.id 匹配。
     * 参考 openhanako-orig 的 channel-mentions.ts:支持中英文标点边界,按名称长度降序匹配。
     */
    private fun parseMentions(
        recentMessages: List<GroupChatMessageEntity>,
        assistants: List<AssistantEntity>,
    ): Set<String> {
        // 取最近一条用户消息
        val lastUserMsg = recentMessages.lastOrNull { it.senderType == "user" } ?: return emptySet()
        val text = lastUserMsg.body
        if (!text.contains("@")) return emptySet()

        // 提取所有 @token
        val tokens = MENTION_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) return emptySet()

        // 与 assistant name/id 匹配(忽略大小写)
        // v1.116 (C2-1): 按 name 长度降序排序后再匹配,避免短名称误匹配长名称的前缀。
        // 例:群内有 "Alice" 和 "Alice2" 时,@Alice2 应匹配后者而非前者。
        // 当前用 equals 精确匹配,排序不影响结果,但为未来扩展(别名/部分匹配)打好基础,
        // 且符合 openhanako channel-mentions.ts 的设计意图。
        val sortedAssistants = assistants.sortedByDescending { it.name.length }
        val mentioned = mutableSetOf<String>()
        for (token in tokens) {
            val matched = sortedAssistants.firstOrNull { a ->
                a.name.equals(token, ignoreCase = true) || a.id.equals(token, ignoreCase = true)
            }
            if (matched != null) {
                mentioned.add(matched.id)
            }
        }
        return mentioned
    }

    /**
     * 调用单个 agent 生成发言。
     *
     * v1.97: 新增 isMentioned / isRepair 参数,支持 @mention 提示和决策修复。
     *
     * @param chat 群聊实体
     * @param chatId 群聊 id
     * @param assistant 当前 agent 配置
     * @param memberNames 群聊所有成员显示名
     * @param model 当前选中的 Model(null 时由 ChatService 用默认)
     * @param isMentioned v1.97: 是否被 @提及(影响 prompt 提示)
     * @param isRepair v1.97: 是否为决策修复重试(提示 agent 上一轮没有回复)
     * @return 保存的 agent 回复消息(null 表示跳过)
     */
    private suspend fun invokeAgent(
        chat: io.zer0.muse.data.groupchat.GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        model: io.zer0.ai.core.Model?,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
    ): GroupChatMessageEntity? {
        // a. 构造上下文
        val contextSize = assistant.contextMessageSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT_SIZE
        val recentMessages = groupChatRepository.getRecentMessages(chatId, contextSize)

        // b. 构造消息列表: system + user(含 @mention / 决策修复提示)
        val messages = buildMessages(chat.name, assistant, memberNames, recentMessages, model, isMentioned, isRepair)

        // c. 调 LLM(流式,60s 超时)
        // v1.134 P1-4: 改为 streamChat 累积 ContentDelta,消除 completeText 的整包阻塞。
        // 流式优势:首 token 即建立连接,后续增量返回;长思考模型不再被 60s 整包超时误杀。
        // UI 仅依赖 currentSpeakerId 显示"谁在思考",不订阅增量文本,因此这里只累积成完整字符串。
        val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
        val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS
        val rawReplyText = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                val builder = StringBuilder()
                var streamError: String? = null
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    temperature = temperature,
                    maxTokens = maxTokens,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                        is ChatStreamEvent.ReasoningDelta -> { /* 思考增量不入正文,与单聊保持一致 */ }
                        is ChatStreamEvent.ImageDelta -> { /* 群聊暂不支持图片输出,忽略 */ }
                        is ChatStreamEvent.ToolCallDelta -> { /* 群聊不传 tools,忽略 */ }
                        is ChatStreamEvent.Done -> { /* 流结束 */ }
                        is ChatStreamEvent.Error -> streamError = event.message
                    }
                }
                if (streamError != null) {
                    throw IllegalStateException(streamError)
                }
                builder.toString().trim()
            }
        }.onError { msg, t ->
            Logger.e(TAG, "Agent「${assistant.name}」LLM 调用失败: $msg", t)
            return null
        }.getOrNull()

        if (rawReplyText == null) {
            Logger.w(TAG, "Agent「${assistant.name}」调用超时(${AGENT_TIMEOUT_MS / 1000}s),跳过")
            return null
        }

        // e. 提取 mood / think 模块,再清理 channel_reply 包装
        val extractedMood = extractMood(rawReplyText)
        val extractedReasoning = extractReasoning(rawReplyText)
        val replyText = sanitizeAgentReply(rawReplyText)

        // f. 检查是否跳过([PASS] 或空文本)
        if (replyText.isBlank() || replyText == PASS_MARKER) {
            Logger.i(TAG, "Agent「${assistant.name}」选择跳过本轮(PASS)")
            return null
        }

        // g. 保存 agent 回复到群聊
        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistant.id,
            senderName = assistant.name,
            body = replyText,
            mood = extractedMood,
            reasoning = extractedReasoning,
        )

        Logger.i(TAG, "Agent「${assistant.name}」在群聊「${chat.name}」中发言")
        return GroupChatMessageEntity(
            id = msgId,
            chatId = chatId,
            senderType = "assistant",
            senderId = assistant.id,
            senderName = assistant.name,
            body = replyText,
            timestamp = System.currentTimeMillis(),
            mood = extractedMood,
            reasoning = extractedReasoning,
        )
    }

    /**
     * 构造发给 LLM 的消息列表。
     *
     * - System: assistant.systemPrompt + 群聊身份提示
     * - User: 群聊最近消息 transcript + 发言引导
     *
     * v1.97: 新增 isMentioned / isRepair 参数,在 user message 中注入 @提及和决策修复提示。
     *
     * @param chatName 群聊名称
     * @param assistant 当前 agent 配置
     * @param memberNames 群聊所有成员显示名
     * @param recentMessages 最近消息列表(按时间升序)
     * @param model 当前选中的模型(null 时无法判断视觉能力,保守不附加图片)
     * @param isMentioned 是否被 @提及
     * @param isRepair 是否为决策修复重试
     * @return UIMessage 列表
     */
    private fun buildMessages(
        chatName: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
        model: io.zer0.ai.core.Model?,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
    ): List<UIMessage> {
        val messages = mutableListOf<UIMessage>()

        // System message: assistant.systemPrompt + 群聊提示
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(
                SystemPromptAssembler.buildGroupChatHintSection(
                    chatName = chatName,
                    members = memberNames,
                    currentAgentName = assistant.name,
                )
            )
            appendLine()
            appendLine(GROUP_CHAT_MOOD_SECTION)
            // M5: 明确告知 Agent 不要输出 channel_* 工具调用文本(Prompt 中声称有 channel_* 工具但未注册)
            appendLine()
            appendLine("如需发言直接回复内容;如无需发言只回复 [PASS]。不要输出 channel_pass/channel_reply 等工具调用文本。")
        }
        messages.add(UIMessage(role = MessageRole.SYSTEM, content = systemContent))

        // User message: 群聊最近消息 transcript + 发言引导
        val userContent = buildString {
            if (recentMessages.isNotEmpty()) {
                appendLine("以下是群聊「$chatName」的最近对话:")
                appendLine()
                appendLine(formatMessageTranscript(recentMessages))
                appendLine()
            }
            appendLine("请以「${assistant.name}」的身份参与群聊,回复用户或回应其他成员。")
            // v1.97: @提及提示
            if (isMentioned) {
                appendLine("用户在消息中@了你,请务必回复。")
            }
            // v1.97: 决策修复提示
            if (isRepair) {
                appendLine("上一轮你没有回复,但你被@提及了。请回应这条消息。")
            }
            appendLine("如果当前话题不需要你发言,只回复 [PASS]。")
        }
        // v1.136: 把最近一条用户消息的图片作为多模态输入传给模型,
        // 避免群聊图片只存不看不回复的问题。
        // 仅当模型明确支持视觉时才附加图片,避免向纯文本模型发图导致 400。
        val latestUserImages = if (model != null && model.supportsVisionInput()) {
            recentMessages.lastOrNull { it.senderType == "user" }
                ?.let { msg ->
                    resultOf {
                        AppJson.decodeFromString(ListSerializer(String.serializer()), msg.imageBase64Json)
                    }.getOrNull()?.map { stripDataUriPrefix(it) }?.filter { it.isNotEmpty() }
                } ?: emptyList()
        } else emptyList()
        messages.add(
            UIMessage(
                role = MessageRole.USER,
                content = userContent,
                imageBase64List = latestUserImages,
            ),
        )

        return messages
    }

    /**
     * v1.136: 去除 data URI 前缀,返回纯 base64 字符串。
     * 群聊中图片以 "data:image/jpeg;base64,..." 形式存储,而 OpenAIProvider 期望纯 base64。
     */
    private fun stripDataUriPrefix(dataUri: String): String {
        val commaIndex = dataUri.indexOf(',')
        return if (commaIndex >= 0 && dataUri.startsWith("data:", ignoreCase = true)) {
            dataUri.substring(commaIndex + 1)
        } else {
            dataUri
        }
    }

    /**
     * 把最近消息格式化为文本 transcript。
     *
     * 格式:
     * ```
     * [发送者名 | MM-dd HH:mm] 内容
     * [发送者名 | MM-dd HH:mm] 内容
     * ```
     *
     * 若消息附带图片,则在内容后标注 `[图片xN]`。
     */
    private fun formatMessageTranscript(messages: List<GroupChatMessageEntity>): String {
        val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (msg in messages) {
            val timeStr = timeFormatter.format(Date(msg.timestamp))
            val imageCount = resultOf {
                AppJson.decodeFromString(ListSerializer(String.serializer()), msg.imageBase64Json).size
            }.getOrNull() ?: 0
            val imageHint = if (imageCount > 0) " [图片x$imageCount]" else ""
            sb.appendLine("[${msg.senderName} | $timeStr] ${msg.body}$imageHint")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 清理 Agent 回复中不应展示给用户的包装标记。
     *
     * - 剥离 `<mood>...</mood>` 情绪模块
     * - 剥离 `<think>...</think>` 思考模块
     * - 剥离 `[channel_reply]...[/channel_reply]` 工具调用包装,只保留内部正文
     */
    private fun sanitizeAgentReply(text: String): String {
        // M9 已知限制: 此正则会剥离所有 <mood>/<think> 块,包括 LLM 代码示例中的标签。
        // 短期可接受,长期建议用更明确的分隔符。
        val withoutMood = text.replace(MusePatterns.MOOD_TAG_REGEX, "")
        val afterThinkReplace = withoutMood.replace(MusePatterns.THINK_TAG_REGEX, "")
        // L9: 处理未闭合的 <think> 标签 — 若仍有 <think> 残留(无对应 </think>),则从 <think> 截断到末尾
        val thinkIdx = afterThinkReplace.indexOf("<think>", ignoreCase = true)
        val withoutThink = if (thinkIdx >= 0) afterThinkReplace.substring(0, thinkIdx) else afterThinkReplace
        val withoutChannelReply = withoutThink.replace(
            // L6: 加 RegexOption.IGNORE_CASE 忽略大小写
            Regex("\\[channel_reply\\](.*?)\\[/channel_reply\\]", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { it.groupValues[1].trim() }
        return withoutChannelReply.trim()
    }

    /**
     * 从原始回复中提取 <mood>...</mood> 块内容。
     */
    private fun extractMood(text: String): String? {
        val sb = StringBuilder()
        var match = MusePatterns.MOOD_TAG_REGEX.find(text)
        while (match != null) {
            sb.appendLine(match.groupValues[1].trim())
            match = match.next()
        }
        return sb.toString().trim().ifBlank { null }
    }

    /**
     * 从原始回复中提取 <think>...</think> 块内容。
     */
    private fun extractReasoning(text: String): String? {
        val sb = StringBuilder()
        var match = MusePatterns.THINK_TAG_REGEX.find(text)
        while (match != null) {
            sb.appendLine(match.groupValues[1].trim())
            match = match.next()
        }
        return sb.toString().trim().ifBlank { null }
    }
}
