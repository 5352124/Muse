package io.zer0.ai.registry

import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.KnownModels
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry

/**
 * Model capability registry (RikkaHub ModelRegistry.kt port).
 *
 * Defines known model capabilities via DSL. When a model ID matches,
 * the registry returns resolved abilities/modalities/tools.
 *
 * Used by ChatService to auto-adapt request parameters:
 *  - Whether to send tools (function calling)
 *  - Whether to include images (vision input)
 *  - Whether to enable reasoning/thinking
 */
object ModelRegistry {

    // ─── OpenAI ───

    private val GPT4O = defineModel {
        tokens("gpt", "4", "o")
        visionInput(); toolAbility()
    }
    private val GPT_4_1 = defineModel {
        tokens("gpt", "4", "1")
        visionInput(); toolAbility()
    }
    private val OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput(); toolReasoningAbility()
    }
    // v1.0.8: GPT-5 系列(旗舰 / mini / nano / codex 等)
    private val GPT_5 = defineModel {
        tokens("gpt", "5")
        notTokens(".")
        visionInput(); toolReasoningAbility()
    }
    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput(); toolReasoningAbility()
    }
    private val GPT_5_4 = defineModel {
        tokens("gpt", "5", "4")
        visionInput(); toolReasoningAbility()
    }
    private val GPT_5_CODEX = defineModel {
        tokens("gpt", "5", "codex")
        toolReasoningAbility()
    }
    private val GPT_4_TURBO = defineModel {
        tokens("gpt", "4", "turbo")
        visionInput(); toolAbility()
    }
    private val GPT_4 = defineModel {
        tokens("gpt", "4")
        notTokens("o")
        toolAbility()
    }

    // ─── Anthropic ───

    private val CLAUDE_3_5_SONNET = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput(); toolAbility()
    }
    private val CLAUDE_3_5_HAIKU = defineModel {
        tokens("claude", "3", "5", "haiku")
        toolAbility()
    }
    private val CLAUDE_3_7 = defineModel {
        tokens("claude", "3", "7")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        notTokens("5")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_OPUS = defineModel {
        tokens("claude", "opus")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_SONNET = defineModel {
        tokens("claude", "sonnet")
        visionInput(); toolAbility()
    }

    // ─── Google Gemini ───

    private val GEMINI_2_0_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput(); toolAbility(); visionGrounding("gemini")
    }
    private val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        visionInput(); toolReasoningAbility(); visionGrounding("gemini")
    }
    private val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput(); toolReasoningAbility(); visionGrounding("gemini")
    }
    private val GEMINI_1_5_PRO = defineModel {
        tokens("gemini", "1", "5", "pro")
        visionInput(); toolAbility(); visionGrounding("gemini")
    }
    private val GEMINI_1_5_FLASH = defineModel {
        tokens("gemini", "1", "5", "flash")
        visionInput(); toolAbility(); visionGrounding("gemini")
    }
    private val GEMINI_PRO = defineModel {
        tokens("gemini", "pro")
        notTokens("1")
        notTokens("2")
        notTokens("3")
        visionInput(); toolAbility()
    }
    private val GEMINI_FLASH = defineModel {
        tokens("gemini", "flash")
        notTokens("1")
        notTokens("2")
        notTokens("3")
        visionInput(); toolAbility()
    }

    // ─── DeepSeek ───

    private val DEEPSEEK_V3 = defineModel {
        tokens("deepseek", "v", "3")
        toolAbility()
    }
    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        toolAbility()
    }
    private val DEEPSEEK_R1 = defineModel {
        tokens("deepseek", "r", "1")
        reasoningAbility()
    }
    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        reasoningAbility()
    }
    // v1.0.8: DeepSeek V4 系列(含 flash / pro 等变体)
    private val DEEPSEEK_V4 = defineModel {
        tokens("deepseek", "v", "4")
        toolReasoningAbility()
    }

    // ─── Qwen ───

    private val QWEN_MAX = defineModel {
        tokens("qwen", "max")
        toolAbility()
    }
    private val QWEN_PLUS = defineModel {
        tokens("qwen", "plus")
        toolAbility()
    }
    private val QWEN_TURBO = defineModel {
        tokens("qwen", "turbo")
        toolAbility()
    }
    private val QWEN_VL = defineModel {
        tokens("qwen", "vl")
        visionInput(); visionGrounding("qwen")
    }
    private val QWEN_QWQ = defineModel {
        tokens("qwq")
        reasoningAbility()
    }
    // v1.0.1 (P3): Qwen2-VL 系列(开源视觉模型,中转站常见)
    // v1.0.4: 支持 grounding(qwen 格式 bbox_2d + point_2d)
    private val QWEN2_VL = defineModel {
        tokens("qwen", "2", "vl")
        visionInput(); visionGrounding("qwen")
    }

    // ─── Others ───

    private val GLM_4 = defineModel {
        tokens("glm", "4")
        visionInput(); toolAbility()
    }
    private val GLM_3 = defineModel {
        tokens("glm", "3")
        toolAbility()
    }
    // v1.0.1 (P3): GLM-4V 系列(智谱视觉模型,中转站常见)
    private val GLM_4V = defineModel {
        tokens("glm", "4", "v")
        visionInput(); toolAbility()
    }
    // v1.0.1 (P3): GLM-V 系列(智谱视觉模型简写,如 glm-v-4plus)
    private val GLM_V = defineModel {
        tokens("glm", "v")
        visionInput()
    }
    private val DOUBAO_PRO = defineModel {
        tokens("doubao", "pro")
        toolAbility()
    }
    // v1.0.1 (P3): Doubao Vision 系列(火山引擎视觉模型)
    private val DOUBAO_VISION = defineModel {
        tokens("doubao", "vision")
        visionInput(); toolAbility()
    }
    private val MINIMAX = defineModel {
        tokens("minimax", "abab")
        toolAbility()
    }
    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m", "3")
        visionInput(); toolAbility()
    }
    // v1.0.8: MiniMax M2.5 / M2.7 / M1 系列
    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        toolAbility()
    }
    private val MINIMAX_M2_7 = defineModel {
        tokens("minimax", "m", "2", "7")
        toolAbility()
    }
    private val MINIMAX_M1 = defineModel {
        tokens("minimax", "m", "1")
        toolAbility()
    }
    private val GROK = defineModel {
        tokens("grok")
        visionInput(); toolAbility()
    }
    private val KIMI = defineModel {
        tokens("kimi", "moonshot")
        toolAbility()
    }
    // v1.0.8: Kimi K2 系列(如 kimi-k2, kimi-k2.5, kimi-k2.7 等)
    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        toolReasoningAbility()
    }
    // v1.0.1 (P3): Kimi Vision(Moonshot 视觉模型,如 moonshot-v1-8k-vision-preview)
    private val KIMI_VISION = defineModel {
        tokens("kimi", "vision")
        visionInput()
    }
    private val YI = defineModel {
        tokens("yi")
        visionInput()
    }
    private val LLAMA_3 = defineModel {
        tokens("llama", "3")
        toolAbility()
    }
    private val MISTRAL_LARGE = defineModel {
        tokens("mistral", "large")
        visionInput(); toolAbility()
    }
    private val MISTRAL = defineModel {
        tokens("mistral")
        notTokens("large")
        toolAbility()
    }
    // v1.0.1 (P3): InternVL 系列(开源视觉模型,OpenRouter/HuggingFace 常见)
    private val INTERN_VL = defineModel {
        tokens("intern", "vl")
        visionInput()
    }
    // v1.0.1 (P3): CogVLM 系列(清华开源视觉模型)
    private val COG_VLM = defineModel {
        tokens("cog", "vlm")
        visionInput()
    }
    // v1.0.1 (P3): Step-VL 系列(阶跃星辰视觉模型)
    private val STEP_VL = defineModel {
        tokens("step", "vl")
        visionInput()
    }
    // v1.0.1 (P3): LLaVA 系列(开源视觉模型)
    private val LLAVA = defineModel {
        tokens("llava")
        visionInput()
    }
    // v1.0.1 (P3): Pixtral 系列(Mistral 视觉模型)
    private val PIXTRAL = defineModel {
        tokens("pixtral")
        visionInput()
    }

    // ─── All models list ───

    private val ALL_MODELS = listOf(
        GPT4O, GPT_4_1, OPENAI_O_MODELS, GPT_5, GPT_5_1, GPT_5_4, GPT_5_CODEX,
        GPT_4_TURBO, GPT_4,
        CLAUDE_3_5_SONNET, CLAUDE_3_5_HAIKU, CLAUDE_3_7, CLAUDE_4, CLAUDE_4_5,
        CLAUDE_OPUS, CLAUDE_SONNET,
        GEMINI_2_0_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO,
        GEMINI_1_5_PRO, GEMINI_1_5_FLASH, GEMINI_PRO, GEMINI_FLASH,
        DEEPSEEK_V3, DEEPSEEK_CHAT, DEEPSEEK_R1, DEEPSEEK_REASONER, DEEPSEEK_V4,
        QWEN_MAX, QWEN_PLUS, QWEN_TURBO, QWEN_VL, QWEN2_VL, QWEN_QWQ,
        GLM_4, GLM_3, GLM_4V, GLM_V,
        DOUBAO_PRO, DOUBAO_VISION,
        MINIMAX, MINIMAX_M3, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M1,
        GROK, KIMI, KIMI_K2, KIMI_VISION, YI,
        LLAMA_3, MISTRAL_LARGE, MISTRAL,
        // v1.0.1 (P3): 开源/中转站常见视觉模型
        INTERN_VL, COG_VLM, STEP_VL, LLAVA, PIXTRAL,
    )

    /**
     * Resolve capabilities for a given model ID.
     * Returns the best-matching definitions sorted by score.
     *
     * v1.135: 先按原始 modelId 匹配;未命中时剥掉中转/聚合平台前缀再试一次,
     * 解决 `opencode-go/deepseek-v3` 这类 ID 无法识别能力的问题。
     */
    fun resolveDefinitions(modelId: String): List<ModelDefinition> {
        val result = resolveDefinitionsInternal(modelId)
        if (result.isNotEmpty()) return result
        val bare = bareModelId(modelId)
        if (bare != modelId) return resolveDefinitionsInternal(bare)
        return emptyList()
    }

    private fun resolveDefinitionsInternal(modelId: String): List<ModelDefinition> {
        var bestScore: Int? = null
        val matches = mutableListOf<ModelDefinition>()
        for (model in ALL_MODELS) {
            val score = model.matchScore(modelId) ?: continue
            when {
                bestScore == null || score > bestScore -> {
                    bestScore = score
                    matches.clear()
                    matches.add(model)
                }
                score == bestScore -> matches.add(model)
            }
        }
        return matches
    }

    /**
     * 剥掉常见中转/聚合平台前缀(如 openrouter/、opencode-go/)。
     * 若不含前缀,则返回最后一个 `/` 之后的部分(兜底)。
     *
     * v1.0.1 (P3): 补全国内常见中转站前缀(siliconflow/、dashscope/、baichuan/、lingyi/ 等),
     *  兜底逻辑(substringAfterLast("/"))其实已能处理任意前缀,但显式列出可避免
     *  某些带版本号前缀(如 "accounts/fireworks/models/")被错误剥离。
     */
    private fun bareModelId(modelId: String): String {
        val raw = modelId.trim().lowercase()
        val prefixes = listOf(
            // 海外聚合站
            "openrouter/", "opencode-go/", "anthropic/", "google/", "openai/",
            "meta-llama/", "mistralai/", "nousresearch/", "deepinfra/", "togethercomputer/",
            "accounts/fireworks/models/", "presets/",
            // v1.0.1 (P3): 国内中转站 / 聚合站
            "siliconflow/", "dashscope/", "baichuan/", "lingyi/", "lingyiwanwu/",
            "stepfun/", "zhipu/", "bigmodel/", "minimax/", "moonshot/",
            "doubao/", "volcengine/", "ark/", "hunyuan/", "qwen/", "aliyun/",
        )
        for (prefix in prefixes) {
            if (raw.startsWith(prefix)) return raw.removePrefix(prefix)
        }
        return raw.substringAfterLast("/").takeIf { it.isNotBlank() } ?: raw
    }

    /**
     * Resolve abilities for a model ID.
     */
    fun resolveAbilities(modelId: String): Set<ModelAbility> =
        resolveDefinitions(modelId).flatMap { it.abilities }.toSet()

    /**
     * Resolve input modalities for a model ID.
     */
    fun resolveInputModalities(modelId: String): Set<String> {
        val defs = resolveDefinitions(modelId)
        if (defs.isEmpty()) return setOf("text")
        return defs.flatMap { it.inputModalities }.toSet()
    }

    /**
     * Resolve output modalities for a model ID.
     */
    fun resolveOutputModalities(modelId: String): Set<String> {
        val defs = resolveDefinitions(modelId)
        if (defs.isEmpty()) return setOf("text")
        return defs.flatMap { it.outputModalities }.toSet()
    }

    /**
     * Check if a model supports vision input.
     */
    fun supportsVision(modelId: String): Boolean =
        "image" in resolveInputModalities(modelId)

    /**
     * Check if a model supports tool calling.
     */
    fun supportsToolCalling(modelId: String): Boolean =
        ModelAbility.TOOL in resolveAbilities(modelId)

    /**
     * Check if a model supports reasoning/thinking.
     */
    fun supportsReasoning(modelId: String): Boolean =
        ModelAbility.REASONING in resolveAbilities(modelId)

    /**
     * Enhance a [Model] with registry-resolved capabilities.
     * Only fills in fields that are not already explicitly set.
     *
     * v1.0.8: 增强兜底链路 — 当 token 规则未命中时,回退到 [KnownModels] 与
     * [ModelContextWindowRegistry],补全 contextWindow / maxOutputTokens / modalities / abilities。
     */
    fun enhanceModel(model: Model): Model {
        val defs = resolveDefinitions(model.id)
        val knownInfo = KnownModels.lookup(model.id)

        val resolvedAbilities = defs.flatMap { it.abilities }.toSet()
        val resolvedInput = defs.flatMap { it.inputModalities }.toSet()
        val resolvedOutput = defs.flatMap { it.outputModalities }.toSet()
        val resolvedTools = defs.flatMap { it.builtInTools }.toSet()
        // v1.0.4: 取首个非 null 的 visionCapabilities(Gemini/Qwen-VL 等 grounding 模型)
        val resolvedVisionCaps = defs.firstNotNullOfOrNull { it.visionCapabilities }

        // abilities: 模型已有 > registry > KnownModels
        val newAbilities = when {
            model.abilities.isNotEmpty() -> model.abilities
            resolvedAbilities.isNotEmpty() -> resolvedAbilities
            !knownInfo?.abilities.isNullOrEmpty() -> knownInfo.abilities
            else -> emptySet()
        }

        // inputModalities: 当 registry 命中已知模型时,以 registry 为权威(覆盖上游/中转站的错误声明);
        // 未命中时保持原逻辑(模型已有 > KnownModels > 默认)。
        // v1.137: 修复中转站错误标记文本模型(如 DeepSeek V4)为 vision,
        // 导致视觉辅助被跳过、图片直发纯文本模型触发 400 或被当作空白的问题。
        val newInput = when {
            defs.isNotEmpty() -> resolvedInput
            model.inputModalities.size == 1 && "text" in model.inputModalities -> {
                val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
                when {
                    !knownInput.isNullOrEmpty() -> knownInput
                    else -> model.inputModalities
                }
            }
            else -> model.inputModalities
        }

        // outputModalities: 同 inputModalities 逻辑
        val newOutput = when {
            defs.isNotEmpty() -> resolvedOutput
            model.outputModalities.size == 1 && "text" in model.outputModalities -> {
                val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
                when {
                    !knownOutput.isNullOrEmpty() -> knownOutput
                    else -> model.outputModalities
                }
            }
            else -> model.outputModalities
        }

        // contextWindow: 模型已有 > KnownModels > ModelContextWindowRegistry
        val newContextWindow = model.contextWindow
            ?: knownInfo?.contextWindow
            ?: ModelContextWindowRegistry.lookup(model.id)

        // maxOutputTokens: 模型已有 > KnownModels
        val newMaxOutputTokens = model.maxOutputTokens ?: knownInfo?.maxOutputTokens

        return model.copy(
            abilities = newAbilities,
            inputModalities = newInput,
            outputModalities = newOutput,
            tools = if (model.tools.isEmpty()) resolvedTools else model.tools,
            // v1.137: registry 命中时 supportsVision/supportsVideo 完全由 newInput/newOutput 派生,
            // 不保留上游可能错误的标记(中转站常见把纯文本模型误标为 vision)。
            // 未命中时保持原逻辑(上游 > newInput)。
            supportsVision = if (defs.isNotEmpty()) {
                "image" in newInput
            } else {
                model.supportsVision || "image" in newInput
            },
            supportsVideo = if (defs.isNotEmpty()) {
                "video" in newOutput
            } else {
                model.supportsVideo || "video" in newOutput
            },
            // v1.0.4: 仅当模型未显式声明时才用 registry 解析的值
            visionCapabilities = model.visionCapabilities ?: resolvedVisionCaps,
            contextWindow = newContextWindow,
            maxOutputTokens = newMaxOutputTokens,
        )
    }
}
