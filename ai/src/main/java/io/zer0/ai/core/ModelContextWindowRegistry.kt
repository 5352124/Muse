package io.zer0.ai.core

/**
 * 常见模型上下文窗口(token 数)注册表。
 *
 * 用于在拉取上游模型列表时回填 [Model.contextWindow](上游 /models 接口一般不返回该字段),
 * 以及 ChatViewModel 在模型未声明 contextWindow 时兜底,避免占用圆环始终显示问号。
 *
 * 匹配规则(均大小写不敏感,按优先级依次尝试):
 *  1. 精确匹配优先(如 "gpt-4o-2024-08-06" 命中 "gpt-4o")
 *  2. 前缀匹配次之(如 "deepseek-v4" 命中 "deepseek" 品牌默认)
 *  3. 模型名 token 数推断(如 "model-128k" → 128000, "model-1m" → 1000000)
 *  4. 品牌级默认(如 "deepseek" 开头 → 64000)
 *  5. 仍未命中返回 null
 */
object ModelContextWindowRegistry {

    // v1.80 (M-CORE10): 支持运行时动态注册(新模型上线无需发版)
    // v1.80 (L-CORE14): 加容量上限,防止恶意/异常调用方无限注册导致内存泄漏。
    //   用 synchronized 包装的 LinkedHashMap(LRU),超限时淘汰最旧条目。
    private const val DYNAMIC_REGISTRY_MAX_SIZE = 256
    private val dynamicRegistry: MutableMap<String, Int> =
        object : java.util.LinkedHashMap<String, Int>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
                return size > DYNAMIC_REGISTRY_MAX_SIZE
            }
        }

    // ── 精确/前缀匹配表 ──
    private val REGISTRY: List<Pair<String, Int>> = listOf(
        // OpenAI
        "gpt-4o" to 128000,
        "gpt-4o-mini" to 128000,
        "gpt-4-turbo" to 128000,
        "gpt-4" to 8192,
        "gpt-3.5-turbo" to 16385,
        "o1" to 200000,
        "o3" to 200000,
        "o4-mini" to 200000,
        // Claude
        "claude-3-5-sonnet" to 200000,
        "claude-3-5-haiku" to 200000,
        "claude-3-7-sonnet" to 200000,
        "claude-3-opus" to 200000,
        "claude-sonnet-4" to 200000,
        "claude-opus-4" to 200000,
        "claude-3-haiku" to 200000,
        // Gemini
        "gemini-2.0-flash" to 1000000,
        "gemini-2.0-flash-lite" to 1000000,
        "gemini-1.5-pro" to 2000000,
        "gemini-1.5-flash" to 1000000,
        "gemini-2.5-pro" to 1000000,
        "gemini-2.5-flash" to 1000000,
        // DeepSeek
        "deepseek-chat" to 64000,
        "deepseek-reasoner" to 64000,
        "deepseek-v3" to 64000,
        "deepseek-r1" to 64000,
        "deepseek-v4" to 1000000,
        "deepseek-v4-pro" to 1000000,
        "deepseek-v4-flash" to 1000000,
        "deepseek-v3.1" to 64000,
        // 通义
        "qwen-max" to 32000,
        "qwen-plus" to 131072,
        "qwen-turbo" to 1000000,
        "qwen2.5" to 131072,
        "qwen3" to 131072,
        "qwen3.7" to 128000,
        "qwen3.6" to 128000,
        // 智谱
        "glm-4" to 128000,
        "glm-4-plus" to 128000,
        "glm-4-flash" to 128000,
        "glm-4.5" to 128000,
        "glm-5" to 128000,
        "glm-5.1" to 128000,
        "glm-5.2" to 128000,
        // 月之暗面
        "moonshot-v1-8k" to 8000,
        "moonshot-v1-32k" to 32000,
        "moonshot-v1-128k" to 128000,
        "kimi-k2" to 128000,
        "kimi-k2.7" to 256000,
        "kimi-k2.6" to 256000,
        "kimi-k2.7-code" to 256000,
        // OpenCode Go / 其他
        "mimo" to 128000,
        "mimo-v2.5" to 128000,
        "mimo-v2.5-pro" to 128000,
        "minimax" to 128000,
        "minimax-m3" to 1000000,
        "minimax-m2.7" to 128000,
        "minimax-m2.5" to 128000,
        // 豆包
        "doubao-pro" to 32000,
        "doubao-lite" to 32000,
        // 百川
        "baichuan2" to 4096,
        // 零一
        "yi-large" to 16384,
        "yi-medium" to 16384,
        // 阶跃
        "step-2" to 32000,
        "step-1" to 8000,
        // Mistral
        "mistral-large" to 128000,
        "mistral-small" to 32000,
        "mixtral" to 32000,
        // Meta Llama
        "llama-3.1" to 128000,
        "llama-3.2" to 128000,
        "llama-3.3" to 128000,
        // Google Gemma
        "gemma-2" to 8192,
        "gemma-3" to 128000,
        // xAI Grok
        "grok-2" to 131072,
        "grok-3" to 131072,
        // Cohere
        "command-r" to 128000,
        "command-r-plus" to 128000,
        // Perplexity
        "llama-3.1-sonar" to 127072,
    )

    // ── 品牌级默认值(前缀匹配未命中时,按品牌兜底) ──
    private val BRAND_DEFAULTS: List<Pair<String, Int>> = listOf(
        "deepseek" to 64000,
        "gpt-4" to 128000,
        "gpt-3.5" to 16385,
        "claude" to 200000,
        "gemini" to 1000000,
        "qwen" to 131072,
        "glm" to 128000,
        "moonshot" to 128000,
        "kimi" to 128000,
        "minimax" to 128000,
        "mimo" to 128000,
        "doubao" to 32000,
        "yi" to 16384,
        "step" to 32000,
        "mistral" to 32000,
        "llama" to 128000,
        "gemma" to 8192,
        "grok" to 131072,
        "command" to 128000,
    )

    // ── 模型名 token 数推断正则(如 "model-128k" / "model-1m" / "model-200k") ──
    private val TOKEN_PATTERN = Regex("""(\d+)([km])""", RegexOption.IGNORE_CASE)

    /**
     * 部分中转/聚合平台会在模型 ID 前加 provider 前缀(如 openrouter/、opencode-go/)。
     * 先把这些已知前缀剥掉,再用核心 modelId 去匹配注册表。
     */
    private val KNOWN_PREFIXES = listOf(
        "opencode-go/",
        "openrouter/",
        "anthropic/",
        "google/",
        "openai/",
        "meta-llama/",
        "mistralai/",
        "nousresearch/",
        "deepinfra/",
        "togethercomputer/",
        "accounts/fireworks/models/",
        "presets/",
    )

    /**
     * v1.80 (M-CORE10): 运行时注册模型上下文窗口,支持新模型无需发版。
     * 已注册的精确 modelId 优先级高于内置 REGISTRY。
     *
     * v1.80 (L-CORE14): 加 synchronized 保护(LRU LinkedHashMap 非线程安全),
     * 容量超 [DYNAMIC_REGISTRY_MAX_SIZE] 时自动淘汰最旧条目。
     */
    fun register(modelId: String, contextWindow: Int) {
        synchronized(dynamicRegistry) {
            dynamicRegistry[modelId.lowercase().trim()] = contextWindow
        }
    }

    /**
     * 按 modelId 查询上下文窗口。未命中返回 null。
     *
     * 匹配优先级:动态注册 → 剥前缀 → 精确 → 边界前缀 → 名称 token 推断 → 品牌默认 → null
     */
    fun lookup(modelId: String): Int? {
        var id = modelId.lowercase().trim()
        if (id.isBlank()) return null

        // v1.80 (M-CORE10): 动态注册优先
        synchronized(dynamicRegistry) { dynamicRegistry[id] }?.let { return it }

        // 0. 剥掉常见 provider 前缀
        KNOWN_PREFIXES.firstOrNull { id.startsWith(it) }?.let { prefix ->
            id = id.removePrefix(prefix)
            // 剥前缀后再查一次动态注册
            synchronized(dynamicRegistry) { dynamicRegistry[id] }?.let { return it }
        }

        // 1. 精确匹配
        REGISTRY.firstOrNull { (pattern, _) -> id == pattern }?.let { return it.second }

        // 2. 边界前缀匹配(v1.80 M-CORE7: 加边界约束,防止 "gpt-4" 匹配 "gpt-4.5")
        //    匹配 "gpt-4o-2024-08-06" 命中 "gpt-4o"(后跟 - 或到末尾)
        REGISTRY.firstOrNull { (pattern, _) ->
            id == pattern || id.startsWith("$pattern-") || id.startsWith("${pattern}_")
        }?.let { return it.second }

        // 3. 模型名 token 数推断(如 "model-128k" → 128000, "model-1m" → 1000000)
        //    取最后一个匹配(避免 "8k-128k" 这种取到 8k)
        TOKEN_PATTERN.findAll(id).lastOrNull()?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@let
            val unit = match.groupValues[2].lowercase()
            return when (unit) {
                "k" -> num * 1000
                "m" -> num * 1_000_000
                else -> null
            }
        }

        // 4. 品牌级默认
        BRAND_DEFAULTS.firstOrNull { (brand, _) -> id.startsWith(brand) }?.let { return it.second }

        return null
    }
}
