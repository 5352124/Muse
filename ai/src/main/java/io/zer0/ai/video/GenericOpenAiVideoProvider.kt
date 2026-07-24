package io.zer0.ai.video

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.136: 通用 OpenAI 兼容视频生成 Provider。
 *
 * 调用端点: POST {baseUrl}{videoGenerationsPath}(默认 /videos/generations)。
 * 认证: Bearer Token。
 *
 * 该 Provider 同时兼容两类响应形态:
 *  1. 同步返回: {"data":[{"url":"..."}]}
 *  2. 异步任务: 与 Kling 类似返回 task_id(目前按同步处理;如后续需要可扩展轮询)
 *
 * 对未知/自定义 OpenAI 兼容端点友好:只要供应商在模型能力中标记 supportsVideo,
 * 即可通过 [VideoGenerationService] 路由到本 Provider。
 *
 * @param client OkHttp 客户端
 */
class GenericOpenAiVideoProvider(
    private val client: OkHttpClient,
) : VideoGenerationProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "OpenAI 兼容视频"

    private val json = Json { ignoreUnknownKeys = true }

    /** 同步任务缓存:taskId -> 视频 URL / 错误信息。 */
    private val syncTasks = ConcurrentHashMap<String, String>()

    override suspend fun submitTask(request: VideoGenerationRequest): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (request.apiKey.isBlank()) {
                    error("视频生成 API Key 为空")
                }
                if (request.prompt.isBlank()) {
                    error("Prompt 为空")
                }

                val baseUrl = (request.baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')
                val path = request.videoGenerationsPath?.trim()?.trim('/')?.ifBlank { "videos/generations" }
                    ?: "videos/generations"
                val url = "$baseUrl/$path"

                val body = buildRequestBody(request)
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${request.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                Logger.i(
                    TAG,
                    "submitTask: model=${request.model} baseUrl=$baseUrl path=$path",
                )

                exec(httpRequest).use { resp ->
                    val respBody = readBody(resp)
                    if (!resp.isSuccessful) {
                        val apiMsg = parseApiErrorMessage(respBody)
                        error(
                            "视频生成请求失败: HTTP ${resp.code}" +
                                (apiMsg?.let { ": $it" } ?: if (respBody.isNotBlank()) ": $respBody" else ""),
                        )
                    }
                    val videoUrl = extractVideoUrl(respBody)
                        ?: error("视频生成响应中未找到视频 URL: $respBody")
                    // 同步任务:用固定前缀 + URL 作为 taskId,queryTask 可直接解析
                    val taskId = "$SYNC_PREFIX$videoUrl"
                    syncTasks[taskId] = videoUrl
                    Logger.i(TAG, "submitTask success: taskId=$taskId")
                    taskId
                }
            }
        }

    override suspend fun queryTask(taskId: String): Result<VideoTaskStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (taskId.startsWith(SYNC_PREFIX)) {
                    VideoTaskStatus.SUCCESS
                } else {
                    error("未知任务 ID:$taskId")
                }
            }
        }

    override suspend fun waitForCompletion(
        taskId: String,
        timeoutMs: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (taskId.startsWith(SYNC_PREFIX)) {
                // 9.3 修复: 任务终态后立即清理,避免失败/超时任务在 map 中堆积。
                // remove 原子取值并移除;若条目缺失则从 taskId 中解析 URL(同步任务 URL 编码在 taskId 内)
                syncTasks.remove(taskId)
                    ?: taskId.removePrefix(SYNC_PREFIX)
            } else {
                error("未知任务 ID:$taskId")
            }
        }
    }

    private fun buildRequestBody(request: VideoGenerationRequest): String {
        return buildJsonObject {
            put("model", request.model.ifBlank { DEFAULT_MODEL })
            put("prompt", request.prompt)
            if (request.duration > 0) put("duration", request.duration)
            if (request.resolution.isNotBlank()) put("resolution", request.resolution)
            if (!request.imageUrl.isNullOrBlank()) {
                put("image", request.imageUrl)
            }
        }.toString()
    }

    /**
     * 从响应体中提取视频 URL。
     *
     * 支持格式:
     *  - {"data":[{"url":"..."}]}
     *  - {"video":{"url":"..."}}
     *  - {"url":"..."}
     */
    private fun extractVideoUrl(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: root["video"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: root["url"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * 尝试解析 OpenAI 风格的错误响应 {"error":{"message":"..."}} 或普通 {"message":"..."}。
     */
    private fun parseApiErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun readBody(resp: Response): String {
        val bytes = resp.body.bytes()
        if (bytes.size > MAX_RESPONSE_BYTES) {
            error("视频生成响应过大: ${bytes.size} bytes")
        }
        return String(bytes, Charsets.UTF_8)
    }

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
        private const val TAG = "GenericOpenAiVideoProvider"

        /** Provider 唯一标识,在 [VideoGenerationService] 中注册为通用兜底。 */
        const val PROVIDER_ID = "openai_video_generic"

        /** 默认基础 URL(未传入 baseUrl 时回退)。 */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** 默认模型 ID。 */
        const val DEFAULT_MODEL = "gpt-4o-video"

        /** 同步任务 ID 前缀。 */
        private const val SYNC_PREFIX = "sync:"

        /** 响应体大小上限 1MB。 */
        private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
    }
}
