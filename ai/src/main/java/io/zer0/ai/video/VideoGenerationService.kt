package io.zer0.ai.video

/**
 * P2-8: 视频生成服务。
 *
 * 设计选择:
 *  - 直接用 OkHttp 调用各家视频生成 API,不走 Provider 抽象
 *    (视频生成是异步任务模型,与 chat completions 协议差异大)
 *  - 每个 Provider 实现 [VideoGenerationProvider] 接口,
 *    [VideoGenerationService] 按 providerId 路由到对应实现
 *  - 当前已实现 [KlingVideoProvider](可灵);后续可扩展 Runway/Pika 等
 *
 * 任务流程:
 *  1. submitTask(request) → 返回 taskId
 *  2. queryTask(taskId) → 返回 [VideoTaskStatus]
 *  3. waitForCompletion(taskId) → 轮询直到 SUCCESS/FAILED/超时,返回视频 URL
 *
 * 限制:
 *  - 生成耗时较长(30s ~ 5min),UI 层需 Loading 指示
 *  - 部分 Provider 不支持图生视频(imageUrl 为空时走文生视频)
 */

/**
 * 视频生成 Provider 接口。
 *
 * 每个 Provider 实现:
 *  - [submitTask]: 提交生成任务,返回任务 ID
 *  - [queryTask]: 查询任务状态
 *  - [waitForCompletion]: 轮询直到完成,返回视频 URL
 */
interface VideoGenerationProvider {
    /** Provider 唯一标识(如 "kling" / "runway")。 */
    val providerId: String

    /** UI 展示名称(如「可灵」/「Runway」)。 */
    val displayName: String

    /**
     * 提交视频生成任务,返回任务 ID。
     *
     * @param request 生成请求(prompt / model / 参考图 / 时长 / 分辨率 / apiKey)
     * @return Result.success(taskId) 或 Result.failure(exception)
     */
    suspend fun submitTask(request: VideoGenerationRequest): Result<String>

    /**
     * 查询任务状态。
     *
     * @param taskId 由 [submitTask] 返回的任务 ID
     * @return Result.success([VideoTaskStatus]) 或 Result.failure(exception)
     */
    suspend fun queryTask(taskId: String): Result<VideoTaskStatus>

    /**
     * 等待任务完成(轮询,默认超时 5 分钟)。
     *
     * @param taskId 由 [submitTask] 返回的任务 ID
     * @param timeoutMs 超时时间(毫秒),默认 5 分钟
     * @return Result.success(videoUrl) 或 Result.failure(exception)
     */
    suspend fun waitForCompletion(taskId: String, timeoutMs: Long = 5 * 60 * 1000): Result<String>
}

/**
 * 视频生成请求参数。
 *
 * @param prompt 视频描述/提示词
 * @param model 模型 ID(如 kling-v1 / kling-v2)
 * @param imageUrl 参考图 URL(非空时走图生视频,为空时走文生视频)
 * @param duration 视频时长(秒,通常 5 或 10)
 * @param resolution 分辨率(如 720p / 1080p)
 * @param apiKey Provider API Key
 */
data class VideoGenerationRequest(
    val prompt: String,
    val model: String,
    val imageUrl: String? = null,
    val duration: Int = 5,
    val resolution: String = "720p",
    val apiKey: String,
    /**
     * v1.136: 视频生成基础 URL。
     *
     * 通用 OpenAI 兼容 Provider 使用;留空时回退到 OpenAI 官方默认值。
     * Kling 等专用 Provider 忽略此字段(使用自身 baseUrl)。
     */
    val baseUrl: String? = null,
    /**
     * v1.136: 视频生成端点路径。
     *
     * 留空时通用 Provider 使用 /videos/generations。
     */
    val videoGenerationsPath: String? = null,
)

/**
 * 视频任务状态。
 */
enum class VideoTaskStatus {
    /** 排队中(任务已提交,尚未开始处理)。 */
    PENDING,

    /** 处理中(模型正在生成视频)。 */
    PROCESSING,

    /** 成功(视频已生成,可下载视频 URL)。 */
    SUCCESS,

    /** 失败(任务出错或被拒绝)。 */
    FAILED,
}

/**
 * 视频任务完整结果(内部使用,包含视频 URL 与错误信息)。
 *
 * [VideoGenerationProvider.queryTask] 仅返回 [VideoTaskStatus],
 * 实现内部通过本类型携带视频 URL 和错误信息,
 * 由 [VideoGenerationProvider.waitForCompletion] 提取 videoUrl 返回。
 *
 * @param status 任务状态
 * @param videoUrl 视频下载 URL(status=SUCCESS 时非空)
 * @param errorMessage 错误信息(status=FAILED 时非空)
 */
data class VideoTaskResult(
    val status: VideoTaskStatus,
    val videoUrl: String? = null,
    val errorMessage: String? = null,
)

/**
 * v1.136: 视频生成统一服务 — 按 [providerId] 路由到对应 [VideoGenerationProvider]。
 *
 * 不再硬编码 provider ID,而是通过构造时传入的 provider map 动态解析。
 * 对于未显式注册的 providerId(如用户自定义 OpenAI 兼容供应商),
 * 若存在 [GenericOpenAiVideoProvider],则作为通用兜底。
 *
 * 用法:
 * ```
 * val service = VideoGenerationService(mapOf(
 *     "kling" to KlingVideoProvider(client),
 *     GenericOpenAiVideoProvider.PROVIDER_ID to GenericOpenAiVideoProvider(client),
 * ))
 * val videoUrl = service.generateVideo("kling", request).getOrThrow()
 * ```
 *
 * @param providers providerId -> 实现的映射;key 建议与 [ProviderConfig.id] 对齐
 * @param genericProvider 通用 OpenAI 兼容兜底实现,未命中 map 时使用
 */
class VideoGenerationService(
    private val providers: Map<String, VideoGenerationProvider>,
    private val genericProvider: GenericOpenAiVideoProvider? = null,
) {
    /**
     * 生成视频 — 提交任务 → 等待完成 → 返回视频 URL。
     *
     * @param providerId Provider 标识(如 "kling")
     * @param request 生成请求
     * @return Result.success(videoUrl) 或 Result.failure(exception)
     */
    suspend fun generateVideo(
        providerId: String,
        request: VideoGenerationRequest,
    ): Result<String> {
        val provider = resolveProvider(providerId)
            ?: return Result.failure(IllegalArgumentException("Unknown video provider: $providerId"))

        // 提交任务
        val taskId = provider.submitTask(request).getOrElse { e ->
            return Result.failure(e)
        }

        // 等待完成
        return provider.waitForCompletion(taskId)
    }

    /**
     * 仅提交任务(不等待完成),返回任务 ID。
     * 适用于 UI 层需要手动轮询的场景。
     *
     * @param providerId Provider 标识
     * @param request 生成请求
     * @return Result.success(taskId) 或 Result.failure(exception)
     */
    suspend fun submitTask(
        providerId: String,
        request: VideoGenerationRequest,
    ): Result<String> {
        val provider = resolveProvider(providerId)
            ?: return Result.failure(IllegalArgumentException("Unknown video provider: $providerId"))
        return provider.submitTask(request)
    }

    /**
     * 查询任务状态(用于 UI 层手动轮询)。
     *
     * @param providerId Provider 标识
     * @param taskId 任务 ID
     * @return Result.success([VideoTaskStatus]) 或 Result.failure(exception)
     */
    suspend fun queryTask(
        providerId: String,
        taskId: String,
    ): Result<VideoTaskStatus> {
        val provider = resolveProvider(providerId)
            ?: return Result.failure(IllegalArgumentException("Unknown video provider: $providerId"))
        return provider.queryTask(taskId)
    }

    /**
     * 根据 providerId 路由到对应实现。
     *
     * 优先查找显式注册的 provider;未命中时,若存在通用 OpenAI 兼容 Provider 则兜底返回。
     *
     * @param providerId Provider 标识(通常与 [io.zer0.ai.core.ProviderConfig.id] 一致)
     * @return 对应的 [VideoGenerationProvider],未注册且没有兜底时返回 null
     */
    private fun resolveProvider(providerId: String): VideoGenerationProvider? {
        return providers[providerId] ?: genericProvider
    }

    companion object {
        /**
         * 已注册的 Provider ID 列表(用于 UI 层展示可选 Provider)。
         *
         * 包含显式注册 key 与通用兜底 key(如果有)。
         */
        fun availableProviderIds(providers: Map<String, VideoGenerationProvider>): List<String> =
            providers.keys.toList()
    }
}
