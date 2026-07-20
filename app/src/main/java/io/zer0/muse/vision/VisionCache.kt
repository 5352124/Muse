package io.zer0.muse.vision

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/**
 * 视觉辅助结果缓存(session 级内存缓存 + sidecar 持久化)。
 *
 * 目的:
 *  - 同一会话中重复出现的图片无需多次调用视觉模型,减少 token 消耗与延迟
 *  - sidecar 文件持久化到磁盘,应用重启后同一会话仍可复用已分析结果
 *
 * 设计:
 *  - 按 sessionId 分文件: filesDir/vision_cache/{sessionId}.json
 *  - 每个条目: imageHash + visionModelId + description + createdAt
 *  - imageHash 用 SHA-256 对 base64 计算
 *  - 内存缓存只保留当前激活会话的条目,切换会话时自动加载/清空
 *  - 写入时原子写临时文件后 rename,避免半写损坏
 *
 * @param context 应用 Context,用于获取 filesDir
 */
class VisionCache(context: Context) {

    private val cacheDir = File(context.applicationContext.filesDir, "vision_cache")
    private val mutex = Mutex()

    @Volatile
    private var activeSessionId: String? = null

    /** 当前激活会话的内存缓存: key = "imageHash|visionModelId", value = 视觉描述。 */
    private val memoryCache = mutableMapOf<String, String>()

    /** 持久化缓存条目。 */
    @Serializable
    data class CacheEntry(
        /** 图片内容哈希。 */
        val imageHash: String,
        /** 分析时使用的视觉模型 id。 */
        val visionModelId: String,
        /** 视觉模型生成的描述(不含 <vision-context> 标签)。 */
        val description: String,
        /** 缓存创建时间戳(毫秒)。 */
        val createdAt: Long,
    )

    /**
     * 激活指定会话,加载其 sidecar 缓存到内存。
     *
     * 若目标会话已是当前激活会话,不做任何操作。
     */
    suspend fun activateSession(sessionId: String) = mutex.withLock {
        if (activeSessionId == sessionId) return@withLock
        activeSessionId = sessionId
        memoryCache.clear()
        val entries = loadEntries(sessionId)
        entries.forEach { entry ->
            memoryCache[makeKey(entry.imageHash, entry.visionModelId)] = entry.description
        }
        Logger.d(TAG, "视觉缓存已激活会话 $sessionId, 共 ${entries.size} 条")
    }

    /**
     * 获取缓存的视觉描述。
     *
     * @param sessionId 会话 id
     * @param imageBase64 图片 base64(无 data: 前缀)
     * @param visionModelId 视觉模型 id(不同模型描述可能不同,需区分)
     * @return 缓存的描述文本(不含 <vision-context> 标签);未命中返回 null
     */
    suspend fun get(sessionId: String, imageBase64: String, visionModelId: String): String? {
        activateSession(sessionId)
        val imageHash = hash(imageBase64)
        val key = makeKey(imageHash, visionModelId)
        return mutex.withLock { memoryCache[key] }
    }

    /**
     * 写入视觉描述到缓存。
     *
     * @param sessionId 会话 id
     * @param imageBase64 图片 base64(无 data: 前缀)
     * @param visionModelId 视觉模型 id
     * @param description 视觉描述(不含 <vision-context> 标签)
     */
    suspend fun put(sessionId: String, imageBase64: String, visionModelId: String, description: String) {
        activateSession(sessionId)
        val imageHash = hash(imageBase64)
        val key = makeKey(imageHash, visionModelId)
        mutex.withLock {
            memoryCache[key] = description
            saveEntries(sessionId, memoryCache.map { (k, desc) ->
                val (hash, modelId) = parseKey(k)
                CacheEntry(hash, modelId, desc, System.currentTimeMillis())
            })
        }
    }

    /**
     * 清空指定会话的缓存(内存 + sidecar)。
     *
     * 用于会话删除或用户手动刷新视觉辅助时。
     */
    suspend fun clearSession(sessionId: String) = mutex.withLock {
        if (activeSessionId == sessionId) {
            memoryCache.clear()
            activeSessionId = null
        }
        fileFor(sessionId).delete()
    }

    private fun makeKey(imageHash: String, visionModelId: String) = "$imageHash|$visionModelId"

    private fun parseKey(key: String): Pair<String, String> {
        val idx = key.lastIndexOf('|')
        return key.substring(0, idx) to key.substring(idx + 1)
    }

    private fun hash(base64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(base64.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(sessionId: String): File {
        cacheDir.mkdirs()
        return File(cacheDir, "$sessionId.json")
    }

    private suspend fun loadEntries(sessionId: String): List<CacheEntry> = withContext(Dispatchers.IO) {
        val f = fileFor(sessionId)
        if (!f.exists()) return@withContext emptyList()
        resultOf {
            val text = f.readText()
            if (text.isBlank()) emptyList()
            else AppJson.decodeFromString(ListSerializer(CacheEntry.serializer()), text)
        }.onError { msg, t ->
            Logger.w(TAG, "加载视觉缓存失败: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    private suspend fun saveEntries(sessionId: String, entries: List<CacheEntry>) = withContext(Dispatchers.IO) {
        resultOf {
            val f = fileFor(sessionId)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            val text = AppJson.encodeToString(ListSerializer(CacheEntry.serializer()), entries)
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }.onError { msg, t ->
            Logger.w(TAG, "保存视觉缓存失败: $msg", t)
        }
    }

    companion object {
        private const val TAG = "VisionCache"
    }
}
