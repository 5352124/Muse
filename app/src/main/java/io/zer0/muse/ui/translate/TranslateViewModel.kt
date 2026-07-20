package io.zer0.muse.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v1.97 gap8: 独立翻译页 ViewModel。
 *
 * 复用 [ChatService] 的通用文本补全能力,不依赖会话/消息持久化。
 * 翻译策略与 [io.zer0.muse.ui.ChatViewModel.translateMessage] 一致:
 *  1. 优先 [ChatService.completeText](一次性,速度快)
 *  2. 若 Provider 未实现 completeText 或出错,降级 [ChatService.streamChat](流式,实时更新译文)
 */
class TranslateViewModel(
    private val chatService: ChatService,
) : ViewModel() {

    /** UI 状态。 */
    data class State(
        /** 用户输入的原文。 */
        val inputText: String = "",
        /** 翻译后的译文。 */
        val translatedText: String = "",
        /** 是否正在翻译中。 */
        val translating: Boolean = false,
        /** 目标语言(中文名)。 */
        val targetLanguage: String = TARGET_LANGUAGES.first(),
        /** 错误消息(null 表示无错误)。 */
        val errorMessage: String? = null,
        /** v1.97: 翻译历史记录(最近 N 条,内存保留,不持久化)。 */
        val history: List<TranslateHistoryItem> = emptyList(),
    )

    /** v1.97: 翻译历史记录项。 */
    data class TranslateHistoryItem(
        val sourceText: String,
        val translatedText: String,
        val targetLanguage: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前翻译协程,支持取消。 */
    private var translateJob: Job? = null

    /** 更新输入文本。 */
    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /** 更新目标语言。 */
    fun updateTargetLanguage(language: String) {
        _state.update { it.copy(targetLanguage = language) }
    }

    /** 清空输入和译文。 */
    fun clear() {
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = "",
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** v1.97: 加载历史记录项到输入框(重新翻译或查看)。 */
    fun loadHistoryItem(item: TranslateHistoryItem) {
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = item.sourceText,
                translatedText = item.translatedText,
                targetLanguage = item.targetLanguage,
                translating = false,
                errorMessage = null,
            )
        }
    }

    /** v1.97: 清空翻译历史。 */
    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
    }

    /**
     * 粘贴剪贴板文本到输入框。
     * @param clipText 剪贴板文本(由 UI 层从 ClipboardManager 获取)
     * @return true 表示粘贴成功,false 表示剪贴板为空
     */
    fun paste(clipText: String?): Boolean {
        if (clipText.isNullOrBlank()) return false
        _state.update { it.copy(inputText = clipText) }
        return true
    }

    /** 将译文回填到输入框(用于"翻译后再翻译"场景)。 */
    fun swapResultToInput() {
        val result = _state.value.translatedText
        if (result.isBlank()) return
        translateJob?.cancel()
        _state.update {
            it.copy(
                inputText = result,
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
    }

    /**
     * 执行翻译。
     *
     * 策略:completeText 优先 → streamChat 兜底。
     * 流式收集时实时更新 translatedText,让用户看到逐字输出。
     */
    fun translate() {
        val current = _state.value
        if (current.translating) return
        if (current.inputText.isBlank()) return

        translateJob?.cancel()
        _state.update {
            it.copy(
                translating = true,
                translatedText = "",
                errorMessage = null,
            )
        }

        translateJob = viewModelScope.launch {
            try {
                val prompt = buildTranslationPrompt(current.inputText, current.targetLanguage)
                val messages = listOf(UIMessage(role = MessageRole.USER, content = prompt))

                // 优先 completeText(一次性返回,速度快)
                val translated: String = try {
                    val completion: ChatCompletion = chatService.completeText(messages = messages)
                    // v1.99: 剥离推理模型内嵌的 <think> 标签,只保留纯净译文
                    io.zer0.muse.transformer.stripThinkTags(completion.text).trim()
                } catch (e: UnsupportedOperationException) {
                    // Provider 未实现 completeText,降级流式
                    collectStream(messages)
                } catch (e: Exception) {
                    // 其他错误也降级流式(网络抖动等)
                    Logger.w(TAG, "completeText failed, fallback to streamChat", e)
                    collectStream(messages)
                }

                if (translated.isEmpty()) {
                    _state.update {
                        it.copy(
                            translating = false,
                            errorMessage = "翻译结果为空",
                        )
                    }
                } else {
                    // v1.97: 翻译成功,记录到历史(最近 20 条)
                    val historyItem = TranslateHistoryItem(
                        sourceText = current.inputText,
                        translatedText = translated,
                        targetLanguage = current.targetLanguage,
                    )
                    _state.update {
                        it.copy(
                            translating = false,
                            translatedText = translated,
                            history = (listOf(historyItem) + it.history).take(MAX_HISTORY),
                        )
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消,不更新状态(cancelTranslation 已处理)
                throw e
            } catch (t: Exception) {
                Logger.e(TAG, "translate failed", t)
                _state.update {
                    it.copy(
                        translating = false,
                        errorMessage = "翻译失败: ${t.message}",
                    )
                }
            }
        }
    }

    /** 取消当前翻译。 */
    fun cancelTranslation() {
        translateJob?.cancel()
        _state.update { it.copy(translating = false) }
    }

    /** 消费错误消息(UI 层显示后调用,清除 errorMessage)。 */
    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * 流式收集翻译结果,实时更新 translatedText。
     */
    private suspend fun collectStream(messages: List<UIMessage>): String {
        val sb = StringBuilder()
        chatService.streamChat(messages = messages).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> {
                    sb.append(event.delta)
                    _state.update { it.copy(translatedText = sb.toString()) }
                }
                is ChatStreamEvent.Error -> {
                    throw RuntimeException(event.message, event.throwable)
                }
                is ChatStreamEvent.Done -> {
                    // 流结束,返回收集到的完整文本
                }
                else -> {
                    // 忽略 ReasoningDelta / ToolCallDelta / ImageDelta
                }
            }
        }
        return io.zer0.muse.transformer.stripThinkTags(sb.toString()).trim()
    }

    companion object {
        private const val TAG = "TranslateVM"
        /** v1.104: 翻译历史上限(之前硬编码 take(20),抽出为常量便于调整)。 */
        private const val MAX_HISTORY = 50

        /** 支持的目标语言列表(中文名,与 ChatViewModel.translateMessage 一致)。 */
        val TARGET_LANGUAGES: List<String> = listOf(
            "中文", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /**
         * 构建翻译 prompt(与 ChatViewModel.translateMessage 同一模板)。
         */
        fun buildTranslationPrompt(text: String, targetLanguage: String): String = buildString {
            appendLine("你是一个专业翻译助手。请将下面的文本翻译为$targetLanguage。")
            appendLine("- 只输出译文,不要加解释、前缀或注释")
            appendLine("- 保留原文的格式(换行/列表/代码块等)")
            appendLine("- 如果原文已经是$targetLanguage,原样输出")
            appendLine()
            appendLine("原文:")
            appendLine(text)
        }
    }
}
