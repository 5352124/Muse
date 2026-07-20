package io.zer0.muse.ui.groupchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.schedule.GroupChatScheduler
import io.zer0.common.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 群聊 UI 状态。
 *
 * @param chats 群聊列表(按 updatedAt 降序)
 * @param currentChat 当前选中的群聊实体(null 表示未选中)
 * @param currentMessages 当前群聊的消息列表(按时间升序)
 * @param inputText 输入框文本
 * @param isAgentResponding 是否正在等待 Agent 轮转回复
 * @param assistants 全部助手列表(新建群聊成员选择用)
 * @param teams 全部协作团队(新建群聊关联团队用)
 */
/**
 * 群聊消息展开状态 — 用于切页后保持 mood / reasoning 折叠状态。
 */
data class GroupChatMessageExpandedState(
    val isMoodExpanded: Boolean? = null,
    val isReasoningExpanded: Boolean? = null,
)

data class GroupChatUiState(
    val chats: List<GroupChatEntity> = emptyList(),
    /** v1.72: 群聊列表首次加载标志(避免闪空状态) */
    val isChatsLoading: Boolean = true,
    val currentChat: GroupChatEntity? = null,
    val currentMessages: List<GroupChatMessageEntity> = emptyList(),
    val inputText: String = "",
    val pendingImages: List<String> = emptyList(),
    val isAgentResponding: Boolean = false,
    /** v1.104: 当前正在发言的 Agent(用于"谁在思考"指示),null=无人在发言 */
    val currentSpeaker: AssistantEntity? = null,
    val assistants: List<AssistantEntity> = emptyList(),
    val teams: List<AgentTeam> = emptyList(),
    /** v1.45: 聊天偏好(控制 mood/reasoning 显示与默认展开)。 */
    val chatPreferences: ChatPreferences = ChatPreferences(),
    /** v1.45: 列表滚动位置缓存(切页/后台后恢复)。 */
    val listFirstVisibleItemIndex: Int = 0,
    val listFirstVisibleItemScrollOffset: Int = 0,
    /** v1.45: 消息级展开状态缓存(mood/reasoning 折叠状态不因切页丢失)。 */
    val messageExpandedStates: Map<String, GroupChatMessageExpandedState> = emptyMap(),
    /** M7: 群聊删除标志,详情页据此自动返回。 */
    val chatDeleted: Boolean = false,
    /** v1.126: 操作错误提示(null=无错误) */
    val errorMessage: String? = null,
)

/**
 * 群聊 ViewModel — 管理群聊列表 / 消息流 / Agent 轮转调度。
 *
 * 职责:
 *  - 观察群聊列表(observeChats)与助手列表(AssistantRepository.observeAll)
 *  - 切换当前群聊时观察其消息流(observeMessages)
 *  - 用户发消息后调用 [GroupChatScheduler.triggerAgentRoundRobin] 串行触发各 Agent 发言
 *  - 创建 / 删除群聊
 *
 * @param groupChatRepository 群聊仓库
 * @param scheduler 群聊调度器(触发 Agent 轮转)
 * @param assistantRepository 助手仓库(加载成员候选列表)
 * @param settings 设置仓库(读取团队配置 + 用户名)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChatViewModel(
    private val groupChatRepository: GroupChatRepository,
    private val scheduler: GroupChatScheduler,
    private val assistantRepository: AssistantRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "GroupChatViewModel"
        /** v1.126: 发送消息最小间隔(ms),防止快速点击重复发送 */
        private const val SEND_DEBOUNCE_MS = 500L
    }

    private val _state = MutableStateFlow(GroupChatUiState())
    val state: StateFlow<GroupChatUiState> = _state.asStateFlow()

    /** v1.126: 上次发送时间戳,用于 debounce */
    private var lastSendTimestamp = 0L

    /** 当前选中的群聊 id(用于 flatMapLatest 切换消息流)。 */
    private val currentChatId = MutableStateFlow<String?>(null)

    init {
        // H-GC2 修复: 原先用 appScope.launch 收集 Flow,ViewModel 销毁后收集器永不取消,
        // 造成 Flow 泄漏与 StateFlow 旧订阅残留。改用 viewModelScope,随 ViewModel 清理自动取消。
        // 观察群聊列表
        viewModelScope.launch {
            groupChatRepository.observeChats().collect { chats ->
                _state.update { it.copy(chats = chats, isChatsLoading = false) }
            }
        }
        // 观察助手列表(新建群聊成员选择用)
        // v1.111: assistants 加载后同步更新 currentSpeaker(切页重建时 scheduler 可能已有活跃生成)
        viewModelScope.launch {
            assistantRepository.observeAll.collect { assistants ->
                val activeGen = scheduler.activeGroupGeneration.value
                val speaker = activeGen?.currentSpeakerId?.let { id ->
                    assistants.firstOrNull { it.id == id }
                }
                _state.update { it.copy(assistants = assistants, currentSpeaker = speaker) }
            }
        }
        // 观察当前群聊的消息流(随 currentChatId 切换)
        viewModelScope.launch {
            currentChatId.flatMapLatest { chatId ->
                if (chatId != null) {
                    groupChatRepository.observeMessages(chatId)
                } else {
                    flowOf(emptyList())
                }
            }.collect { messages ->
                _state.update { it.copy(currentMessages = messages) }
            }
        }
        // 观察当前群聊元数据(标题刷新用)
        viewModelScope.launch {
            currentChatId.flatMapLatest { chatId ->
                if (chatId != null) groupChatRepository.observeChat(chatId) else flowOf(null)
            }.collect { chat ->
                _state.update { it.copy(currentChat = chat) }
            }
        }
        // 加载团队列表(从内存缓存读,零阻塞)
        viewModelScope.launch {
            settings.multiAgentConfigFlow.collect { config ->
                _state.update { it.copy(teams = config.teams) }
            }
        }
        // 加载聊天偏好
        viewModelScope.launch {
            settings.chatPreferencesFlow.collect { prefs ->
                _state.update { it.copy(chatPreferences = prefs) }
            }
        }
        // v1.111: 订阅群聊生成状态(切页后重建 ViewModel 时恢复 isAgentResponding / currentSpeaker)
        viewModelScope.launch {
            scheduler.activeGroupGeneration.collect { gen ->
                if (gen == null) {
                    _state.update { it.copy(isAgentResponding = false, currentSpeaker = null) }
                } else if (gen.chatId == currentChatId.value) {
                    // 只在当前群聊匹配时显示生成状态(避免其他群聊的生成干扰当前页)
                    val speaker = gen.currentSpeakerId?.let { id ->
                        _state.value.assistants.firstOrNull { it.id == id }
                    }
                    _state.update {
                        it.copy(
                            isAgentResponding = gen.isResponding,
                            currentSpeaker = speaker,
                        )
                    }
                } else {
                    // 其他群聊在生成,当前页不显示
                    _state.update { it.copy(isAgentResponding = false, currentSpeaker = null) }
                }
            }
        }
    }

    /**
     * v1.45: 缓存列表滚动位置,切页/后台后恢复。
     */
    fun onListScrollPositionChanged(index: Int, offset: Int) {
        // L-GC6 修复: 用 _state.update {} 原子更新,替代 _state.value = _state.value.copy(...)
        // 避免读-改-写竞态(高频滚动时并发更新可能丢失中间状态)
        _state.update {
            it.copy(
                listFirstVisibleItemIndex = index,
                listFirstVisibleItemScrollOffset = offset,
            )
        }
    }

    /**
     * v1.45: 切换指定消息 mood 块的展开/折叠状态。
     */
    fun toggleMessageMoodExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: GroupChatMessageExpandedState()
            val default = current.chatPreferences.moodExpandedByDefault
            val newExpanded = !(currentState.isMoodExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isMoodExpanded = newExpanded)),
            )
        }
    }

    /**
     * v1.45: 切换指定消息 reasoning 块的展开/折叠状态。
     */
    fun toggleMessageReasoningExpanded(messageId: String) {
        _state.update { current ->
            val currentState = current.messageExpandedStates[messageId] ?: GroupChatMessageExpandedState()
            val default = current.chatPreferences.reasoningExpandedByDefault
            val newExpanded = !(currentState.isReasoningExpanded ?: default)
            current.copy(
                messageExpandedStates = current.messageExpandedStates +
                    (messageId to currentState.copy(isReasoningExpanded = newExpanded)),
            )
        }
    }

    /**
     * 选中群聊,切换消息流观察。
     *
     * @param chatId 群聊 id
     */
    fun selectChat(chatId: String) {
        currentChatId.value = chatId
    }

    /**
     * 更新输入框文本。
     */
    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * 添加待发送图片(base64 字符串)。
     */
    fun addPendingImage(base64Image: String) {
        _state.update { it.copy(pendingImages = it.pendingImages + base64Image) }
    }

    /**
     * 移除指定待发送图片。
     */
    fun removePendingImage(base64Image: String) {
        _state.update { it.copy(pendingImages = it.pendingImages - base64Image) }
    }

    /**
     * 清空所有待发送图片。
     */
    fun clearPendingImages() {
        _state.update { it.copy(pendingImages = emptyList()) }
    }

    /**
     * 发送用户消息并触发 Agent 轮转回复。
     *
     * v1.111: 轮转运行在 [GroupChatScheduler.launchRoundRobin] 的 appScope 中,
     * 切页/后台不中断。ViewModel 只负责前置检查 + 清空输入框 + 委托给 scheduler。
     *
     * @param text 消息正文
     */
    fun sendMessage(text: String) {
        val chatId = currentChatId.value ?: return
        if (text.isBlank() && _state.value.pendingImages.isEmpty()) return
        // v1.126: debounce — 防止快速点击重复发送
        val now = System.currentTimeMillis()
        if (now - lastSendTimestamp < SEND_DEBOUNCE_MS) return
        lastSendTimestamp = now
        // v1.111: 用 scheduler 的全局状态检查(替代 _state.isAgentResponding,切页后 _state 会重置)
        if (scheduler.hasActiveGeneration(chatId)) return

        val images = _state.value.pendingImages
        // 立即清空输入框(常见交互:点发送后输入框立即清空)
        _state.update { it.copy(inputText = "", pendingImages = emptyList()) }
        // v1.111: 委托给 scheduler,轮转运行在 appScope
        scheduler.launchRoundRobin(chatId, text, images)
    }

    /**
     * v1.111: 手动停止当前群聊生成。
     */
    fun stopGeneration() {
        val chatId = currentChatId.value ?: return
        scheduler.stop(chatId)
    }

    /**
     * 创建新群聊。
     *
     * @param name 群聊名称
     * @param memberIds 成员助手 id 列表
     * @param teamId 可选关联团队 id
     * @return 新创建的群聊 id
     */
    suspend fun createChat(name: String, memberIds: List<String>, teamId: String? = null): String {
        return groupChatRepository.createChat(name, memberIds, teamId)
    }

    /**
     * v1.126: 清空错误提示。
     */
    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * 删除群聊(级联删除其全部消息)。
     *
     * @param chatId 群聊 id
     */
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.deleteChat(chatId)
                // 若删除的是当前群聊,清空选中状态并通知详情页返回
                if (currentChatId.value == chatId) {
                    currentChatId.value = null
                    // M7: 设置删除标志,详情页据此自动返回
                    _state.update { it.copy(chatDeleted = true) }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "删除群聊失败", e)
                _state.update { it.copy(errorMessage = "删除群聊失败") }
            }
        }
    }

    /**
     * v1.77: 删除群聊中的单条消息。
     *
     * @param messageId 消息 id
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.deleteMessage(messageId)
            } catch (e: Exception) {
                Logger.e(TAG, "删除消息失败", e)
                _state.update { it.copy(errorMessage = "删除消息失败") }
            }
        }
    }

    /**
     * 切换群聊置顶状态。
     *
     * @param chatId 群聊 id
     */
    fun togglePin(chatId: String) {
        viewModelScope.launch {
            try {
                groupChatRepository.togglePin(chatId)
            } catch (e: Exception) {
                Logger.e(TAG, "切换置顶失败", e)
                _state.update { it.copy(errorMessage = "操作失败") }
            }
        }
    }

    /**
     * v1.97: 更新群聊信息(名称/描述/成员)。
     *
     * @param chatId 群聊 id
     * @param name 新名称(null 表示不改)
     * @param description 新描述(null 表示不改)
     * @param memberIds 新成员列表(null 表示不改)
     */
    fun updateChat(
        chatId: String,
        name: String? = null,
        description: String? = null,
        memberIds: List<String>? = null,
    ) {
        viewModelScope.launch {
            try {
                groupChatRepository.updateChat(chatId, name, description, memberIds)
            } catch (e: Exception) {
                Logger.e(TAG, "更新群聊失败", e)
                _state.update { it.copy(errorMessage = "更新群聊失败") }
            }
        }
    }

    /**
     * 解析群聊成员 id 列表。
     */
    fun parseMemberIds(chat: GroupChatEntity): List<String> =
        groupChatRepository.parseMemberIds(chat)

    /**
     * 取指定群聊的最新一条消息(用于列表页预览)。
     */
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity? =
        groupChatRepository.getLatestMessage(chatId)
}
