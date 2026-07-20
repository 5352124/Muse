package io.zer0.muse.vision

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 视觉分析异常。
 *
 * v1.135: 视觉辅助失败时不再静默返回 null,而是抛出此异常,
 * 由调用方(如 [io.zer0.muse.ui.ChatViewModel])捕获并注入失败提示、清空图片,
 * 避免把原图发给纯文本模型导致 HTTP 400。
 */
class VisionAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 视觉辅助桥接器 — 让纯文本模型通过视觉模型"看到"图片。
 *
 * 工作原理:
 *  1. 检测当前模型是否支持视觉输入([Model.supportsVisionInput])
 *  2. 若不支持且视觉辅助已启用,将图片发送给配置的视觉模型进行分析
 *  3. 获取结构化文字描述,以 `<vision-context>` 标签包裹后注入用户消息
 *
 * v1.135 参考 openhanako 实现:
 *  - 视觉模型自身能力通过 [ModelRegistry.enhanceModel] 识别,解决中转平台前缀导致误判
 *  - 使用固定字段的结构化提示词(image_overview / visible_text / objects_and_layout / ...)
 *  - 失败时抛 [VisionAnalysisException] 而非静默返回 null
 *  - 提供 [buildFailureNotice] 生成给文本模型的失败提示
 */
class VisionBridge(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    /** v1.135: 视觉辅助结果缓存(session 级 + sidecar 持久化)。 */
    private val visionCache: VisionCache? = null,
) {

    companion object {
        private const val TAG = "VisionBridge"

        /**
         * 视觉分析结构化提示词。
         *
         * 与 openhanako 的 `formatStructuredVisionNote` 字段对齐,
         * 让视觉模型输出固定字段,便于下游纯文本模型稳定消费。
         */
        private val VISION_PROMPT = """
            请分析这张图片,为另一个纯文本模型生成结构化描述。
            使用以下固定字段输出,每个字段独占一行,字段名后加冒号:

            image_overview: 图片的整体内容概述
            visible_text: 图片中可见的重要文字(OCR),无则写"无"
            objects_and_layout: 重要物体、位置、数量及其关系
            charts_or_data: 图表/表格/数据细节,无则写"无"
            user_request_answer: 根据图片内容回答用户可能的问题
            evidence: 支持上述回答的视觉证据
            uncertainty: 不确定、被遮挡或需要猜测的内容

            不要提及你是工具或独立模型,直接输出字段内容即可。
        """.trimIndent()
    }

    /**
     * 检查给定模型是否支持直接图像输入。
     *
     * @param model 待检查的模型
     * @return true 表示模型原生支持视觉,无需走视觉辅助桥接
     */
    fun supportsVision(model: Model): Boolean = model.supportsVisionInput()

    /**
     * 分析单张图片,返回 `<vision-context>` 包裹的文字描述。
     *
     * 流程:
     *  1. 检查视觉辅助是否启用
     *  2. 读取视觉模型和 Provider 配置
     *  3. 用 [ModelRegistry.enhanceModel] 增强视觉模型能力识别
     *  4. 校验视觉模型自身是否支持视觉输入
     *  5. 构造带图片的请求发送给视觉模型
     *  6. 返回描述文本(失败抛 [VisionAnalysisException])
     *
     * @param imageBase64 图片的 base64 编码(无 data: 前缀)
     * @param mimeType 图片 MIME 类型(如 "image/jpeg"),目前仅用于日志
     * @return 视觉描述文本(含 `<vision-context>` 标签)
     * @throws VisionAnalysisException 禁用、未配置、模型不支持视觉或分析失败时抛出
     */
    suspend fun analyzeImage(
        imageBase64: String,
        mimeType: String = "image/jpeg",
    ): String {
        // 1. 检查视觉辅助是否启用
        val enabled = settings.visionEnabledFlow.first()
        if (!enabled) {
            Logger.d(TAG, "视觉辅助未启用,跳过")
            throw VisionAnalysisException("视觉辅助未启用")
        }

        // 2. 读取视觉模型配置
        val visionModelId = settings.visionModelIdFlow.first()
        val visionProviderId = settings.visionProviderIdFlow.first()

        if (visionModelId.isNullOrBlank() || visionProviderId.isNullOrBlank()) {
            Logger.w(TAG, "视觉模型或供应商未配置,跳过")
            throw VisionAnalysisException("视觉模型或供应商未配置")
        }

        // 3. 解析视觉模型所属的 ProviderConfig 和 Model
        val allProviders = settings.getAllProviders()
        val visionProvider: ProviderConfig = allProviders.firstOrNull { it.id == visionProviderId }
            ?: run {
                Logger.w(TAG, "视觉供应商 $visionProviderId 未找到")
                throw VisionAnalysisException("视觉供应商 $visionProviderId 未找到")
            }

        val rawVisionModel: Model = visionProvider.models.firstOrNull { it.id == visionModelId }
            ?: run {
                Logger.w(TAG, "视觉模型 $visionModelId 未找到")
                throw VisionAnalysisException("视觉模型 $visionModelId 未找到")
            }

        // v1.135: 增强视觉模型能力识别,解决中转平台前缀(如 opencode-go/)导致误判
        val visionModel = ModelRegistry.enhanceModel(rawVisionModel)

        // 4. 校验视觉模型自身是否支持视觉输入
        if (!visionModel.supportsVisionInput()) {
            Logger.w(TAG, "所选视觉模型 ${visionModel.id} 不支持视觉输入")
            throw VisionAnalysisException("所选视觉模型 ${visionModel.id} 不支持视觉输入")
        }

        // 5. 构造请求:USER 消息携带图片 base64 + 分析提示词
        val userMessage = UIMessage(
            role = MessageRole.USER,
            content = VISION_PROMPT,
            imageBase64List = listOf(imageBase64),
        )

        return try {
            Logger.i(TAG, "开始视觉分析: model=${visionModel.id}, provider=${visionProvider.id}")
            val completion = chatService.completeText(
                messages = listOf(userMessage),
                model = visionModel,
                providerConfig = visionProvider,
                // 不使用工具,不需要推理
                tools = null,
            )
            val description = completion.text.trim()
            if (description.isBlank()) {
                Logger.w(TAG, "视觉模型返回空描述")
                throw VisionAnalysisException("视觉模型返回空描述")
            }
            Logger.i(TAG, "视觉分析完成,描述长度: ${description.length}")
            "<vision-context>\n$description\n</vision-context>"
        } catch (e: VisionAnalysisException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "视觉分析失败: ${e.message}", e)
            throw VisionAnalysisException("视觉分析失败: ${e.message}", e)
        }
    }

    /**
     * 批量分析多张图片,返回所有成功描述的列表。
     *
     * v1.135: 单张失败会被捕获并继续;若全部失败则抛出最后一次异常,
     * 让调用方注入失败提示并清空图片,避免向纯文本模型发图。
     *
     * v1.135-A: 新增 session 级缓存。同一会话内重复出现的图片会直接复用已分析结果,
     * 减少视觉模型调用次数与首 token 延迟;未命中时写入缓存并持久化到 sidecar。
     *
     * @param images base64 图片列表(无 data: 前缀)
     * @param sessionId 当前会话 id;为 null 时不使用缓存
     * @return 视觉描述列表(含 `<vision-context>` 标签)
     * @throws VisionAnalysisException 全部图片分析失败时抛出
     */
    suspend fun analyzeImages(images: List<String>, sessionId: String? = null): List<String> {
        if (images.isEmpty()) return emptyList()

        // 读取当前视觉模型 id 作为缓存 key 的一部分(不同模型描述可能不同)
        val visionModelId = runCatching { settings.visionModelIdFlow.first() }.getOrNull()

        var lastError: VisionAnalysisException? = null
        val descriptions = mutableListOf<String>()
        images.forEachIndexed { index, base64 ->
            try {
                // 1. 优先查 session 缓存
                val cached = if (sessionId != null && visionModelId != null) {
                    visionCache?.get(sessionId, base64, visionModelId)
                } else null

                if (cached != null) {
                    Logger.d(TAG, "第 ${index + 1}/${images.size} 张图片命中视觉缓存")
                    descriptions.add("<vision-context>\n$cached\n</vision-context>")
                    return@forEachIndexed
                }

                // 2. 未命中则调用视觉模型
                val description = analyzeImage(base64)
                descriptions.add(description)

                // 3. 写入缓存(去掉 <vision-context> 标签只存描述本体)
                if (sessionId != null && visionModelId != null && visionCache != null) {
                    val rawDescription = description
                        .removePrefix("<vision-context>\n")
                        .removeSuffix("\n</vision-context>")
                    visionCache.put(sessionId, base64, visionModelId, rawDescription)
                }
            } catch (e: VisionAnalysisException) {
                lastError = e
                Logger.w(TAG, "第 ${index + 1}/${images.size} 张图片分析失败: ${e.message}")
            }
        }
        if (descriptions.isEmpty() && lastError != null) {
            throw lastError
        }
        return descriptions
    }

    /**
     * 构建视觉上下文前缀,注入到用户消息开头。
     *
     * @param descriptions 视觉描述列表(已含 `<vision-context>` 标签)
     * @return 拼接后的前缀文本;空列表返回空串
     */
    fun buildVisionContext(descriptions: List<String>): String {
        if (descriptions.isEmpty()) return ""
        return descriptions.joinToString("\n\n") + "\n\n"
    }

    /**
     * 构建视觉辅助失败提示文本。
     *
     * 参考 openhanako 的 `visionFailureNotice`:把失败信息注入到用户消息中,
     * 让纯文本模型明确知道"本轮没有图片内容",并引导它请求用户重试或检查配置。
     *
     * @param reason 失败原因,可为 null
     * @return 用于注入用户消息的提示文本
     */
    fun buildFailureNotice(reason: String?): String {
        val detail = if (reason.isNullOrBlank()) "" else "($reason)"
        return """
            <vision-context>
            [图片分析失败:辅助视觉模型暂时不可用,本轮不会把图片内容传给文本模型。请明确说明你没有看到图片,并请用户稍后重试或检查视觉模型配置$detail。]
            </vision-context>
        """.trimIndent()
    }
}
