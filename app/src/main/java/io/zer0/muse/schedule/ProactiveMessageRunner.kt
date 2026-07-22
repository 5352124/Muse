package io.zer0.muse.schedule

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.ProactiveScoreEngine
import io.zer0.muse.data.proactive.ScoreContext
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 主动消息调度器(虚拟陪伴助手用)。
 *
 * 让助手像真人一样定时主动给用户发消息并弹通知,模拟"对方先找你聊天"的体验。
 * App 启动时调用 [start] 进入轮询,每 60 秒检查一次是否到达触发间隔;
 * 到期则调用 LLM 生成一条主动消息,写入当前会话并弹通知。
 *
 * 设计要点(参考 [ScheduledTaskRunner] 的轮询结构):
 *  - 用协程轮询而非 AlarmManager,避免 Android 后台限制
 *  - 单 Job 控制生命周期,[stop] 取消即可
 *  - [lastTriggeredAt] 持久化在 DataStore,App 重启后不会立即重发
 *  - 任一环节失败(LLM 调用 / 写库 / 通知)都不更新 lastTriggeredAt,下个 tick 会重试
 *
 * @param settings 读取/保存 [io.zer0.muse.data.ProactiveMessageConfig]
 * @param chatService 非流式调用 LLM 生成主动消息内容
 * @param sessionRepository 把生成的消息追加进当前会话
 * @param assistantRepository 取当前助手(人设 + 名字)
 * @param notificationManager 弹"主动消息"通知
 * @param context Application Context
 * @param appScope App 全局协程作用域
 */
class ProactiveMessageRunner(
    private val settings: SettingsRepository,
    private val chatService: ChatService,
    private val sessionRepository: SessionRepository,
    private val assistantRepository: AssistantRepository,
    private val notificationManager: MuseNotificationManager,
    private val scoreEngine: ProactiveScoreEngine,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    /** v0.44: 解析 LLM 决策 JSON(忽略未知字段,兼容模型多返回字段的情况)。 */
    private val decisionJson = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "ProactiveMessageRunner started")
            while (isActive) {
                try {
                    checkAndTrigger()
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Logger.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * v1.134: 供 [ProactiveMessageWorker] 调用的一次性检查入口(Worker 进程被系统拉起时使用)。
     *
     * 与协程轮询共用 [checkAndTrigger] 实现。Worker 进程内 Koin 已初始化(MuseApp.onCreate
     * 已执行),依赖解析正常。
     *
     * v1.134 P2-2: 冷启动防打扰 — 若距上次触发已超过 2 倍 interval,说明 App 长时间未运行,
     * 此时不应立即发送主动消息(可能打扰用户),仅更新 lastTriggeredAt 为当前时间,
     * 下次正常 interval 后再发送。时间窗口检查仍然生效。
     */
    suspend fun tickOnceForWorker() {
        checkAndTrigger(suppressIfColdStart = true)
    }

    private suspend fun checkAndTrigger(suppressIfColdStart: Boolean = false) {
        val config = settings.proactiveMessageConfigFlow.first()
        if (!config.enabled) return

        // v1.95: 时间窗口检查 — 不在允许时段跳过发送(避免夜间打扰)
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val inWindow = if (config.allowedHourStart <= config.allowedHourEnd) {
            // 普通时段:如 8-22
            currentHour in config.allowedHourStart until config.allowedHourEnd
        } else {
            // 跨夜时段:如 22-8(22点到次日8点)
            currentHour >= config.allowedHourStart || currentHour < config.allowedHourEnd
        }
        if (!inWindow) {
            Logger.i(TAG, "当前 $currentHour:00 不在允许时段 ${config.allowedHourStart}:00-${config.allowedHourEnd}:00,跳过")
            return
        }

        val now = System.currentTimeMillis()
        // v1.27: 实际间隔 = intervalHours ± randomOffsetHours(下限 1 小时,避免过于频繁)
        val effectiveIntervalMs = computeEffectiveIntervalMs(config)
        val elapsed = now - config.lastTriggeredAt
        if (elapsed < effectiveIntervalMs) return

        // v1.134 P2-2: 冷启动防打扰 — Worker 路径检测到长时间未触发(> 2× interval)
        // 时,不立即发送,仅更新 lastTriggeredAt 到当前时间,等下个 interval 再发。
        // 协程轮询路径不抑制(用户刚打开 App,可能希望立即看到陪伴消息)。
        if (suppressIfColdStart && config.lastTriggeredAt > 0 && elapsed > effectiveIntervalMs * 2) {
            Logger.i(TAG, "冷启动检测:距上次触发 ${elapsed / 3600000}h,推迟到下个 interval 再发(避免打扰)")
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            return
        }

        // 获取指定 Agent 助手(v1.27):优先用 config.agentId,否则 default,再否则第一个
        val assistants = assistantRepository.observeAll.first()
        val assistant = assistants.firstOrNull { it.id == config.agentId.takeIf { id -> id.isNotBlank() } }
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: return

        // 取会话作为"当前会话"
        val sessions = sessionRepository.observeSessions().first()
        // v1.95: 仅Agent会话可发主动消息(agentOnly=true时)
        // observeSessions() 只返回任务会话,Agent Tab 会话需单独取(isAgentSession=1);
        // 无 Agent 会话时回退到第一个任务会话(向后兼容)
        val targetSession = if (config.agentOnly) {
            sessionRepository.getLatestAgentSession() ?: sessions.firstOrNull()
        } else {
            sessions.firstOrNull()
        } ?: return

        // v0.44: 取最近 10 条消息,过滤出最近 5 条 user/assistant 消息作为上下文
        val allMessages = resultOf {
            sessionRepository.observeMessages(targetSession.id).first()
        }.getOrNull() ?: emptyList()
        val recentMessages = allMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(5)

        // v1.0.4: ProactiveScoreEngine 预筛选 — 评分低于阈值直接跳过 LLM 调用(节省 token)
        // 评分综合:时间衰减 × 沉默期 × 情绪 × 新鲜度;shouldSend 还会检查静默时段 + 每日上限
        val scoreCtx = ScoreContext(
            hoursSinceLastMessage = (elapsed / 3_600_000f).coerceAtLeast(0f),
            accountAgeDays = 7, // 简化:暂用默认值,后续可从账户首次启动时间计算
            todaySentCount = 0, // 简化:暂不持久化每日计数
            hasNewMemories = recentMessages.isNotEmpty(),
            hasNewTopics = recentMessages.isNotEmpty(),
        )
        if (!scoreEngine.shouldSend(scoreCtx)) {
            Logger.i(TAG, "ScoreEngine 预筛选未通过,跳过 LLM 调用")
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            return
        }

        // 构造让 LLM 决定是否发主动消息的 prompt 并调用 LLM
        val promptMessages = buildDecisionPrompt(assistant, recentMessages)
        val completion = resultOf {
            withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = promptMessages,
                    temperature = 0.8f,
                    // 决策 JSON(shouldSend+content+reason)需完整返回,content 上限 80 字;
                    // 部分模型对中文 token 化较稀疏(~2 tokens/字),150 可能截断 JSON 致
                    // parseDecision 失败(默认 shouldSend=false,主动消息功能静默失效)。
                    // 已偏离约束 150,待对齐。
                    maxTokens = DECISION_MAX_TOKENS,
                )
            }
        }.onError { msg, t ->
            Logger.w(TAG, "主动消息 LLM 调用失败: ${t?.message ?: msg}")
            return
        }.getOrNull()
        if (completion == null) {
            Logger.w(TAG, "主动消息 LLM 调用超时(${LLM_TIMEOUT_MS / 1000}s),跳过")
            return
        }

        // 解析 LLM 返回的 JSON 决策
        val decision = parseDecision(completion.text)

        if (decision.shouldSend && decision.content.isNotBlank()) {
            val proactiveContent = decision.content.trim()
            // 插入会话作为 assistant 消息
            sessionRepository.appendMessage(
                sessionId = targetSession.id,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = proactiveContent,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            // 更新 lastTriggeredAt(先更新再通知,即使通知失败也不影响下次间隔)
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            // 弹通知(像微信来消息一样,通知栏用助手头像)
            notificationManager.notifyProactiveMessage(assistant, proactiveContent)
            Logger.i(TAG, "Proactive message sent to session ${targetSession.id}, reason=${decision.reason}")
        } else {
            // shouldSend=false 也更新 lastTriggeredAt,避免频繁打扰 + 浪费 token
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            Logger.i(TAG, "Proactive message skipped (shouldSend=false), reason=${decision.reason}")
        }
    }

    /**
     * v0.44: 解析 LLM 返回的决策 JSON。
     *
     * LLM 返回可能被 markdown 代码块包裹(```json ... ```),先剥离再解析。
     * 解析失败时返回 shouldSend=false 的默认决策,避免误打扰用户。
     */
    private fun parseDecision(raw: String): ProactiveDecision {
        // 剥离 markdown 代码块包裹,取首个 { 到末尾 } 之间的 JSON 文本
        val cleaned = raw.trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val jsonText = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        return resultOf { decisionJson.decodeFromString<ProactiveDecision>(jsonText) }
            .onError { msg, t -> Logger.w(TAG, "Parse proactive decision failed: ${t?.message ?: msg}") }
            .getOrNull() ?: ProactiveDecision(shouldSend = false, reason = "parse_error")
    }

    /**
     * v0.44: 构造让 LLM 决定"是否发主动消息 + 发什么"的 prompt。
     *
     * 返回 system + user 两条消息:
     *  - system: 助手人设 + 决策指令 + 返回 JSON 格式约束
     *  - user:   最近 5 条对话内容(从早到晚),作为决策上下文
     *
     * 不直接复用 [AssistantEntity.systemPrompt],而是在其基础上叠加"判断是否发消息"指令,
     * 让 LLM 自己根据上下文活跃度决定是否发、发什么,避免无脑打扰用户。
     */
    private fun buildDecisionPrompt(
        assistant: AssistantEntity,
        recentMessages: List<UIMessage>,
    ): List<UIMessage> {
        val systemMsg = UIMessage(
            role = MessageRole.SYSTEM,
            content = buildString {
                appendLine("你是「${assistant.name.ifBlank { "muse" }}」,用户的虚拟陪伴助手。")
                if (assistant.systemPrompt.isNotBlank()) {
                    appendLine("你的人设参考:${assistant.systemPrompt.take(500)}")
                }
                appendLine()
                appendLine("下面会给你最近 5 条对话内容,请你判断现在是否适合主动给用户发一条消息。")
                appendLine("判断标准:")
                appendLine("- 如果最近对话还很活跃(最后一条是 user 在几分钟内发的),不需要主动发(用户还在聊)")
                appendLine("- 如果距离上次对话已经过了一段时间(半小时以上),可以主动发")
                appendLine("- 如果之前聊到某个话题有未完成的呼应(如问了问题没答、提到某事可以跟进),可以基于上下文主动跟进")
                appendLine("- 不要发与上下文无关的内容(如突然讲笑话、推鸡汤)")
                appendLine("- 如果上下文是吵架/不愉快,可以发关心的消息")
                appendLine("- 如果上下文是技术讨论,可以基于讨论主动延伸或追问")
                appendLine()
                appendLine("返回严格的 JSON 格式(不要带 markdown 代码块标记,不要带任何额外说明):")
                appendLine("""{"shouldSend": true/false, "content": "消息内容", "reason": "为什么发/不发"}""")
                appendLine()
                appendLine("content 要求:像真人发微信,20-80 字,纯文本,不带 markdown,不带「回复:」等前缀。")
                appendLine("如果 shouldSend=false,content 留空字符串。")
            },
        )
        val contextText = buildString {
            appendLine("最近 5 条对话(从早到晚):")
            if (recentMessages.isEmpty()) {
                appendLine("(暂无历史对话)")
            } else {
                recentMessages.forEach { msg ->
                    val role = if (msg.role == MessageRole.USER) "user" else "assistant"
                    appendLine("[$role] ${msg.content}")
                }
            }
        }
        val userMsg = UIMessage(
            role = MessageRole.USER,
            content = contextText,
        )
        return listOf(systemMsg, userMsg)
    }

    fun stop() {
        job?.cancel()
        job = null
        Logger.i(TAG, "ProactiveMessageRunner stopped")
    }

    companion object {
        private const val TAG = "ProactiveMsg"
        private const val POLL_INTERVAL_MS = 60_000L // 每分钟检查一次
        /** LLM 决策调用超时(毫秒)。 */
        private const val LLM_TIMEOUT_MS = 60_000L
        /** 决策 JSON 生成的最大 token 数。 */
        private const val DECISION_MAX_TOKENS = 300

        /**
         * v1.30: 计算本次有效的触发间隔(毫秒)。
         *
         * 基础间隔 = intervalMinutes;在 [-randomOffsetMinutes, +randomOffsetMinutes] 范围内加随机偏移,
         * 让发送时间更自然(像真人不会准点发消息)。下限钳制到 15 分钟,避免过于频繁打扰。
         * v1.30: 从小时单位改为分钟单位,支持 15/30/45 分钟等细分粒度。
         */
        private fun computeEffectiveIntervalMs(
            config: io.zer0.muse.data.ProactiveMessageConfig,
        ): Long {
            val baseMinutes = config.intervalMinutes.coerceAtLeast(15)
            val offsetRange = config.randomOffsetMinutes.coerceIn(0, baseMinutes)
            if (offsetRange == 0) return baseMinutes * 60_000L
            val offset = kotlin.random.Random.nextInt(-offsetRange, offsetRange + 1)
            val effectiveMinutes = (baseMinutes + offset).coerceAtLeast(15)
            return effectiveMinutes * 60_000L
        }
    }
}

/**
 * v0.44: LLM 决策返回的结构(是否发主动消息 + 内容 + 理由)。
 *
 * 解析失败时默认 [shouldSend]=false,避免误打扰用户。
 */
@Serializable
private data class ProactiveDecision(
    val shouldSend: Boolean = false,
    val content: String = "",
    val reason: String = "",
)
