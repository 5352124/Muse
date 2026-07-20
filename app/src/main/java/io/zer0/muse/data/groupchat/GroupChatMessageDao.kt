package io.zer0.muse.data.groupchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 群聊消息计数投影(按 chatId 分组,用于 Top N 活跃群聊)。
 */
data class GroupChatMessageCount(
    val chatId: String,
    val cnt: Int,
)

/**
 * 群聊消息数据访问对象。
 *
 * 负责消息实体的 CRUD + Flow 观察,以及按 chatId 分组统计(Top N 活跃群聊)。
 */
@Dao
interface GroupChatMessageDao {

    /**
     * 观察指定群聊的消息(按 timestamp 升序,聊天界面用)。
     * M4: 暂用 LIMIT 200 上限,后续可改 PagingSource。
     * M8: 用 id 作为次级排序 key,避免同毫秒碰撞。
     */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC LIMIT 200")
    fun observeMessages(chatId: String): Flow<List<GroupChatMessageEntity>>

    /** 插入或更新消息(冲突时替换)。 */
    @Upsert
    suspend fun upsert(message: GroupChatMessageEntity)

    /** 批量插入消息。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<GroupChatMessageEntity>)

    /** 按 id 删除单条消息。 */
    @Query("DELETE FROM group_chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 删除指定群聊的全部消息(群聊删除时级联调用)。 */
    @Query("DELETE FROM group_chat_messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: String)

    /** 取指定群聊的最近 N 条消息(按 timestamp 降序取再反转,供 Agent 工具读取上下文)。M8: 用 id 作次级排序 key。 */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: String, limit: Int): List<GroupChatMessageEntity>

    /** 按 chatId 分组统计消息数,取 Top N 活跃群聊(降序)。 */
    @Query("SELECT chatId, COUNT(*) as cnt FROM group_chat_messages GROUP BY chatId ORDER BY cnt DESC LIMIT :limit")
    fun observeTopActiveChats(limit: Int): Flow<List<GroupChatMessageCount>>

    @Query("SELECT * FROM group_chat_messages")
    suspend fun getAll(): List<GroupChatMessageEntity>

    @Query("DELETE FROM group_chat_messages")
    suspend fun deleteAll()
}
