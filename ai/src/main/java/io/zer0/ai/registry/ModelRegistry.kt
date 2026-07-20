package io.zer0.ai.registry

import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility

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
        visionInput(); toolAbility()
    }
    private val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        visionInput(); toolReasoningAbility()
    }
    private val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput(); toolReasoningAbility()
    }
    private val GEMINI_1_5_PRO = defineModel {
        tokens("gemini", "1", "5", "pro")
        visionInput(); toolAbility()
    }
    private val GEMINI_1_5_FLASH = defineModel {
        tokens("gemini", "1", "5", "flash")
        visionInput(); toolAbility()
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
        tokens("deepseek", "v3")
        toolAbility()
    }
    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        toolAbility()
    }
    private val DEEPSEEK_R1 = defineModel {
        tokens("deepseek", "r1")
        reasoningAbility()
    }
    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        reasoningAbility()
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
        visionInput()
    }
    private val QWEN_QWQ = defineModel {
        tokens("qwq")
        reasoningAbility()
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
    private val DOUBAO_PRO = defineModel {
        tokens("doubao", "pro")
        toolAbility()
    }
    private val MINIMAX = defineModel {
        tokens("minimax", "abab")
        toolAbility()
    }
    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m3")
        visionInput(); toolAbility()
    }
    private val GROK = defineModel {
        tokens("grok")
        visionInput(); toolAbility()
    }
    private val KIMI = defineModel {
        tokens("kimi", "moonshot")
        toolAbility()
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

    // ─── All models list ───

    private val ALL_MODELS = listOf(
        GPT4O, GPT_4_1, OPENAI_O_MODELS, GPT_4_TURBO, GPT_4,
        CLAUDE_3_5_SONNET, CLAUDE_3_5_HAIKU, CLAUDE_3_7, CLAUDE_4, CLAUDE_4_5,
        CLAUDE_OPUS, CLAUDE_SONNET,
        GEMINI_2_0_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO,
        GEMINI_1_5_PRO, GEMINI_1_5_FLASH, GEMINI_PRO, GEMINI_FLASH,
        DEEPSEEK_V3, DEEPSEEK_CHAT, DEEPSEEK_R1, DEEPSEEK_REASONER,
        QWEN_MAX, QWEN_PLUS, QWEN_TURBO, QWEN_VL, QWEN_QWQ,
        GLM_4, GLM_3, DOUBAO_PRO, MINIMAX, MINIMAX_M3, GROK, KIMI, YI,
        LLAMA_3, MISTRAL_LARGE, MISTRAL,
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
     */
    private fun bareModelId(modelId: String): String {
        val raw = modelId.trim().lowercase()
        val prefixes = listOf(
            "openrouter/", "opencode-go/", "anthropic/", "google/", "openai/",
            "meta-llama/", "mistralai/", "nousresearch/", "deepinfra/", "togethercomputer/",
            "accounts/fireworks/models/", "presets/",
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
     */
    fun enhanceModel(model: Model): Model {
        val defs = resolveDefinitions(model.id)
        if (defs.isEmpty()) return model

        val resolvedAbilities = defs.flatMap { it.abilities }.toSet()
        val resolvedInput = defs.flatMap { it.inputModalities }.toSet()
        val resolvedOutput = defs.flatMap { it.outputModalities }.toSet()
        val resolvedTools = defs.flatMap { it.builtInTools }.toSet()

        return model.copy(
            abilities = if (model.abilities.isEmpty()) resolvedAbilities else model.abilities,
            inputModalities = if (model.inputModalities == setOf("text")) resolvedInput else model.inputModalities,
            outputModalities = if (model.outputModalities == setOf("text")) resolvedOutput else model.outputModalities,
            tools = if (model.tools.isEmpty()) resolvedTools else model.tools,
            supportsVision = model.supportsVision || "image" in resolvedInput,
        )
    }
}
