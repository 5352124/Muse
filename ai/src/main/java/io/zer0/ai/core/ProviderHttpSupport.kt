package io.zer0.ai.core

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * v1.80 (M3+M5): AI Provider HTTP 公共支持。
 *
 * 集中三家 Provider(OpenAI / Gemini / Anthropic)的共用 HTTP 逻辑:
 *  - OkHttpClient 构建(统一超时配置,消除三处硬编码)
 *  - 错误体安全读取(消除 `runCatching { resp.body.string() }.getOrDefault("")` 重复)
 *  - HTTP 状态码分类(支撑差异化重试/提示)
 *
 * v1.73 已记录 M3(三 Provider 异常处理同构可抽 ProviderHttpSupport)和
 * M5(错误体 runCatching 重复),此处统一落地。
 */
abstract class ProviderHttpSupport(
    protected val config: ProviderConfig,
) : Provider {

    /**
     * 共享 OkHttpClient — 所有 Provider 统一超时配置。
     *
     * v1.80 (M-HTTP1): 改为引用 companion object 中的全局单例 [sharedHttpClient],
     * 避免每个 ProviderHttpSupport 子类实例化时各建一个 OkHttpClient(浪费连接池资源)。
     *
     * 子类如需额外自定义(如 Gemini VertexAi 的短超时 client),
     * 可用 `httpClient.newBuilder().xxx().build()` 派生,共享连接池。
     */
    protected val httpClient: OkHttpClient get() = sharedHttpClient

    companion object {
        /** 错误体最大截取长度(防止超大 HTML 错误页撑爆日志/UI)。 */
        const val ERROR_BODY_MAX_LEN = 500

        /**
         * 全局共享 OkHttpClient 单例(M-HTTP1)。
         * 所有 ProviderHttpSupport 子类共用同一实例,共享连接池与超时配置。
         * 子类可用 `sharedHttpClient.newBuilder().xxx().build()` 派生差异化配置。
         *
         * v1.135 性能优化:
         *  - 扩大连接池到 10 个空闲连接、keep-alive 5 分钟,减少多 Provider / 并发请求时的
         *    连接重建与 TLS 握手开销。
         *  - 显式启用 HTTP/2 + HTTP/1.1 协商,对支持 HTTP/2 的端点降低延迟。
         *  - 启用 gzip(OkHttp 默认已开启),这里显式保留意图。
         */
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(ProviderHttpDefaults.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(ProviderHttpDefaults.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(ProviderHttpDefaults.WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(ProviderHttpDefaults.CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .connectionPool(
                    ConnectionPool(
                        maxIdleConnections = 10,
                        keepAliveDuration = 5,
                        timeUnit = TimeUnit.MINUTES,
                    ),
                )
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build()
        }

        /**
         * 安全读取 Response body,失败返回空串。
         * 替代各家重复的 `runCatching { resp.body.string() }.getOrDefault("")`。
         * 只捕获 IOException,不吞 CancellationException / OOM。
         * v1.97: 追加捕获 IllegalStateException — EventSourceListener/WebSocketListener
         * 回调中的 Response body 已被框架 stripped,读取会抛 IllegalStateException。
         */
        fun readBodySafely(response: Response): String = try {
            response.body?.string() ?: ""
        } catch (e: java.io.IOException) {
            ""
        } catch (e: IllegalStateException) {
            ""
        }

        /**
         * 安全读取 Response body 并截断到 [maxLen],防止超大错误体。
         */
        fun readBodyCapped(response: Response, maxLen: Int = ERROR_BODY_MAX_LEN): String {
            val body = readBodySafely(response)
            return if (body.length > maxLen) body.take(maxLen) else body
        }

        /**
         * 按 HTTP 状态码返回人类可读的错误分类,用于差异化提示/重试决策。
         * 返回 null 表示无需特殊分类(如 400/404 等业务错误)。
         */
        fun classifyHttpCode(code: Int): String? = when (code) {
            401 -> "authentication failed"
            403 -> "permission denied"
            408 -> "request timeout"
            429 -> "rate limited"
            in 500..599 -> "server error"
            else -> null
        }

        /**
         * 构建标准化的 HTTP 错误消息(含状态码分类 + 截断的 body 摘要)。
         * 各 Provider 的 parseErrorMessage 可在此基础上追加自家错误结构解析。
         */
        fun buildHttpErrorMessage(prefix: String, code: Int, body: String): String {
            val category = classifyHttpCode(code)
            return buildString {
                append(prefix).append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
                if (body.isNotBlank()) {
                    val capped = if (body.length > 200) body.take(200) else body
                    append(": ").append(capped)
                }
            }
        }
    }
}
