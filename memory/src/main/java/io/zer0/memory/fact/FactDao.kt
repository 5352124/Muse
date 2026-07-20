package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Fact 数据访问对象 (openhanako fact-store.ts prepared statements 移植)。
 *
 * v6: 新增 facts_fts FTS4 全文索引。
 *  - 全文搜索优先走 FTS4 MATCH(ngram 预处理)
 *  - 单字/异常时回退 LIKE '%query%'
 *  - 标签搜索用 json_each 精确匹配(避免 LIKE 子串误匹配)
 */
@Dao
interface FactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FactEntity>): List<Long>

    /**
     * v4: 按 importance 降序 + time 降序获取全部事实。
     * 关键(importance=2)和重要(importance=1)的事实排在前面,便于 UI 优先展示。
     */
    @Query("SELECT * FROM facts ORDER BY importance DESC, time DESC")
    suspend fun getAll(): List<FactEntity>

    @Query("SELECT * FROM facts WHERE id = :id")
    suspend fun getById(id: Long): FactEntity?

    @Query("SELECT * FROM facts WHERE session_id = :sessionId ORDER BY importance DESC, time DESC")
    suspend fun getBySession(sessionId: String): List<FactEntity>

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** P2: 更新指定 fact 的内容(用于记忆页 UI 编辑 Fact 层)。 */
    @Query("UPDATE facts SET fact = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String): Int

    /**
     * v4: 更新指定 fact 的重要程度(用于记忆页 UI 手动调整)。
     * importance: 0=普通,1=重要,2=关键
     */
    @Query("UPDATE facts SET importance = :importance WHERE id = :id")
    suspend fun updateImportance(id: Long, importance: Int): Int

    /** v5: 全字段更新(用于合并去重后替换内容)。 */
    @Query("UPDATE facts SET fact = :fact, tags = :tags, time = :time, session_id = :sessionId, created_at = :createdAt, importance = :importance, category = :category, confidence = :confidence, source = :source, expires_at = :expiresAt, last_confirmed_at = :lastConfirmedAt, last_hit_at = :lastHitAt WHERE id = :id")
    suspend fun updateEntity(id: Long, fact: String, tags: String, time: String?, sessionId: String?, createdAt: String, importance: Int, category: String, confidence: Float, source: String, expiresAt: String?, lastConfirmedAt: String?, lastHitAt: String?)

    /**
     * v5: 查找与给定文本前40字前缀匹配的事实(用于去重)。
     */
    @Query("SELECT * FROM facts WHERE fact LIKE :prefix || '%' ORDER BY importance DESC, created_at DESC LIMIT 5")
    suspend fun findSimilar(prefix: String): List<FactEntity>

    @Query("DELETE FROM facts")
    suspend fun deleteAll(): Int

    /**
     * 删除创建时间早于 [cutoffIso] 的全部 fact。
     * 由 [FactStore.applyDecay] 在 daily pipeline 中调用,实现配置驱动的遗忘。
     *
     * 用 created_at 而非 time:time 是 fact 自身描述的事件时间(可空且可远早于入库),
     * created_at 是落库时间,作为衰减基准更稳定。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE created_at < :cutoffIso")
    suspend fun deleteOlderThan(cutoffIso: String): Int

    /**
     * v4: 删除创建时间早于 [cutoffIso] 且 importance < [minImportance] 的 fact。
     * 关键事实(importance=2)通过 minImportance=3 永不删除,实现"永不衰减"。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE created_at < :cutoffIso AND importance < :minImportance")
    suspend fun deleteOlderThanExceptImportant(cutoffIso: String, minImportance: Int): Int

    /**
     * v7: 按命中时间衰减删除。
     *  - 从未命中(last_hit_at IS NULL)的事实按 created_at 判断
     *  - 已命中的事实按 last_hit_at 判断
     *  - importance < minImportance 的事实才会被删除
     *
     * 配合 [MemoryConfig.hitBonus] 实现"被引用的记忆更慢遗忘"。
     */
    @Query("""
        DELETE FROM facts
        WHERE importance < :minImportance
          AND (
            (last_hit_at IS NULL AND created_at < :neverHitCutoffIso)
            OR
            (last_hit_at IS NOT NULL AND last_hit_at < :hitCutoffIso)
          )
    """)
    suspend fun deleteOlderThanWithHit(neverHitCutoffIso: String, hitCutoffIso: String, minImportance: Int): Int

    /**
     * 全文搜索(LIKE,兼容所有 ROM)。
     * 在 fact 字段上做子串匹配,v4: 按 importance 降序 + time 降序。
     */
    @Query("SELECT * FROM facts WHERE fact LIKE '%' || :query || '%' ORDER BY importance DESC, time DESC LIMIT :limit")
    suspend fun likeSearch(query: String, limit: Int): List<FactEntity>

    /**
     * v6: FTS4 全文搜索。
     * 使用已 ngram 化的 MATCH 表达式(由 [FactFtsManager.toMatchQuery] 生成)。
     */
    @Query("""
        SELECT f.* FROM facts_fts
        JOIN facts f ON facts_fts.fact_id = f.id
        WHERE content_ngram MATCH :matchQuery
        ORDER BY f.importance DESC, f.time DESC
        LIMIT :limit
    """)
    suspend fun searchFts(matchQuery: String, limit: Int): List<FactEntity>

    /**
     * 标签 + 日期范围搜索。SQL 由 [FactStore.searchByTags] 动态拼接
     * (因为 tag 数量和日期范围组合是动态的,Room 静态 @Query 难以表达)。
     */
    @RawQuery
    suspend fun tagSearch(query: SupportSQLiteQuery): List<FactTagSearchRow>

    // ── v6: FTS4 索引同步 ──

    /** 插入/更新 facts_fts 索引(fact_id 不索引,content_ngram 全文索引)。 */
    @Query("INSERT OR REPLACE INTO facts_fts(fact_id, content_ngram) VALUES(:factId, :contentNgram)")
    suspend fun insertFts(factId: Long, contentNgram: String)

    /** 删除指定 fact 的 FTS 索引。 */
    @Query("DELETE FROM facts_fts WHERE fact_id = :factId")
    suspend fun deleteFts(factId: Long)

    /** 清空 FTS 索引(rebuild 用)。 */
    @Query("DELETE FROM facts_fts")
    suspend fun clearFts()

    /** 取全部事实(rebuild 索引用,只取 id + fact)。 */
    @Query("SELECT id, fact FROM facts")
    suspend fun getAllForFtsRebuild(): List<FtsRebuildRow>

    /** facts 表行数(ensureFtsIndexConsistent 比较用)。 */
    @Query("SELECT COUNT(*) FROM facts")
    suspend fun countFacts(): Int

    /** facts_fts 表行数(ensureFtsIndexConsistent 比较用)。 */
    @Query("SELECT COUNT(*) FROM facts_fts")
    suspend fun countFts(): Int
}

/** 标签搜索结果(带 matchCount)。v4: 含 importance 字段。 */
data class FactTagSearchRow(
    val id: Long,
    val fact: String,
    val tags: String,
    val time: String?,
    @androidx.room.ColumnInfo(name = "session_id")
    val sessionId: String?,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: String,
    val importance: Int = 0,
    val category: String = "general",
    val confidence: Float = 1.0f,
    val source: String = "inferred",
    val expiresAt: String? = null,
    val lastConfirmedAt: String? = null,
    val lastHitAt: String? = null,
    val matchCount: Int,
)

/** FTS rebuild 用轻量行。 */
data class FtsRebuildRow(
    val id: Long,
    val fact: String,
)
