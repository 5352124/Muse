package io.zer0.ai.video

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * P2-8: 可灵(Kling)视频生成 Provider 实现。
 *
 * API 参考: https://kling.kuaishou.com/docs
 *
 * 端点:
 *  - 文生视频: POST {baseUrl}/videos/generations/text2video
 *  - 图生视频: POST {baseUrl}/videos/generations/image2video
 *  - 查询任务: GET  {baseUrl}/videos/generations/{taskId}
 *
 * 认证: Bearer Token(API Key 直接放 Authorization 头)
 *
 * 任务状态映射:
 *  - "submit"     → [VideoTaskStatus.PENDING]
 *  - "processing" → [VideoTaskStatus.PROCESSING]
 *  - "succeed"    → [VideoTaskStatus.SUCCESS]
 *  - "failed"     → [VideoTaskStatus.FAILED]
 *
 * @param client OkHttp 客户端(由 Koin 注入 named("chat"),已配 30s connect / 300s read 超时)
 * @param baseUrl 可灵 API 基础 URL,默认 https://api.klingai.com/v1
 */
class KlingVideoProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : VideoGenerationProvider {

    override val providerId: String = "kling"
    override val displayName: String = "可灵 Kling"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 提交任务后缓存的 apiKey,供后续 queryTask/waitForCompletion 使用。
     *
     * 注:由于 [VideoGenerationProvider.queryTask] 接口签名不传 apiKey,
     * 实际查询时从此字段读取(由 [submitTask] 缓存)。
     * 多任务并发时会覆盖前一个 apiKey(已知限制,后续可改为 taskId→apiKey map)。
     */
    @Volatile
    private var apiKeyForQuery: String = ""

    /**
     * 提交视频生成任务。
     *
     * 根据 [VideoGenerationRequest.imageUrl] 是否非空,自动选择:
     *  - 非空 → POST /videos/generations/image2video(图生视频)
     *  - 空   → POST /videos/generations/text2video(文生视频)
     *
     * @return Result.success(taskId) 或 Result.failure(exception)
     */
    override suspend fun submitTask(request: VideoGenerationRequest): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (request.apiKey.isBlank()) {
                    error("Kling API key is empty")
                }
                if (request.prompt.isBlank()) {
                    error("Prompt is empty")
                }

                val path = if (!request.imageUrl.isNullOrBlank()) "image2video" else "text2video"
                val url = "${baseUrl.trimEnd('/')}/videos/generations/$path"

                val body = buildRequestBody(request)
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                Logger.i(
                    TAG,
                    "submitTask: model=${request.model} duration=${request.duration} " +
                        "resolution=${request.resolution} hasImage=${!request.imageUrl.isNullOrBlank()}",
                )

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        val apiMsg = parseApiErrorMessage(respBody)
                        error(
                            "Kling submit failed: HTTP ${resp.code}" +
                                (apiMsg?.let { ": $it" } ?: if (respBody.isNotBlank()) ": $respBody" else ""),
                        )
                    }
                    val root = json.parseToJsonElement(respBody).jsonObject
                    val code = root["code"]?.jsonPrimitive?.content
                    // 可灵 API 业务错误:code != 0
                    if (code != null && code != "0") {
                        val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
                        error("Kling submit API error: code=$code message=$msg")
                    }
                    val data = root["data"]?.jsonObject
                        ?: error("Kling submit response missing data: $respBody")
                    val taskId = data["task_id"]?.jsonPrimitive?.content
                        ?: error("Kling submit response missing task_id: $respBody")
                    // 缓存 apiKey 供后续 queryTask/waitForCompletion 使用
                    apiKeyForQuery = request.apiKey
                    Logger.i(TAG, "submitTask success: taskId=$taskId")
                    taskId
                }
            }
        }

    /**
     * 查询任务状态(对外只返回 [VideoTaskStatus])。
     */
    override suspend fun queryTask(taskId: String): Result<VideoTaskStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                queryTaskInternal(taskId).status
            }
        }

    /**
     * 轮询等待任务完成,返回视频 URL。
     *
     * 默认超时 5 分钟([DEFAULT_POLL_TIMEOUT_MS]),每 [POLL_INTERVAL_MS] 轮询一次。
     * 任务状态变为 SUCCESS 时返回视频 URL;FAILED 时抛出异常;超时返回 failure。
     */
    override suspend fun waitForCompletion(
        taskId: String,
        timeoutMs: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val effectiveTimeout = if (timeoutMs > 0) timeoutMs else DEFAULT_POLL_TIMEOUT_MS
            val result = withTimeoutOrNull(effectiveTimeout) {
                while (true) {
                    val taskResult = queryTaskInternal(taskId)
                    when (taskResult.status) {
                        VideoTaskStatus.SUCCESS -> return@withTimeoutOrNull taskResult
                        VideoTaskStatus.FAILED ->
                            error(taskResult.errorMessage ?: "Kling task failed")
                        VideoTaskStatus.PENDING,
                        VideoTaskStatus.PROCESSING -> {
                            // 继续轮询
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } ?: error("Kling task timeout (taskId=$taskId, timeoutMs=$effectiveTimeout)")

            val videoUrl = result.videoUrl
                ?: error("Kling task succeeded but no video URL: taskId=$taskId")
            Logger.i(TAG, "waitForCompletion success: taskId=$taskId url=$videoUrl")
            videoUrl
        }
    }

    /**
     * 查询任务完整结果(内部使用,携带视频 URL 与错误信息)。
     */
    private suspend fun queryTaskInternal(taskId: String): VideoTaskResult {
        val url = "${baseUrl.trimEnd('/')}/videos/generations/$taskId"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKeyForQuery")
            .get()
            .build()

        return exec(httpRequest).use { resp ->
            val respBody = readBody(resp)
            if (!resp.isSuccessful) {
                return VideoTaskResult(
                    status = VideoTaskStatus.FAILED,
                    errorMessage = "Kling query HTTP ${resp.code}: $respBody",
                )
            }
            val root = json.parseToJsonElement(respBody).jsonObject
            val code = root["code"]?.jsonPrimitive?.content
            if (code != null && code != "0") {
                val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
                return VideoTaskResult(
                    status = VideoTaskStatus.FAILED,
                    errorMessage = "Kling query API error: code=$code message=$msg",
                )
            }
            val data = root["data"]?.jsonObject
                ?: return VideoTaskResult(
                    status = VideoTaskStatus.FAILED,
                    errorMessage = "Kling query response missing data: $respBody",
                )

            val statusStr = data["task_status"]?.jsonPrimitive?.content ?: "unknown"
            val status = mapStatus(statusStr)

            when (status) {
                VideoTaskStatus.SUCCESS -> {
                    val videoUrl = data["task_result"]?.jsonObject
                        ?.get("videos")?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("url")?.jsonPrimitive?.content
                    VideoTaskResult(status = status, videoUrl = videoUrl)
                }
                VideoTaskStatus.FAILED -> {
                    val failInfo = data["task_fail_info"]?.jsonObject
                    val errMsg = failInfo?.get("message")?.jsonPrimitive?.content
                        ?: "Kling task failed (status=$statusStr)"
                    VideoTaskResult(status = status, errorMessage = errMsg)
                }
                else -> VideoTaskResult(status = status)
            }
        }
    }

    /**
     * 构造请求体 JSON(文生视频 / 图生视频通用)。
     */
    private fun buildRequestBody(request: VideoGenerationRequest): JsonObject {
        return buildJsonObject {
            put("model", request.model.ifBlank { DEFAULT_MODEL })
            put("prompt", request.prompt)
            // duration 必须是 5 或 10(可灵限制),非法则回退 5
            val duration = if (request.duration == 5 || request.duration == 10) request.duration else 5
            put("duration", duration)
            put("resolution", request.resolution.ifBlank { "720p" })
            // mode 默认 std(标准模式),pro 模式需付费更高,不暴露
            put("mode", "std")
            if (!request.imageUrl.isNullOrBlank()) {
                put("image", request.imageUrl)
            }
        }
    }

    /**
     * 把可灵 task_status 字符串映射到 [VideoTaskStatus]。
     */
    private fun mapStatus(statusStr: String): VideoTaskStatus {
        return when (statusStr.lowercase()) {
            "submit", "submitted", "pending" -> VideoTaskStatus.PENDING
            "processing", "running" -> VideoTaskStatus.PROCESSING
            "succeed", "success", "succeeds" -> VideoTaskStatus.SUCCESS
            "failed", "fail", "error" -> VideoTaskStatus.FAILED
            else -> {
                Logger.w(TAG, "unknown kling task_status: $statusStr")
                VideoTaskStatus.PENDING
            }
        }
    }

    /**
     * 尝试解析可灵 API 错误响应 {"code": non-zero, "message": "..."}。
     */
    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * 读取响应体(限制 1MB,防止异常响应导致 OOM)。
     */
    private fun readBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("Kling response too large: ${bytes.size} bytes")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 协程取消可传播的 OkHttp 请求执行(参考 ImageService.exec)。
     */
    private suspend fun exec(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    companion object {
        private const val TAG = "KlingVideoProvider"

        /** 可灵 API 默认基础 URL。 */
        const val DEFAULT_BASE_URL = "https://api.klingai.com/v1"

        /** 默认模型(可灵 v1,5 秒视频生成)。 */
        const val DEFAULT_MODEL = "kling-v1"

        /** 轮询间隔(5 秒)。 */
        private const val POLL_INTERVAL_MS = 5_000L

        /** 默认轮询超时(5 分钟)。 */
        private const val DEFAULT_POLL_TIMEOUT_MS = 5L * 60 * 1000

        /** 响应体大小上限 1MB(任务查询响应通常 < 10KB,1MB 兜底)。 */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
    }
}
