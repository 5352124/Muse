package io.zer0.ai.image

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 5-G: 图片生成服务。
 *
 * 设计选择:
 *  - 直接用 OkHttp POST 到 {baseUrl}/images/generations,不走 Provider 抽象
 *    (绘图是独立 API,与 chat completions 协议差异大)
 *  - 仅支持 OpenAI 兼容 Provider(Anthropic / Gemini 绘图 API 协议不同,留后续)
 *  - response_format=url,返回 URL 列表,由 Coil 在 UI 层加载
 *
 * 限制:
 *  - 国产 OpenAI 兼容服务可能不支持绘图端点,调用会失败
 *  - 生成耗时较长(10-30s),UI 层需 Loading 指示
 */
class ImageService(
    private val configStore: ProviderConfigStore,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * v0.35: 生成图片。返回图片 URL/base64 列表(通常 1 张,n=1)。
     *
     * 若 [params.referenceImageUri] 非空,则调用 /images/edits 进行图生图;
     * 否则调用 /images/generations 进行文生图。
     *
     * @param prompt 图片描述/修改指令
     * @param params 绘图参数(模型/尺寸/质量/风格/数量/参考图等)
     */
    suspend fun generate(
        prompt: String,
        params: ImageGenParams = ImageGenParams(),
        providerConfig: io.zer0.ai.core.ProviderConfig? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val config = providerConfig ?: configStore.get()
            ?: error(ErrorCode.NO_PROVIDER_CONFIGURED.toMessage())
        if (config.apiKey.isBlank()) error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        if (config.type != ProviderType.OPENAI) {
            error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage(config.type.toString()))
        }

        // L-IMG7: 关键参数日志
        Logger.i("ImageService", "generate: model=${params.model} size=${params.size} n=${params.n}")

        val model = ImageModelCatalog.resolveById(params.model)
        val validated = validateParams(params, model)
        val hasReference = !validated.referenceImageUri.isNullOrBlank()

        // M-IMG1: 读取 ProviderSpecificConfig.OpenAI.imagesPath 拼接端点(替代硬编码)
        val specific = (config.resolvedSpecific() as? ProviderSpecificConfig.OpenAI)
            ?: ProviderSpecificConfig.OpenAI()
        val generationsPath = specific.imagesPath.trim().trim('/').ifBlank { "images/generations" }
        // edits 路径:把末尾 generations 段替换为 edits;无法识别时回退 images/edits
        val editsPath = if (generationsPath.endsWith("generations")) {
            generationsPath.removeSuffix("generations").trimEnd('/') + "/edits"
        } else {
            "images/edits"
        }
        val path = if (hasReference) editsPath else generationsPath
        val baseUrl = config.resolvedBaseUrl()
        val url = "${baseUrl.trimEnd('/')}/$path"

        val request = if (hasReference) {
            buildEditsRequest(url, config.apiKey, prompt, validated)
        } else {
            buildGenerationsRequest(url, config.apiKey, prompt, validated)
        }

        try {
            execWithRetry(request).use { resp ->
                if (!resp.isSuccessful) {
                    // H-IMG1: 错误体安全读取 + 状态码分类 + OpenAI 错误结构解析
                    val body = ProviderHttpSupport.readBodyCapped(resp)
                    val openAiMsg = parseOpenAiError(body)
                    val hint = when (resp.code) {
                        401, 403 -> ErrorCode.AUTH_FAILED.toMessage()
                        429 -> ErrorCode.RATE_LIMITED.toMessage()
                        in 500..599 -> ErrorCode.SERVICE_UNAVAILABLE.toMessage()
                        else -> null
                    }
                    val msg = buildString {
                        append(ErrorCode.IMAGE_GEN_FAILED.toMessage())
                        append(" HTTP ${resp.code}")
                        hint?.let { append(" [").append(it).append("]") }
                        openAiMsg?.let { append(": ").append(it) }
                        if (hint == null && openAiMsg == null && body.isNotBlank()) {
                            append(": ").append(body)
                        }
                    }
                    Logger.w("ImageService", "image gen HTTP ${resp.code}")
                    error(msg)
                }
                // M-IMG3: 成功响应体限制 20MB,防止 b64_json 多图场景峰值内存过高
                // (contentLength 为 -1 时无法预判,只能信任服务端;此处做力所能及的防护)
                val declaredLen = resp.body?.contentLength() ?: -1L
                if (declaredLen > MAX_RESPONSE_BODY_BYTES) {
                    error(ErrorCode.IMAGE_RESPONSE_TOO_LARGE.toMessage(declaredLen / 1024 / 1024))
                }
                val respBody = ProviderHttpSupport.readBodySafely(resp)
                if (respBody.isBlank()) error(ErrorCode.IMAGE_EMPTY_RESPONSE.toMessage())
                val root = json.parseToJsonElement(respBody).jsonObject
                val data = root["data"]?.jsonArray
                    ?: error(ErrorCode.INVALID_RESPONSE.toMessage("missing_data"))
                if (data.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
                // M-IMG8: b64_json data URI 的 mime 从模型推断(默认 png)
                val mime = model?.outputMime ?: "image/png"
                data.mapNotNull { item ->
                    val obj = item.jsonObject
                    when {
                        validated.responseFormat == "b64_json" ->
                            obj["b64_json"]?.jsonPrimitive?.content?.let { "data:$mime;base64,$it" }
                        validated.responseFormat == "url" ->
                            obj["url"]?.jsonPrimitive?.content
                        else -> // response_format 被清空(gpt-image-1),优先 b64_json 再 url
                            obj["b64_json"]?.jsonPrimitive?.content?.let { "data:$mime;base64,$it" }
                                ?: obj["url"]?.jsonPrimitive?.content
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // error() 抛出的业务错误,原样向上传播
            throw e
        } catch (e: Exception) {
            // M-IMG4: 改为 catch(Exception),避免捕获 OOM/StackOverflow 等 JVM 致命错误
            // L-IMG1: 统一错误消息
            Logger.w("ImageService", "image gen failed: ${e.message}")
            error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
        }
    }

    /**
     * v0.36: 根据模型能力校验并修正参数,避免把不支持的尺寸/质量/风格/数量发到服务端。
     *
     * - 已知模型:尺寸/质量/风格 fallback 到模型默认值;n 被限制在 [1, maxN]。
     * - 自定义/未收录模型:使用宽松默认值校验,其余原样透传。
     * - 参考图仅在模型明确支持时才保留;不支持时显式报错(L-IMG9)。
     * - b64_json 仅在模型支持时才保留,否则改为 url。
     * - gpt-image-1 不支持 response_format 参数,清空该字段(H-IMG2)。
     */
    private fun validateParams(params: ImageGenParams, model: ImageModel?): ImageGenParams {
        if (model == null) {
            // L-IMG10: 未知/空模型保守取 n=1
            return params.copy(n = 1)
        }

        // L-IMG9: 参考图不支持时显式报错而非静默清空
        if (!params.referenceImageUri.isNullOrBlank() && !model.supportsReferenceImage) {
            error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage(model.id))
        }

        val size = params.size.takeIf { it in model.supportedSizes } ?: model.defaultSize
        val quality = params.quality
            .takeIf { it.isBlank() || it in model.supportedQualities }
            ?: model.defaultQuality
        val style = params.style
            .takeIf { it.isBlank() || it in model.supportedStyles }
            ?: model.defaultStyle
        // L-IMG3: takeIf 为死代码 — 上方已校验不支持参考图时会 error(),此处直接透传
        val referenceImageUri = params.referenceImageUri
        val responseFormat = when {
            !model.supportsResponseFormatParam -> ""
            !model.supportsB64Json && params.responseFormat == "b64_json" -> "url"
            else -> params.responseFormat
        }

        return params.copy(
            model = params.model,
            size = size,
            quality = quality,
            style = style,
            n = params.n.coerceIn(1, model.maxN),
            referenceImageUri = referenceImageUri,
            responseFormat = responseFormat,
        )
    }

    private fun buildGenerationsRequest(
        url: String,
        apiKey: String,
        prompt: String,
        params: ImageGenParams,
    ): Request {
        val body = buildJsonObject {
            put("prompt", prompt)
            // L-IMG5: 不再重复 coerce n,信任 validateParams
            put("n", params.n)
            put("size", params.size)
            if (params.model.isNotBlank()) put("model", params.model)
            if (params.quality.isNotBlank()) put("quality", params.quality)
            if (params.style.isNotBlank()) put("style", params.style)
            // H-IMG2: responseFormat 为空时不 put(gpt-image-1 不支持 response_format)
            if (params.responseFormat.isNotBlank()) put("response_format", params.responseFormat)
        }.toString()

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private suspend fun buildEditsRequest(
        url: String,
        apiKey: String,
        prompt: String,
        params: ImageGenParams,
    ): Request {
        // v1.73: 局部变量捕获避免 NPE,改抛业务异常而非 NullPointerException
        val refUri = params.referenceImageUri ?: error(ErrorCode.IMAGE_INVALID_URI.toMessage("no_ref"))
        val imageBytes = resolveImageBytes(refUri) ?: error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("no_bytes"))
        // L-IMG4: 参考图 media type 从字节头推断
        val mime = inferImageMime(imageBytes)
        val ext = mime.substringAfter('/')
        val imageBody = imageBytes.toRequestBody(mime.toMediaType())
        val promptBody = prompt.toRequestBody("text/plain".toMediaType())

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "reference.$ext", imageBody)
            .addFormDataPart("prompt", null, promptBody)
            // L-IMG5: 不再重复 coerce n
            .addFormDataPart("n", params.n.toString())
            .addFormDataPart("size", params.size)
        // H-IMG2: responseFormat 为空时不 put
        if (params.responseFormat.isNotBlank()) builder.addFormDataPart("response_format", params.responseFormat)
        if (params.model.isNotBlank()) builder.addFormDataPart("model", params.model)

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(builder.build())
            .build()
    }

    /**
     * 把参考图 URI 解析为字节数组。
     * 支持 http/https URL、data URI 和本地 file URI。
     *
     * M-IMG1: HTTP 下载改为 suspend,复用 [exec] 的 suspendCancellableCoroutine + enqueue 模式,
     * 协程取消时 call.cancel() 中断阻塞的网络调用。
     * M-IMG3: content:// URI 需 Android ContentResolver,ai 模块为纯 JVM 无法访问,
     * 由 app 层在调用前复制为本地文件。
     */
    private suspend fun resolveImageBytes(uri: String): ByteArray? {
        return when {
            uri.startsWith("data:image") -> {
                val base64 = uri.substringAfter("base64,", "")
                // M-IMG2: base64 长度 ≈ 字节数 * 4/3,解码前校验避免 OOM
                if (base64.length > MAX_REF_IMAGE_BYTES * 4 / 3) {
                    error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(base64.length / 1024 / 1024))
                }
                // L-IMG11: 用 java.util.Base64 保持 ai 模块纯 JVM
                java.util.Base64.getDecoder().decode(base64)
            }
            uri.startsWith("content://") -> {
                // 安全兜底:app 层(InputBar.kt)在存储 referenceImageUri 时已将 content:// URI
                // 通过 ContentResolver 读取并转为 data:image URI,正常情况下不会走到此分支。
                // 若仍走到此(如直接构造 ImageGenParams),需退回到通过 ContentResolver 转换。
                error(ErrorCode.IMAGE_INVALID_URI.toMessage("content_uri"))
            }
            uri.startsWith("http") -> {
                // M-IMG1/M-IMG6: 参考图 HTTP 下载用 suspendCancellableCoroutine + enqueue(复用 exec),
                // 支持协程取消;大小限制 ≤ 10MB
                val request = Request.Builder().url(uri).build()
                exec(request).use { resp ->
                    if (!resp.isSuccessful) error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("HTTP ${resp.code}"))
                    val len = resp.body?.contentLength() ?: -1L
                    if (len > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(len / 1024 / 1024))
                    }
                    val bytes = resp.body?.bytes() ?: error(ErrorCode.IMAGE_REFERENCE_DOWNLOAD_FAILED.toMessage("empty"))
                    if (bytes.size > MAX_REF_IMAGE_BYTES) {
                        error(ErrorCode.IMAGE_REFERENCE_TOO_LARGE.toMessage(bytes.size / 1024 / 1024))
                    }
                    bytes
                }
            }
            else -> {
                java.io.File(uri.removePrefix("file:")).takeIf { it.exists() }?.readBytes()
            }
        }
    }

    /**
     * L-IMG4: 从字节头推断图片 MIME 类型。
     */
    private fun inferImageMime(bytes: ByteArray): String {
        if (bytes.size < 4) return "image/png"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() -> "image/gif"
            // L-IMG1: RIFF 头需再校验 bytes[8..11]="WEBP",否则把任意 RIFF 容器误判为 webp
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"
            else -> "image/png"
        }
    }

    /**
     * H-IMG1: 尝试解析 OpenAI 错误结构 {"error":{"message","code","type"}}。
     * 返回 message 字段;无法解析返回 null。
     */
    private fun parseOpenAiError(body: String): String? {
        if (body.isBlank()) return null
        // L-IMG4: 用 resultOf 替代 runCatching,避免吞 CancellationException
        return resultOf {
            val root = json.parseToJsonElement(body).jsonObject
            val err = root["error"]?.jsonObject ?: return@resultOf null
            err["message"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * M-IMG2: 协程取消可传播 — 用 suspendCancellableCoroutine + enqueue,
     * 协程取消时调用 call.cancel(),中断阻塞的网络调用。
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

    /**
     * L-IMG8: 对超时/429 做有限重试(1 次)。
     * 图片生成按次计费,仅对未产生生成的失败(超时/429)重试。
     */
    private suspend fun execWithRetry(request: Request): Response {
        try {
            val resp = exec(request)
            // 429: 关闭后重试一次
            if (resp.code != 429) return resp
            // L-IMG2: 优先按 Retry-After 头退避(秒),无则默认 1s
            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
            resp.close()
            delay(retryAfter?.let { it * 1000L } ?: 1000L)
            return exec(request)
        } catch (e: java.net.SocketTimeoutException) {
            // 超时:重试一次
        }
        // 其他 IOException/异常直接向上抛,不重试
        delay(1000L)
        return exec(request)
    }

    companion object {
        /** M-IMG6: 参考图大小上限 10MB。 */
        private const val MAX_REF_IMAGE_BYTES = 10L * 1024 * 1024

        /** M-IMG3: 成功响应体大小上限 20MB(防止 b64_json 多图峰值内存)。 */
        private const val MAX_RESPONSE_BODY_BYTES = 20L * 1024 * 1024
    }
}

/**
 * v0.34: 图片生成请求参数。
 */
data class ImageGenParams(
    /** 绘图模型 ID,如 dall-e-3 / gpt-image-1。 */
    val model: String = "",
    /** 图片尺寸,如 1024x1024 / 1792x1024 / 1024x1792。 */
    val size: String = "1024x1024",
    /** 图片质量:standard / hd。 */
    val quality: String = "standard",
    /** 图片风格:vivid / natural。 */
    val style: String = "vivid",
    /** 返回格式:url / b64_json。 */
    val responseFormat: String = "url",
    /** 生成数量。 */
    val n: Int = 1,
    /**
     * v0.35: 参考图 URI(http/https/data URI/本地 file)。
     * 非空时调用 /images/edits 进行图生图/参考图生成。
     */
    val referenceImageUri: String? = null,
)
