package io.zer0.muse.balance

import io.zer0.ai.core.ProviderConfig
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** M3: 缓存数组索引正则,避免每次调用重新编译。 */
private val ARRAY_INDEX_REGEX = Regex("""\[(\d+)]""")

/**
 * Phase 8.9: Provider 余额查询服务。
 *
 * 利用 [ProviderConfig.balanceApiPath] + [ProviderConfig.balanceResultPath] 字段
 * 查询 Provider 余额/用量,支持 OpenAI 兼容的 dashboard billing 端点。
 *
 * 协议:
 *  - HTTP GET `${baseUrl}${balanceApiPath}` 带 `Authorization: Bearer ${apiKey}`
 *  - 响应 JSON 用 [balanceResultPath] 提取(JsonPath 简易语法,如 `$.data.total_usage`)
 *  - 余额/用量数值原样返回字符串(UI 格式化由调用方处理)
 *
 * 局限:
 *  - 仅支持 GET 请求(部分 Provider 用 POST,留待扩展)
 *  - JsonPath 仅支持 `$.a.b.c` 直链路径 + 数组索引 `$.data[0].amount`
 *  - 不解析货币单位(由 UI 层根据 Provider 类型推断)
 *
 * @param client OkHttpClient(复用 named("chat") 客户端,带 30s 超时)
 */
class BalanceService(private val client: OkHttpClient) {

    /**
     * 查询 Provider 余额。
     * @param provider Provider 配置(必须含 balanceApiPath 和 balanceResultPath)
     * @return 余额字符串(如 "$12.34" / "5000 tokens"),查询失败返回 null
     */
    suspend fun queryBalance(provider: ProviderConfig): String? = withContext(Dispatchers.IO) {
        if (provider.balanceApiPath.isBlank() || provider.balanceResultPath.isBlank()) {
            return@withContext null
        }
        if (provider.apiKey.isBlank()) {
            return@withContext null
        }
        val url = buildUrl(provider.baseUrl, provider.balanceApiPath)
        if (url == null) {
            Logger.w("BalanceService", "Invalid URL: ${provider.baseUrl} + ${provider.balanceApiPath}")
            return@withContext null
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .get()
            .build()
        // M1: 改用 suspendCancellableCoroutine + enqueue,协程取消时可取消 HTTP 请求
        // (execute() 是阻塞调用,协程取消时无法中断;suspendCancellableCoroutine 配合
        //  invokeOnCancellation { call.cancel() } 可在协程取消时取消底层 HTTP 请求)
        // resultOf 是 inline 函数,在 suspend 上下文中可调用 suspendCancellableCoroutine
        resultOf {
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(req)
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            if (!resp.isSuccessful) {
                                Logger.w("BalanceService", "Balance query failed: HTTP ${resp.code}")
                                cont.resume(null)
                                return
                            }
                            // M2: body 可能为 null,加 null 检查避免 NPE
                            val body = resp.body?.string()
                            if (body == null) {
                                Logger.w("BalanceService", "Balance query failed: empty body")
                                cont.resume(null)
                                return
                            }
                            cont.resume(extractJsonPath(body, provider.balanceResultPath))
                        }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }
                })
                cont.invokeOnCancellation { call.cancel() }
            }
        }.onError { _, throwable ->
            Logger.e("BalanceService", "Balance query error", throwable)
        }.getOrNull()
    }

    /** 拼接 baseUrl + path,处理末尾/开头的斜杠。 */
    private fun buildUrl(baseUrl: String, path: String): String? {
        if (baseUrl.isBlank()) return null
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }

    /**
     * 简易 JsonPath 提取器。
     * 支持:
     *  - `$.field.subfield` 直链路径
     *  - `$.data[0].amount` 数组索引
     *  - `$` 根对象(返回整个 JSON)
     * 不支持过滤器 `$[?(@.price < 10)]` / 通配符 `$..*`。
     */
    private fun extractJsonPath(jsonStr: String, path: String): String? {
        // L6: 改用 resultOf 替代 runCatching(自定义 Result API,正确重抛 CancellationException)
        val element = resultOf {
            AppJson.parseToJsonElement(jsonStr)
        }.onError { msg, _ ->
            Logger.w("BalanceService", "JSON parse failed: $msg")
        }.getOrNull() ?: return null
        if (path.isBlank() || path == "$") return element.toString()
        // 解析路径段:去掉开头的 $.,按 . 分割,识别 [N] 数组索引
        val segments = path.removePrefix("$").removePrefix(".")
            .split(".")
            .filter { it.isNotBlank() }
        var current: JsonElement? = element
        for (seg in segments) {
            current = extractSegment(current, seg) ?: return null
        }
        return when (current) {
            is JsonPrimitive -> current.content
            is JsonElement -> current.toString()
            null -> null
        }
    }

    /** 处理单个路径段(可能含 [N] 数组索引)。 */
    private fun extractSegment(element: JsonElement?, segment: String): JsonElement? {
        if (element == null) return null
        // 提取数组索引(如 data[0] → field=data, index=0)
        val bracketIdx = segment.indexOf('[')
        val fieldName = if (bracketIdx >= 0) segment.substring(0, bracketIdx) else segment
        var current = element
        // 字段访问
        if (fieldName.isNotBlank()) {
            if (current !is JsonObject) return null
            current = current[fieldName] ?: return null
        }
        // 数组索引访问(可能有多个,如 a[0][1])
        if (bracketIdx >= 0) {
            // M3: 使用文件级缓存的 ARRAY_INDEX_REGEX,避免每次调用重新编译
            val indices = ARRAY_INDEX_REGEX.findAll(segment).map { it.groupValues[1].toInt() }.toList()
            for (idx in indices) {
                if (current !is JsonArray || idx >= current.size) return null
                current = current[idx]
            }
        }
        return current
    }
}
