package io.zer0.muse.asr

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable

/**
 * Phase 9.3 (M2): ASR Provider 类型枚举。
 *
 * - [SYSTEM]: 系统 ACTION_RECOGNIZE_SPEECH Intent(依赖 Google/厂商服务,国产 ROM 可能缺失)
 * - [DASHSCOPE]: 阿里云 DashScope Paraformer 实时语音识别(WebSocket 流式)
 * - [STEP]: 阶跃星辰 Step-Audio(OpenAI 兼容 API,audio 输入)
 * - [DASHSCOPE_FILE]: Phase 11.1.5 DashScope 异步文件转录(submit → query 轮询),
 *   适合长音频文件(需先上传到 OSS 拿 URL,或直接传公网可访问 URL)
 */
enum class AsrProviderType {
    /** 系统 Intent 语音识别(默认,无网络依赖但依赖厂商服务)。 */
    SYSTEM,
    /** 阿里云 DashScope Paraformer(wss://dashscope.aliyuncs.com/api-ws/v1/inference)。 */
    DASHSCOPE,
    /** 阶跃星辰 Step-Audio(OpenAI 兼容 API,audio base64 输入)。 */
    STEP,
    /** Phase 11.1.5: DashScope 异步文件转录(POST submit → GET query 轮询)。 */
    DASHSCOPE_FILE,
}

/**
 * Phase 9.3 (M2): ASR 配置。
 *
 * @param provider ASR Provider 类型
 * @param apiKey DashScope/Step API Key(SYSTEM 模式忽略)
 * @param model 模型名(DashScope 默认 paraformer-realtime-v2,Step 默认 step-audio-r1.1)
 * @param sampleRate 采样率(DashScope Paraformer v2 支持任意,默认 16000)
 * @param language 语种提示(DashScope: zh/en/ja/yue/ko/de/fr/ru,null=自动识别)
 * @param enablePunctuation 是否启用标点预测(DashScope v2,默认 true)
 * @param enableInverseTextNormalization 是否启用逆文本正则化(中文数字→阿拉伯数字,默认 true)
 * @param fileAudioUrl Phase 11.1.5: 异步文件转录的音频 URL(公网可访问,如 OSS URL)
 * @param pollIntervalMs Phase 11.1.5: 轮询间隔(默认 3000ms)
 * @param pollTimeoutMs Phase 11.1.5: 轮询总超时(默认 300000ms = 5 分钟)
 * @param asrEndpoint L-ASR3: DashScope WebSocket 端点(默认空,空时用代码内置默认值)
 */
@Serializable
data class AsrConfig(
    val provider: AsrProviderType = AsrProviderType.SYSTEM,
    val apiKey: String = "",
    val model: String = "paraformer-realtime-v2",
    val sampleRate: Int = 16000,
    val language: String? = "zh",
    val enablePunctuation: Boolean = true,
    val enableInverseTextNormalization: Boolean = true,
    val fileAudioUrl: String = "",
    val pollIntervalMs: Long = 3000L,
    val pollTimeoutMs: Long = 300_000L,
    val asrEndpoint: String = "",
) {
    /** 是否已配置可用(SYSTEM 总是可用,API 模式需要 apiKey)。 */
    val isConfigured: Boolean
        get() = provider == AsrProviderType.SYSTEM || apiKey.isNotBlank()

    /** 根据 provider 返回默认模型名。 */
    fun defaultModel(): String = when (provider) {
        AsrProviderType.DASHSCOPE -> "paraformer-realtime-v2"
        AsrProviderType.STEP -> "step-audio-r1.1"
        AsrProviderType.DASHSCOPE_FILE -> "paraformer-v2"
        AsrProviderType.SYSTEM -> ""
    }

    /**
     * M-03: 返回 apiKey 已加密(走 [SecureKeyStore.encrypt])的副本,供持久化前调用。
     * 空值原样保留(不加密空值)。参照 WebServerConfig.encrypted 模式。
     */
    suspend fun encrypted(): AsrConfig = copy(
        apiKey = SecureKeyStore.encrypt(apiKey),
    )

    /**
     * M-03: 返回 apiKey 已解密(走 [SecureKeyStore.decrypt])的副本,供从持久化层读出后调用。
     * 旧版明文由 decrypt 透传(兼容)。参照 WebServerConfig.decrypted 模式。
     */
    suspend fun decrypted(): AsrConfig = copy(
        apiKey = SecureKeyStore.decrypt(apiKey),
    )
}
