package io.zer0.muse.ui.translate

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.speech.TtsManager
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
    private val ttsManager: TtsManager,
    private val appContext: Context,
) : ViewModel() {

    /** UI 状态。 */
    data class State(
        /** 用户输入的原文。 */
        val inputText: String = "",
        /** 翻译后的译文。 */
        val translatedText: String = "",
        /** 是否正在翻译中。 */
        val translating: Boolean = false,
        /** 源语言(中文名),"自动检测"表示由模型判断。 */
        val sourceLanguage: String = SOURCE_LANGUAGES.first(),
        /** 目标语言(中文名)。 */
        val targetLanguage: String = TARGET_LANGUAGES.first(),
        /** v1.0.30: 翻译风格(通用/学术/商务/口语化/润色)。 */
        val translationStyle: String = TRANSLATION_STYLES.first(),
        /** 错误消息(null 表示无错误)。 */
        val errorMessage: String? = null,
        /** v1.97: 翻译历史记录(最近 N 条,内存保留,不持久化)。 */
        val history: List<TranslateHistoryItem> = emptyList(),
    )

    /** v1.97: 翻译历史记录项。 */
    data class TranslateHistoryItem(
        val sourceText: String,
        val translatedText: String,
        val sourceLanguage: String,
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

    /** 更新源语言。 */
    fun updateSourceLanguage(language: String) {
        _state.update { it.copy(sourceLanguage = language) }
    }

    /** v1.0.30: 更新翻译风格。 */
    fun updateTranslationStyle(style: String) {
        _state.update { it.copy(translationStyle = style) }
    }

    /** 交换源语言与目标语言,并将当前译文回填到输入框(便于继续翻译)。 */
    fun swapLanguages() {
        val current = _state.value
        translateJob?.cancel()
        _state.update {
            it.copy(
                sourceLanguage = current.targetLanguage,
                targetLanguage = if (current.sourceLanguage == SOURCE_AUTO) TARGET_LANGUAGES.first() else current.sourceLanguage,
                inputText = current.translatedText,
                translatedText = "",
                translating = false,
                errorMessage = null,
            )
        }
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
                sourceLanguage = item.sourceLanguage,
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
                val prompt = buildTranslationPrompt(
                    text = current.inputText,
                    targetLanguage = current.targetLanguage,
                    sourceLanguage = current.sourceLanguage,
                    style = current.translationStyle,
                )
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
                            errorMessage = appContext.getString(R.string.err_chat_translate_empty),
                        )
                    }
                } else {
                    // v1.97: 翻译成功,记录到历史(最近 N 条)
                    val historyItem = TranslateHistoryItem(
                        sourceText = current.inputText,
                        translatedText = translated,
                        sourceLanguage = current.sourceLanguage,
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
                        errorMessage = appContext.getString(R.string.err_chat_translate_failed, t.message ?: appContext.getString(R.string.err_chat_unknown)),
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

    /** 朗读当前原文。 */
    fun speakSource(): Boolean {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) return false
        return ttsManager.speak(text, utteranceId = "translate_source_${System.currentTimeMillis()}")
    }

    /** 朗读当前译文。 */
    fun speakTranslated(): Boolean {
        val text = _state.value.translatedText.trim()
        if (text.isBlank()) return false
        return ttsManager.speak(text, utteranceId = "translate_result_${System.currentTimeMillis()}")
    }

    /** 停止朗读。 */
    fun stopSpeaking() {
        ttsManager.stop()
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

        /** 自动检测源语言占位值。 */
        const val SOURCE_AUTO = "自动检测"

        /** 支持的源语言列表(中文名,首项为自动检测)。 */
        val SOURCE_LANGUAGES: List<String> = listOf(
            SOURCE_AUTO, "中文", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /** 支持的目标语言列表(中文名,与 ChatViewModel.translateMessage 一致)。 */
        val TARGET_LANGUAGES: List<String> = listOf(
            "中文", "English", "日本語", "한국어",
            "Français", "Deutsch", "Español", "Русский",
            "العربية", "Português",
        )

        /** v1.0.30: 支持的翻译风格。 */
        val TRANSLATION_STYLES: List<String> = listOf(
            "通用", "学术", "商务", "口语化", "润色", "简洁"
        )

        /**
         * 构建翻译 prompt。
         *
         * @param sourceLanguage 源语言,自动检测时让模型自行判断
         * @param style 翻译风格,影响语气与用词
         */
        fun buildTranslationPrompt(
            text: String,
            targetLanguage: String,
            sourceLanguage: String = SOURCE_AUTO,
            style: String = TRANSLATION_STYLES.first(),
        ): String = buildString {
            if (sourceLanguage == SOURCE_AUTO) {
                appendLine("你是一个专业翻译助手。请自动识别下面文本的语言,并将其翻译为$targetLanguage。")
            } else {
                appendLine("你是一个专业翻译助手。请将下面的文本从$sourceLanguage 翻译为$targetLanguage。")
            }
            appendLine("- 只输出译文,不要加解释、前缀或注释")
            appendLine("- 保留原文的格式(换行/列表/代码块等)")
            appendLine("- 如果原文已经是$targetLanguage,原样输出")
            when (style) {
                "学术" -> appendLine("- 使用学术、正式、严谨的表达方式")
                "商务" -> appendLine("- 使用商务、专业、礼貌的表达方式")
                "口语化" -> appendLine("- 使用自然、口语化、贴近日常对话的表达方式")
                "润色" -> appendLine("- 在忠实原意的基础上润色译文,使其更流畅优美")
                "简洁" -> appendLine("- 尽量简洁,去除冗余表达,保留核心信息")
                else -> appendLine("- 使用通用、准确、自然的表达方式")
            }
            appendLine()
            appendLine("原文:")
            appendLine(text)
        }
    }
}
