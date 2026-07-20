package io.zer0.ai.openai

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.ollama.OllamaVisionInferrer
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * OpenAI 兼容 Provider。同时兼容所有走 OpenAI Chat Completions 协议的中转/自托管服务,
 * 仅 [ProviderConfig.baseUrl] 不同。
 *
 * - 流式: 走 SSE,`data: [DONE]` 表示结束
 * - 取消: UI 调 [ChatRequest.abortSignal] -> 取消 OkHttp Call
 * - 错误: HTTP 非 200 时读 body 提取 OpenAI 错误结构,失败回退原始文本
 */
class OpenAIProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /** 解析后的 OpenAI 特定配置(specific 为 null 时按 type 兜底)。L-OAI12: by lazy 避免重复解析。 */
    private val openAIConfig: ProviderSpecificConfig.OpenAI by lazy {
        config.resolvedSpecific() as? ProviderSpecificConfig.OpenAI
            ?: ProviderSpecificConfig.OpenAI()
    }

    private val sseFactory by lazy { EventSources.createFactory(httpClient) }

    /**
     * 获取实际发送给 API 的 model id。
     *
     * 1. 如果 OpenAI specific 配置了 [ProviderSpecificConfig.OpenAI.stripModelPrefix],优先移除该前缀。
     * 2. 未配置且 model id 含 "/" 时,自动取最后一段(兼容 openrouter/opencode-go 等聚合平台的
     *    "provider/model" 命名)。OpenAI 官方模型 id 不含 "/",因此不会误伤。
     *
     * v1.135: 自动剥离解决中转站(OpenCode/OpenRouter)因模型 id 前缀导致 HTTP 400 的问题。
     */
    private fun effectiveModelId(modelId: String): String {
        val configuredPrefix = openAIConfig.stripModelPrefix.takeIf { it.isNotBlank() }
        if (configuredPrefix != null) {
            return if (modelId.startsWith(configuredPrefix)) modelId.removePrefix(configuredPrefix) else modelId
        }
        return modelId.substringAfterLast("/").takeIf { it.isNotBlank() } ?: modelId
    }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val body = buildRequestBody(request)
        // M-OAI4: 改用配置项 chatCompletionsPath(支持 Azure 等中转自定义路径)
        val url = baseUrl() + openAIConfig.chatCompletionsPath
        Logger.i("OpenAIProvider", "streamChat: POST $url model=${request.model} msgs=${request.messages.size}")
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 捕获 ProducerScope,供匿名 listener 内启动重连协程
        val scope = this@callbackFlow

        // v1.135 性能诊断:记录各阶段耗时,帮助定位 OpenCode 等中转平台的延迟来源
        val requestStartAt = System.currentTimeMillis()
        var firstByteAt = 0L
        var firstDeltaAt = 0L

        // M-OAI3: 有限次指数退避重连参数
        val retryCount = AtomicInteger(0)
        // M-OAI3: 若已发出 tool_call 增量则不重试(避免重复)
        val toolCallDeltaSent = AtomicBoolean(false)
        // H-OAI1: 任何 ContentDelta/ReasoningDelta/ToolCallDelta 发出后置 true,
        //   重试前检查 !anyDeltaSent.get(),避免已流出内容被重发(原仅检查 toolCallDeltaSent
        //   不足以覆盖纯文本/纯推理场景的重发风险)。
        val anyDeltaSent = AtomicBoolean(false)
        // M-OAI4: tool_call index 兜底,用累积 Map 按 API index 分配本地递增 index
        val toolCallIndexMap = mutableMapOf<Int, Int>()
        var nextToolCallIndex = 0

        var currentEventSource: EventSource? = null
        // v1.114 修复: 持有底层 Call 引用,awaitClose 时一并 cancel(与 AnthropicProvider/GeminiProvider 一致),
        //   避免 eventSource 尚未创建或 cancel 不及时导致底层连接泄漏。
        var currentCall: Call? = null

        fun connect() {
            // v1.114 修复: 先 newCall 并持有引用,供 awaitClose cancel(参考 AnthropicProvider)
            val call = httpClient.newCall(httpRequest)
            currentCall = call
            currentEventSource = sseFactory.newEventSource(httpRequest, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    firstByteAt = System.currentTimeMillis()
                    Logger.i("OpenAIProvider", "streamChat TTFB: ${firstByteAt - requestStartAt}ms | url=$url")
                    if (!response.isSuccessful) {
                        // L-OAI1: 用 readBodySafely 替代 runCatching
                        val errText = ProviderHttpSupport.readBodySafely(response)
                        val msg = parseErrorMessage(response.code, errText)
                        Logger.w("OpenAIProvider", "streamChat onOpen HTTP ${response.code}: $msg")
                        // L-OAI17: 错误事件携带 throwable,便于上层据此区分错误类型
                        trySend(ChatStreamEvent.Error(msg, OpenAIHttpException(response.code, msg)))
                        close()
                        return
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (data == "[DONE]") {
                        trySend(ChatStreamEvent.Done())
                        close()
                        return
                    }
                    // M-OAI3: 改用 resultOf(会重抛 CancellationException),替代 runCatching(会吞 CancellationException)
                    val chunk = resultOf {
                        AppJson.decodeFromString<OpenAIStreamChunk>(data)
                    }.getOrNull() ?: return

                    val choice = chunk.choices.firstOrNull() ?: return
                    val delta = choice.delta
                    if (delta != null) {
                        if (firstDeltaAt == 0L) {
                            firstDeltaAt = System.currentTimeMillis()
                            Logger.i(
                                "OpenAIProvider",
                                "streamChat first delta: ${firstDeltaAt - requestStartAt}ms " +
                                    "(TTFB=${firstByteAt - requestStartAt}ms) | url=$url",
                            )
                        }
                        delta.reasoningContent?.takeIf { it.isNotEmpty() }
                            ?.let {
                                anyDeltaSent.set(true)
                                trySend(ChatStreamEvent.ReasoningDelta(it))
                            }
                        delta.content?.takeIf { it.isNotEmpty() }
                            ?.let {
                                anyDeltaSent.set(true)
                                trySend(ChatStreamEvent.ContentDelta(it))
                            }
                        // Phase 7: 解析 tool_calls 增量(每个 index 对应一个工具调用,arguments 分片累积)
                        delta.toolCalls?.forEach { tc ->
                            // M-OAI4: 用累积 Map 按 index 分配,避免默认 0 合并多个调用。
                            // 新工具调用(首片携带 id 或 name)触发新 index 分配。
                            val apiIndex = tc.index
                            val isNewCall = tc.id != null || tc.function?.name != null
                            if (isNewCall) {
                                toolCallIndexMap[apiIndex] = nextToolCallIndex++
                            }
                            // L-OAI13: toolCallIndexMap 缺失时(首片丢了/乱序)不 fallback 到 0,
                            //   否则会把该片误并入 index=0 的工具调用。跳过该片并记录警告。
                            val localIndex = toolCallIndexMap[apiIndex]
                            if (localIndex == null) {
                                Logger.w(
                                    "OpenAIProvider",
                                    "tool_calls 片段 apiIndex=$apiIndex 未在 map 中找到(首片丢失?),跳过该片",
                                )
                                return@forEach
                            }
                            toolCallDeltaSent.set(true)
                            anyDeltaSent.set(true)
                            // L-OAI5: 空 arguments 规范化
                            trySend(ChatStreamEvent.ToolCallDelta(
                                index = localIndex,
                                id = tc.id,
                                name = tc.function?.name,
                                argumentsDelta = tc.function?.arguments.orEmpty(),
                            ))
                        }
                    }
                    if (choice.finishReason != null) {
                        trySend(ChatStreamEvent.Done(choice.finishReason))
                        close()
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    if (request.abortSignal.aborted) {
                        Logger.d("OpenAIProvider", "streamChat aborted by user")
                        close()
                        return
                    }
                    // M-OAI3: 判断是否可重试(408/429/5xx/IOException),且未发出任何增量,且未达重试上限
                    // H-OAI1: 重试条件改用 anyDeltaSent(覆盖 Content/Reasoning/ToolCall 三类增量),
                    //   原仅检查 toolCallDeltaSent 会导致已流出的正文/推理被重发。
                    val code = response?.code
                    val isRetryable = (code != null && (code == 408 || code == 429 || code in 500..599))
                        || (t is java.io.IOException)
                    if (isRetryable && !anyDeltaSent.get()
                        && retryCount.incrementAndGet() <= MAX_RETRIES
                        && !request.abortSignal.aborted
                    ) {
                        val attempt = retryCount.get()
                        var backoffMs = (1L shl attempt) * RETRY_BASE_DELAY_MS
                        // M-OAI7: 429 限流优先用 Retry-After 响应头(秒数 → 毫秒)
                        if (code == 429) {
                            backoffMs = response?.header("Retry-After")?.toIntOrNull()
                                ?.let { it * 1000L } ?: backoffMs
                        }
                        // L-OAI9: 加 jitter(0~499ms),避免多客户端同步重试引发惊群
                        backoffMs += Random.nextLong(0, 500)
                        Logger.w("OpenAIProvider", "streamChat onFailure, retry $attempt/$MAX_RETRIES after ${backoffMs}ms: ${t?.message ?: code}")
                        scope.launch {
                            delay(backoffMs)
                            if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                connect()
                            }
                        }
                        return
                    }
                    // L-OAI1: 用 readBodySafely 替代 runCatching
                    // v1.109 修复: SSE 已建立(2xx)后中断是连接断开,不是 HTTP 错误
                    //   优先用 Throwable 信息,避免构造误导性的 "HTTP 200" 错误
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage()
                        } else {
                            val bodyText = ProviderHttpSupport.readBodySafely(it)
                            parseErrorMessage(it.code, bodyText)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                    Logger.e("OpenAIProvider", "streamChat onFailure: $msg", t)
                    trySend(ChatStreamEvent.Error(msg, t))
                    close()
                }
            })
        }

        connect()

        awaitClose {
            request.abortSignal.abort()
            currentEventSource?.cancel()
            // v1.114 修复: 同时 cancel 底层 Call(与 AnthropicProvider/GeminiProvider 一致)
            currentCall?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式聊天。
     *
     * v1.80 (L-OAI15): 设计决策 — completeText 不做自动重试(不同于 streamChat)。
     *   原因:completeText 主要服务于 memory 编译 / fact 抽取等后台任务,
     *   这些任务由 JobWorker 调度,本身已有重试机制(Worker.Result.retry);
     *   在 Provider 层再加一层重试会放大延迟且难以向用户暴露进度。
     *   如确需重试,应由调用方(Worker)根据返回的异常类型决定。
     */
    override suspend fun completeText(request: ChatRequest): ChatCompletion = withContext(Dispatchers.IO) {
        val body = buildRequestBody(request, stream = false)
        // M-OAI4: 改用配置项 chatCompletionsPath(与 streamChat 一致)
        val url = baseUrl() + openAIConfig.chatCompletionsPath
        Logger.i("OpenAIProvider", "completeText: POST $url model=${request.model}")
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    // L-OAI1: 用 readBodySafely 替代 runCatching
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val msg = parseErrorMessage(resp.code, errText)
                    Logger.w("OpenAIProvider", "completeText HTTP ${resp.code}: $msg")
                    // L-OAI11: 用自定义异常替代字符串前缀判断
                    throw OpenAIHttpException(resp.code, msg)
                }
                // M-OAI6: body 可能为 null(虽然 OkHttp 实际几乎不为 null,但类型上 Nullable),统一做空安全
                val raw = resp.body?.string()
                    ?: throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_body", resp.code))
                val parsed = AppJson.decodeFromString<OpenAICompletionResponse>(raw)
                val choice = parsed.choices.firstOrNull()
                    ?: throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_choices"))
                val msg = choice.message
                val text = msg?.content.orEmpty()
                val reasoningContent = msg?.reasoningContent.orEmpty()
                val toolCalls = msg?.toolCalls?.map {
                    ToolCall(id = it.id, name = it.function.name, arguments = it.function.arguments)
                }
                // Phase 7: 如果有 tool_calls,允许 text 为空(工具调用场景)
                // M-OAI8: 如果有推理内容(reasoning_content),也允许 text 为空
                //   (部分推理模型在非流式响应中可能只返回 reasoning_content 而 content 为空)
                if (text.isBlank() && toolCalls.isNullOrEmpty() && reasoningContent.isBlank()) {
                    Logger.w("OpenAIProvider", "completeText 返回空文本(无 content/reasoning_content/tool_calls)")
                    throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_text"))
                }
                Logger.d("OpenAIProvider", "completeText OK: text=${text.length} chars, reasoning=${reasoningContent.length} chars, toolCalls=${toolCalls?.size ?: 0}")
                ChatCompletion(
                    text = text,
                    finishReason = choice.finishReason,
                    toolCalls = toolCalls,
                    reasoningContent = reasoningContent.takeIf { it.isNotBlank() },
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("OpenAIProvider", "completeText aborted by user")
            } else if (t is OpenAIHttpException) {
                // L-OAI11: 已记录的 HTTP 错误,不重复 log
            } else {
                Logger.e("OpenAIProvider", "completeText 异常", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    private fun baseUrl(): String = config.resolvedBaseUrl()

    /**
     * 拉取上游模型列表。
     *
     * GET {resolvedBaseUrl()}/models(resolvedBaseUrl 已含 /v1),
     * Bearer 鉴权,解析 data[].id。返回的每个 Model 的 id/name 均为上游 id,
     * contextWindow 用 [ModelContextWindowRegistry] 兜底(上游不返回该字段)。
     *
     * [config] 形参允许传入未保存的编辑值(如临时改的 apiKey/baseUrl)。
     *
     * v1.80 (H-OAI2): 显式 catch CancellationException 并 call.cancel(),
     *   否则 OkHttp Call 会继续阻塞 IO 线程最长 300s(readTimeout),导致协程取消后线程仍被占用。
     *
     * v1.132 优化(参考 rikkahub/kelivo/openhanako 三个项目):
     *  - OpenRouter: 解析 context_length / max_completion_tokens / pricing,
     *    动态注册到 [ModelContextWindowRegistry](参考 openhanako 的三层元信息叠加);
     *    并附加 HTTP-Referer / X-Title 头(参考 kelivo 的推广/归因头)
     *  - 过滤异常条目:id 等于 provider 自身 id 的伪模型(参考 openhanako 对 DeepSeek 的过滤)
     *  - 按 id 去重,保留首个(参考 openhanako 的 Set 去重)
     *  - 按 id 字母序排序,便于用户查找(参考 rikkahub 的 sortedBy)
     *  - 使用独立短超时 client(30s connect + 30s read),避免 listModels 卡顿占用 chat 长连接资源
     *  - 401/403 不 fallback,直接抛错(凭证问题不掩盖,参考 openhanako 的错误分级)
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val resolvedBaseUrl = config.resolvedBaseUrl()
        val url = resolvedBaseUrl.trimEnd('/') + "/models"
        // P2-3: 识别 Ollama 服务(baseUrl host 含 "ollama" 或端口 11434),
        //   命中时对每个模型调用 OllamaVisionInferrer 推断 supportsVision/supportsTools。
        val isOllama = isOllamaEndpoint(resolvedBaseUrl)
        Logger.i("OpenAIProvider", "listModels: GET $url" + if (isOllama) " (ollama)" else "")
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
        // v1.132: OpenRouter 归因头(参考 kelivo 的 provider_request_headers.dart)
        // 上报应用名 + 来源,既符合 OpenRouter 排名榜规则,也避免被识别为匿名流量
        if (url.contains("openrouter.ai")) {
            builder.header("HTTP-Referer", "https://github.com/zer0/muse")
                .header("X-Title", "Muse")
        }
        val httpRequest = builder.get().build()
        // v1.132: listModels 使用独立短超时 client(30s),与 chat 长超时分离
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        // H-OAI2: 仿 completeText 模式,显式 catch CancellationException + call.cancel()
        val call = listClient.newCall(httpRequest)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    // L-OAI1: 用 readBodySafely 替代 runCatching
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val errMsg = ErrorCode.INVALID_RESPONSE.toMessage("model_list_fetch", resp.code) +
                        errText.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    // L-OAI16: 用 OpenAIHttpException 替代通用 RuntimeException,
                    //   保留 HTTP code 作为类型信息(与 completeText 一致),便于上层区分错误来源。
                    throw OpenAIHttpException(resp.code, errMsg)
                }
                // M-OAI6: body 空安全
                val raw = resp.body?.string()
                    ?: throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("model_list_empty", resp.code))
                val parsed = AppJson.decodeFromString<OpenAIModelsResponse>(raw)
                // v1.132: 去重 + 过滤 + 排序 + 元信息丰富
                val seen = HashSet<String>()
                parsed.data.asSequence()
                    // 过滤空 id 与伪模型(id == provider id,如 deepseek API 偶发返回 "deepseek")
                    .filter { it.id.isNotBlank() && it.id != config.id }
                    // 按 id 去重,保留首个(防止上游返回重复)
                    .filter { seen.add(it.id) }
                    .map { m ->
                        // v1.132: OpenRouter/Together 的 context_length 透传并注册到全局表
                        val upstreamCtx = m.context_length
                            ?: m.top_provider?.max_completion_tokens?.takeIf { it > 0 }
                        if (upstreamCtx != null && upstreamCtx > 0) {
                            ModelContextWindowRegistry.register(m.id, upstreamCtx)
                        }
                        // P2-3: 视觉/工具能力填充。
                        //   优先级: 服务端 capabilities 字段 > OllamaVisionInferrer 推断 > 默认 false。
                        //   - 服务端 capabilities 含 "vision" → supportsVision = true
                        //   - 服务端 capabilities 含 "tools" → abilities 加 TOOL
                        //   - Ollama 场景下,服务端未声明时用模型名关键字推断(见 OllamaVisionInferrer)
                        val serverVision = m.capabilities
                            ?.any { it.equals("vision", ignoreCase = true) } ?: false
                        val serverTools = m.capabilities
                            ?.any { it.equals("tools", ignoreCase = true) } ?: false
                        val inferredVision = isOllama && !serverVision &&
                            OllamaVisionInferrer.inferSupportsVision(m.id)
                        val inferredTools = isOllama && !serverTools &&
                            OllamaVisionInferrer.inferSupportsTools(m.id)
                        val supportsVision = serverVision || inferredVision
                        val abilities = if (serverTools || inferredTools) {
                            setOf(ModelAbility.TOOL)
                        } else {
                            emptySet()
                        }
                        Model(
                            id = m.id,
                            name = m.id,
                            providerId = config.id,
                            // 优先用上游 context_length,其次查注册表(刚 register 的会被命中)
                            contextWindow = upstreamCtx ?: ModelContextWindowRegistry.lookup(m.id),
                            // v1.132: 透传 max_completion_tokens 作为 maxOutputTokens
                            maxOutputTokens = m.max_completion_tokens ?: m.top_provider?.max_completion_tokens,
                            // P2-3: 视觉/工具能力(服务端优先,推断兜底)
                            supportsVision = supportsVision,
                            abilities = abilities,
                        )
                    }
                    // 按 id 字母序排序,便于用户查找
                    .sortedBy { it.id.lowercase() }
                    .toList()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        }
    }

    /**
     * P2-3: 判断 baseUrl 是否为 Ollama 服务。
     *
     * Ollama 默认端口 11434,用户也可能用自定义主机名(如 "ollama.local"),
     * 或在 baseUrl 中显式包含 "ollama" 标识。命中任一即按 Ollama 处理,
     * 触发 [OllamaVisionInferrer] 推断。
     *
     * 注意: 仅用于能力推断分支,不影响请求构造与协议兼容性。
     */
    private fun isOllamaEndpoint(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains(":11434") || lower.contains("ollama")
    }

    private fun buildRequestBody(request: ChatRequest, stream: Boolean = true): String {
        // v1.138: 思考等级优化 — 避免简单问题模型过度思考。
        // - AUTO: 不发 reasoning_effort(让服务端自行决定)
        // - OFF: 模型支持推理时发 "minimal"(最小推理,比不发 effort 让服务端用默认 medium 更少);
        //        模型不支持推理时不发(避免对 GPT-4o 等非推理模型发 reasoning_effort 导致 400)
        // - LOW/MEDIUM/HIGH/XHIGH: 显式发送对应 effort
        val effort = when (request.reasoningLevel) {
            io.zer0.ai.core.ReasoningLevel.AUTO -> null
            io.zer0.ai.core.ReasoningLevel.OFF ->
                if (request.model.supportsReasoning()) "minimal" else null
            else -> request.reasoningLevel.effort
        }
        // compat 派生:按 type + baseUrl + modelId 三层匹配,决定是否注入 reasoning_effort / tools。
        // 用 effectiveModelId(已剥离 stripModelPrefix)作为 modelId,反映真正发给 API 的 model。
        // H-COMPAT1: 仅在最明显的参数注入点加判断,不重构请求构造主体逻辑。
        val effectiveModel = effectiveModelId(request.model.id)
        val compat: ProviderCompat = config.resolvedCompat(effectiveModel)
        val payload = OpenAIRequest(
            model = effectiveModel,
            messages = request.messages.map { it.toOpenAI() },
            temperature = request.temperature,
            max_tokens = request.maxTokens,
            stream = stream,
            // compat.supportsToolCalling=false 时强制不发 tools(如 deepseek-reasoner / o1-mini)
            tools = if (compat.supportsToolCalling) request.tools?.mapNotNull { it.toOpenAISafely() } else null,
            // compat.supportsReasoningEffort=false 时强制不发 reasoning_effort
            //   (如 DeepSeek / Zhipu / Gemini OpenAI 兼容层,各自用 reasoning_content / thinking 字段)
            reasoning_effort = if (compat.supportsReasoningEffort) effort else null,
        )
        return AppJson.encodeToString(payload)
    }

    /**
     * L-OAI14: 解析 [parametersJsonSchema] 失败时跳过该工具并记录警告,
     *   避免单个工具的非法 schema 导致整个请求失败(其他工具仍可正常调用)。
     */
    private fun ToolDefinition.toOpenAISafely(): OpenAITool? = try {
        OpenAITool(
            function = OpenAIToolFunction(
                name = name,
                description = description,
                parameters = AppJson.decodeFromString(JsonElement.serializer(), parametersJsonSchema),
            ),
        )
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        Logger.w(
            "OpenAIProvider",
            "工具 '$name' 的 parametersJsonSchema 解析失败,跳过该工具: ${t.message}",
        )
        null
    }

    /**
     * Phase 8.6: UIMessage -> OpenAIMessage。
     *
     * 多模态处理:
     *  - 无图片:imageBase64List 为空 -> content = JsonPrimitive(text)(纯字符串)
     *  - 有图片:imageBase64List 非空 -> content = JsonArray([
     *      {type:"text", text:"..."},
     *      {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}
     *    ])(OpenAI Vision 协议)
     *
     * M-OAI5: 图片 mime type 从 base64 头部 magic bytes 推断。
     * M-OAI8: base64 图片数量限制(<=4张)+ 单张大小限制(<=2MB),超限丢弃。
     * L-OAI7: assistant + tool_calls 时 content 为空传 JsonNull 而非空串。
     */
    private fun UIMessage.toOpenAI(): OpenAIMessage = OpenAIMessage(
        role = when (role) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "tool"
        },
        content = if (imageBase64List.isEmpty()) {
            // L-OAI7: assistant + tool_calls 时 content 为空传 JsonNull 而非空串
            if (role == MessageRole.ASSISTANT && !toolCalls.isNullOrEmpty() && content.isBlank()) {
                JsonNull
            } else {
                JsonPrimitive(content)
            }
        } else {
            buildJsonArray {
                // 文本 part(即使为空也添加,避免 messages[].content 为空数组)
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
                // M-OAI8: 图片数量限制 + 单张大小限制,超限丢弃
                val validImages = imageBase64List.filter { it.isNotEmpty() }
                if (validImages.size > MAX_VISION_IMAGES) {
                    Logger.w("OpenAIProvider", "图片数量 ${validImages.size} 超过上限 $MAX_VISION_IMAGES,丢弃多余的")
                }
                validImages.take(MAX_VISION_IMAGES).forEach { b64 ->
                    if (b64.length > MAX_IMAGE_BASE64_LEN) {
                        Logger.w("OpenAIProvider", "图片 base64 长度 ${b64.length} 超过 $MAX_IMAGE_BASE64_LEN,丢弃")
                        return@forEach
                    }
                    // M-OAI5: 从 magic bytes 推断 mime type
                    val mimeType = inferMimeType(b64)
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:$mimeType;base64,$b64")
                        })
                    })
                }
            }
        },
        toolCalls = toolCalls?.map { it.toOpenAI() },
        toolCallId = toolCallId,
    )

    private fun ToolCall.toOpenAI(): OpenAIToolCall = OpenAIToolCall(
        id = id,
        function = OpenAIToolCallFunction(name = name, arguments = arguments),
    )

    /**
     * M-OAI5: 从 base64 头部 magic bytes 推断图片 MIME 类型。
     * - jpeg: 0xFFD8
     * - png:  0x89504E47
     * - gif:  0x47494638
     * - webp: 0x52494646
     * 回退 image/jpeg。
     */
    private fun inferMimeType(base64: String): String {
        return try {
            // 取前 8 个 base64 字符(6 字节)用于判断 magic bytes
            val prefix = base64.take(8).padEnd(8, '=')
            val bytes = java.util.Base64.getMimeDecoder().decode(prefix)
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
                bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() -> "image/gif"
                bytes.size >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "image/webp"
                else -> "image/jpeg"
            }
        } catch (e: Exception) {
            "image/jpeg"
        }
    }

    /**
     * 解析错误消息。
     * M-OAI1: 用 classifyHttpCode 分类状态码。
     * M-OAI2: 消息截断(take 200)。
     * L-OAI10: 追加 detail.code 字段。
     * L-OAI11: 移除一次重复截断(原 safeBody=take(200) + msg.take(200) 两次截断)。
     */
    private fun parseErrorMessage(code: Int, body: String): String {
        // M-OAI1: HTTP 状态码分类
        val category = ProviderHttpSupport.classifyHttpCode(code)
        // M-OAI3: 改用 resultOf(会重抛 CancellationException),替代 runCatching
        val detail = resultOf {
            AppJson.decodeFromString<OpenAIErrorBody>(body).error
        }.getOrNull()
        return buildString {
            append("HTTP ").append(code)
            category?.let { append(" [").append(it).append("]") }
            // 优先用解析出的 detail.message,回退到原始 body(统一在此处截断一次)
            // L-OAI11: 移除 safeBody=body.take(200) 的预先截断,仅在此处 take(200)
            val msg = detail?.message?.takeIf { it.isNotBlank() }
                ?: body.takeIf { it.isNotBlank() }
            msg?.let { append(": ").append(it.take(200)) }
            // L-OAI10: 追加 detail.code 字段
            detail?.code?.let { codeElem ->
                val codeStr = when (codeElem) {
                    is JsonPrimitive -> codeElem.content
                    else -> codeElem.toString()
                }
                if (codeStr.isNotBlank()) append(" [code=").append(codeStr).append("]")
            }
            detail?.type?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        }
    }

    private companion object {
        // L-OAI12: 移除 charset=utf-8(OpenAI / 中转普遍按 UTF-8 处理 application/json,
        //   且 OkHttp 对 application/json 默认即按 UTF-8 解码;显式 charset 在某些中转下
        //   反而被严格校验导致 415)。
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        // M-OAI3: 流式重连参数
        const val MAX_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 1000L
        // M-OAI8: Vision 图片限制
        const val MAX_VISION_IMAGES = 4
        const val MAX_IMAGE_BASE64_LEN = 2 * 1024 * 1024  // 2MB
    }
}

/**
 * L-OAI11: OpenAI HTTP 错误异常,携带状态码,替代字符串前缀判断。
 */
internal class OpenAIHttpException(val code: Int, message: String) : RuntimeException(message)
