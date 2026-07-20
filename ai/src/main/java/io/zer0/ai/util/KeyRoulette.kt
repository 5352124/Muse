package io.zer0.ai.util

import java.util.concurrent.ConcurrentHashMap

/**
 * API Key rotation (RikkaHub KeyRoulette.kt port).
 *
 * Supports multiple API keys per provider with LRU eviction.
 * When a provider has multiple keys (separated by comma or newline),
 * this picks the least-recently-used key to distribute load.
 *
 * Usage:
 * ```kotlin
 * val keyRoulette = KeyRoulette()
 * val selectedKey = keyRoulette.pick("provider-123", "key1,key2,key3")
 * ```
 */
class KeyRoulette {

    companion object {
        private const val MAX_ENTRIES = 100
        private const val EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private data class Entry(
        val key: String,
        val lastUsedAt: Long,
    )

    /** providerId → list of recent entries (oldest first) */
    private val cache = ConcurrentHashMap<String, MutableList<Entry>>()

    /**
     * Pick a key from a comma/newline-separated key string.
     * Uses LRU strategy: prefers keys not recently used.
     *
     * @param providerId unique identifier for the provider
     * @param keysString comma or newline separated API keys
     * @return the selected key, or the original string if single key
     */
    fun pick(providerId: String, keysString: String): String {
        val keys = keysString.split(",", "\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (keys.size <= 1) return keysString.trim()

        val now = System.currentTimeMillis()
        val entries = cache.getOrPut(providerId) { mutableListOf() }

        // Find least recently used key
        val usedKeys = entries
            .filter { now - it.lastUsedAt < EXPIRY_MS }
            .map { it.key }
            .toSet()

        val unusedKeys = keys.filter { it !in usedKeys }
        val selected = if (unusedKeys.isNotEmpty()) {
            unusedKeys.random()
        } else {
            // All keys used recently, pick the oldest
            val oldest = entries.minByOrNull { it.lastUsedAt }
            if (oldest != null && oldest.key in keys) {
                oldest.key
            } else {
                keys.random()
            }
        }

        // Update LRU
        synchronized(entries) {
            entries.removeAll { it.key == selected }
            entries.add(Entry(selected, now))
            // Evict old entries
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
        }

        return selected
    }

    /** Clear all cached key usage data. */
    fun clear() {
        cache.clear()
    }
}
