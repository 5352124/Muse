package io.zer0.muse.tools.quicknote

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: 快速记录(轻量笔记)存储。
 *
 * 数据以 JSON 文件保存在应用私有目录,支持标题、正文、标签、置顶。
 * 模型可通过 ToolRegistry 中的 quick_note_* 工具读写维护。
 */
class QuickNoteStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "quicknotes/notes.json") }

    /** 列出记录,可选按关键字/标签过滤,置顶记录排在前面。 */
    fun list(keyword: String? = null, tag: String? = null, limit: Int = 50): List<QuickNote> {
        var all = readAll().sortedWith(compareByDescending<QuickNote> { it.pinned }.thenByDescending { it.updatedAtMillis })
        if (!keyword.isNullOrBlank()) {
            val kw = keyword.lowercase()
            all = all.filter {
                it.title.lowercase().contains(kw) || it.content.lowercase().contains(kw) || it.tags.any { t -> t.lowercase().contains(kw) }
            }
        }
        if (!tag.isNullOrBlank()) {
            all = all.filter { it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
        }
        return all.take(limit)
    }

    /** 搜索记录(与 list 关键字过滤行为一致)。 */
    fun search(keyword: String, limit: Int = 20): List<QuickNote> = list(keyword = keyword, limit = limit)

    /** 根据 id 获取单条记录。 */
    fun get(id: String): QuickNote? = readAll().find { it.id == id }

    /**
     * 添加一条记录。
     *
     * @return 记录 id
     */
    fun add(title: String, content: String, tags: List<String>): String {
        val now = System.currentTimeMillis()
        val note = QuickNote(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            content = content,
            tags = tags,
            pinned = false,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        save(readAll().toMutableList().apply { add(note) })
        return note.id
    }

    /** 更新指定记录。 */
    fun update(id: String, title: String?, content: String?, tags: List<String>?): Boolean {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = all[idx]
        all[idx] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            updatedAtMillis = System.currentTimeMillis(),
        )
        save(all)
        return true
    }

    /** 删除记录。 */
    fun remove(id: String): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) save(all)
        return removed
    }

    /** 切换置顶状态。 */
    fun setPinned(id: String, pinned: Boolean): Boolean {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(pinned = pinned, updatedAtMillis = System.currentTimeMillis())
        save(all)
        return true
    }

    private fun readAll(): List<QuickNote> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(QuickNote.serializer()), file.readText())
        } catch (e: Exception) {
            Logger.w(TAG, "读取快速记录失败: ${e.message}")
            emptyList()
        }
    }

    private fun save(list: List<QuickNote>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(QuickNote.serializer()), list))
        } catch (e: Exception) {
            Logger.w(TAG, "保存快速记录失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "QuickNoteStore"
    }
}

@Serializable
data class QuickNote(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("created_at") val createdAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long,
)
