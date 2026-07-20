package io.zer0.muse.data.preset

import android.content.Context
import io.zer0.ai.core.Model
import io.zer0.muse.R
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType

/**
 * v0.22: 预置供应商清单(海外 + 国产 + 中转站全覆盖)。
 *
 * 设计:
 *  - 每个预置供应商 [ProviderConfig.builtIn] = true,不可删除
 *  - [ProviderConfig.apiKey] 留空,用户只需填入自己的 Key 即可使用
 *  - baseUrl 走各家官方 OpenAI 兼容端点(国产厂商基本都支持)
 *  - models 默认空列表,首次使用时从上游 /models 接口动态拉取
 *  - category 字段用于 UI 分组展示:官方 / 中转站 / 自定义
 *
 * 分类:
 *  - 海外官方: OpenAI / Anthropic / Gemini / Groq / Together / Mistral / OpenRouter / DeepInfra / Fireworks
 *  - 国产官方: DeepSeek / 通义千问 / 智谱GLM / 月之暗面 / 豆包 / 百川 / 零一 / 阶跃
 *  - 中转站: OpenCode / API2D / AiHubMix / DeepBricks / OneAPI 自建 / NewAPI 自建
 *
 * 调用方: SettingsRepository.presetProviders(首次启动 / Onboarding / "添加 Provider" 时展示)
 */
class PresetProviders(
    private val context: Context,
) {

    // M-PRESET1: 各预置供应商官方 OpenAI 兼容端点,集中为常量便于维护与统一修改。
    // 空 baseUrl(OpenAI/Anthropic/Gemini/OneAPI/NewAPI)由 Provider 实现内部走官方域名或由用户填入。
    private val ENDPOINT_GROQ = "https://api.groq.com/openai/v1"
    private val ENDPOINT_TOGETHER = "https://api.together.xyz/v1"
    private val ENDPOINT_MISTRAL = "https://api.mistral.ai/v1"
    private val ENDPOINT_OPENROUTER = "https://openrouter.ai/api/v1"
    private val ENDPOINT_DEEPINFRA = "https://api.deepinfra.com/v1/openai"
    private val ENDPOINT_FIREWORKS = "https://api.fireworks.ai/inference/v1"
    private val ENDPOINT_DEEPSEEK = "https://api.deepseek.com/v1"
    private val ENDPOINT_QWEN = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val ENDPOINT_ZHIPU = "https://open.bigmodel.cn/api/paas/v4"
    private val ENDPOINT_MOONSHOT = "https://api.moonshot.cn/v1"
    private val ENDPOINT_DOUBAO = "https://ark.cn-beijing.volces.com/api/v3"
    private val ENDPOINT_BAICHUAN = "https://api.baichuan-ai.com/v1"
    private val ENDPOINT_LINGYI = "https://api.lingyiwanwu.com/v1"
    private val ENDPOINT_STEPFUN = "https://api.stepfun.com/v1"
    private val ENDPOINT_OPENCODE = "https://opencode.ai/zen/go/v1"
    private val ENDPOINT_API2D = "https://oa.api2d.net/v1"
    private val ENDPOINT_AIHUBMIX = "https://aihubmix.com/v1"
    private val ENDPOINT_DEEPBRICKS = "https://api.deepbricks.ai/v1"
    // P2-5: SiliconFlow 平台 OpenAI 兼容端点(与 SiliconFlowFreeModels.BASE_URL 保持一致)
    private val ENDPOINT_SILICONFLOW = SiliconFlowFreeModels.BASE_URL

    /** 海外官方厂商。 */
    val overseas: List<ProviderConfig> = listOf(
        openai(),
        anthropic(),
        gemini(),
        xai(),
        openaiCodex(),
        githubCopilot(),
        groq(),
        together(),
        mistral(),
        openrouter(),
        deepInfra(),
        fireworks(),
    )

    /** 国产官方厂商。 */
    val domestic: List<ProviderConfig> = listOf(
        deepseek(),
        qwen(),
        zhipu(),
        moonshot(),
        doubao(),
        baichuan(),
        lingyi(),
        stepfun(),
        // P2-5: SiliconFlow 免费模型兜底(国产开源模型聚合平台)
        siliconFlowFree(),
    )

    /** 中转站。 */
    val relay: List<ProviderConfig> = listOf(
        opencode(),
        api2d(),
        aihubmix(),
        deepbricks(),
        oneapiTemplate(),
        newapiTemplate(),
    )

    /** 全部预置供应商(保持向后兼容)。 */
    val all: List<ProviderConfig> = overseas + domestic + relay

    /** 按 id 查找预置供应商。 */
    fun byId(id: String): ProviderConfig? = all.firstOrNull { it.id == id }

    // ── 海外官方 ──────────────────────────────────────────────────────

    private fun openai() = ProviderConfig(
        id = "preset_openai",
        displayName = "OpenAI",
        type = ProviderType.OPENAI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun anthropic() = ProviderConfig(
        id = "preset_anthropic",
        displayName = "Anthropic Claude",
        type = ProviderType.ANTHROPIC,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun gemini() = ProviderConfig(
        id = "preset_gemini",
        displayName = "Google Gemini",
        type = ProviderType.GEMINI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    /**
     * P1-6: xAI 预设供应商。
     *
     * 默认填充 [OAuthConfig.XAI_PRESET],用户在 ProviderEditPage 点击「OAuth 登录」
     * 即可走 Device Flow 自动获取 apiKey(access_token),无需手动填密钥。
     * 走 OpenAI 兼容协议(type=OPENAI),baseUrl 用 xAI 官方端点。
     */
    private fun xai() = ProviderConfig(
        id = "preset_xai",
        displayName = "xAI Grok",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
        oauthConfig = OAuthConfig.XAI_PRESET,
    )

    /**
     * v1.134 P2-1: OpenAI Codex 预设供应商(走 OAuth Device Flow)。
     *
     * 与 [openai] 区别:此预设强制走 Codex CLI 的 OAuth 流程(auth.openai.com 域名),
     * 用户用 ChatGPT 账号扫码授权后,自动拿到 access_token 当作 apiKey 使用。
     * baseUrl 仍走 OpenAI 官方 /v1 端点,但内部走 Responses API(由 OpenAISpecific
     * 的 useResponseApi 控制,用户可在编辑页打开)。
     */
    private fun openaiCodex() = ProviderConfig(
        id = "preset_openai_codex",
        displayName = "OpenAI Codex (OAuth)",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
        oauthConfig = OAuthConfig.OPENAI_CODEX_PRESET,
        specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
    )

    /**
     * v1.134 P2-1: GitHub Copilot 预设供应商(走 GitHub OAuth Device Flow)。
     *
     * 用户用 GitHub 账号扫码授权后,access_token 当作 apiKey,
     * baseUrl 指向 Copilot 网关 https://api.githubcopilot.com(OpenAI 兼容协议),
     * Copilot 网关会代理到 GPT-4o / Claude 等模型。
     */
    private fun githubCopilot() = ProviderConfig(
        id = "preset_github_copilot",
        displayName = "GitHub Copilot",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.githubcopilot.com",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
        oauthConfig = OAuthConfig.GITHUB_COPILOT_PRESET,
    )

    private fun groq() = ProviderConfig(
        id = "preset_groq",
        displayName = "Groq",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_GROQ,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun together() = ProviderConfig(
        id = "preset_together",
        displayName = "Together AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_TOGETHER,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun mistral() = ProviderConfig(
        id = "preset_mistral",
        displayName = "Mistral AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MISTRAL,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun openrouter() = ProviderConfig(
        id = "preset_openrouter",
        displayName = "OpenRouter",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OPENROUTER,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun deepInfra() = ProviderConfig(
        id = "preset_deepinfra",
        displayName = "DeepInfra",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPINFRA,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun fireworks() = ProviderConfig(
        id = "preset_fireworks",
        displayName = "Fireworks AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_FIREWORKS,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    // ── 国产官方 ──────────────────────────────────────────────────────

    private fun deepseek() = ProviderConfig(
        id = "preset_deepseek",
        displayName = context.getString(R.string.preset_provider_deepseek),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPSEEK,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun qwen() = ProviderConfig(
        id = "preset_qwen",
        displayName = context.getString(R.string.preset_provider_qwen),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_QWEN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun zhipu() = ProviderConfig(
        id = "preset_zhipu",
        displayName = context.getString(R.string.preset_provider_zhipu),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_ZHIPU,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun moonshot() = ProviderConfig(
        id = "preset_moonshot",
        displayName = context.getString(R.string.preset_provider_moonshot),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MOONSHOT,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun doubao() = ProviderConfig(
        id = "preset_doubao",
        displayName = context.getString(R.string.preset_provider_doubao),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DOUBAO,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun baichuan() = ProviderConfig(
        id = "preset_baichuan",
        displayName = context.getString(R.string.preset_provider_baichuan),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_BAICHUAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun lingyi() = ProviderConfig(
        id = "preset_lingyi",
        displayName = context.getString(R.string.preset_provider_lingyi),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_LINGYI,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun stepfun() = ProviderConfig(
        id = "preset_stepfun",
        displayName = context.getString(R.string.preset_provider_stepfun),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_STEPFUN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    /**
     * P2-5: SiliconFlow 免费模型兜底预设。
     *
     * 与其他预设不同:
     *  - 预填一份当前已知免费的国产开源模型清单([SiliconFlowFreeModels.MODELS]),
     *    让用户拿到 SiliconFlow apiKey 后无需手动逐个添加;
     *  - apiKey 留空,用户填入自己的 SiliconFlow Key 即可直接使用;
     *  - 用户在 ProviderEditPage 也可通过「填入免费模型」按钮一键刷新为最新清单
     *    (按钮仅当 baseUrl 命中 siliconflow.cn 时显示)。
     *
     * 分类为 [ProviderCategory.OFFICIAL](国产官方):SiliconFlow 是国内厂商,
     * 自建推理集群并对外提供官方 OpenAI 兼容端点,不属于中转站。
     */
    private fun siliconFlowFree() = ProviderConfig(
        id = SiliconFlowFreeModels.PROVIDER_ID,
        displayName = context.getString(R.string.preset_siliconflow_free),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_SILICONFLOW,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = SiliconFlowFreeModels.MODELS,
    )

    // ── 中转站 ──────────────────────────────────────────────────────

    /**
     * OpenCode Go — OpenCode 官方提供的低成本模型订阅服务。
     *
     * 注意:
     *  - 端点来自官方文档: https://opencode.ai/zen/go/v1/chat/completions
     *  - 仅列出 OpenAI 兼容(/chat/completions)的模型;Anthropic 兼容(/messages)的
     *    MiniMax/Qwen 系列需要 ProviderType.ANTHROPIC,当前预设不包含。
     *  - 模型 ID 本地保存带 opencode-go/ 前缀,但实际请求前会通过
     *    ProviderSpecificConfig.OpenAI.stripModelPrefix 自动剥离。
     */
    private fun opencode() = ProviderConfig(
        id = "preset_opencode",
        displayName = "OpenCode Go",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OPENCODE,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.OpenAI(stripModelPrefix = "opencode-go/"),
        models = emptyList(),
    )

    /** API2D 中转站 — 国内老牌 OpenAI 中转。 */
    private fun api2d() = ProviderConfig(
        id = "preset_api2d",
        displayName = context.getString(R.string.preset_provider_api2d),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_API2D,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** AiHubMix 中转站 — 聚合 OpenAI/Claude/Gemini 等。 */
    private fun aihubmix() = ProviderConfig(
        id = "preset_aihubmix",
        displayName = context.getString(R.string.preset_provider_aihubmix),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_AIHUBMIX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** DeepBricks 中转站 — OpenAI 兼容。 */
    private fun deepbricks() = ProviderConfig(
        id = "preset_deepbricks",
        displayName = context.getString(R.string.preset_provider_deepbricks),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPBRICKS,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** OneAPI 自建中转模板 — 用户自部署,baseUrl 留空。 */
    private fun oneapiTemplate() = ProviderConfig(
        id = "preset_oneapi",
        displayName = context.getString(R.string.preset_provider_oneapi),
        type = ProviderType.OPENAI,
        baseUrl = "",  // 用户自部署,留空让用户填
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** NewAPI 自建中转模板 — OneAPI 的增强 fork。 */
    private fun newapiTemplate() = ProviderConfig(
        id = "preset_newapi",
        displayName = context.getString(R.string.preset_provider_newapi),
        type = ProviderType.OPENAI,
        baseUrl = "",  // 用户自部署,留空让用户填
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )
}
