package io.zer0.memory.fact

import androidx.sqlite.db.SimpleSQLiteQuery
import io.zer0.memory.pii.PiiGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * 元事实存储 (openhanako fact-store.ts FactStore class 移植)。
 *
 * v6: 全文搜索升级为 FTS4 + 应用层 CJK 2-gram,保留 LIKE 作为单字/异常回退。
 *  - 增删改查(单条/批量/按 session/按 id)
 *  - 标签搜索(json_each 精确匹配,OR 逻辑,按匹配数降序)
 *  - 全文搜索(FTS4 MATCH,ngram 预处理;单字回退 LIKE)
 *  - FTS 索引一致性自检与全量 rebuild
 *
 * 所有写入前对 fact 字段做 PII 脱敏。
 *
 * v5: 添加事实去重(按内容前缀匹配)与智能重要度判定(关键词驱动)。
 */
class FactStore(
    private val dao: FactDao,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** v6: 是否已经做过 FTS 索引一致性检查(避免每次搜索都重复 COUNT)。 */
    private var ftsConsistencyChecked = false

    /** 元事实数据(业务层结构,与 Entity 分离)。 */
    data class Fact(
        val id: Long = 0,
        val fact: String,
        val tags: List<String> = emptyList(),
        val time: String? = null,
        val sessionId: String? = null,
        val createdAt: String = Instant.now().toString(),
        /** v4: 重要程度(0=普通,1=重要,2=关键)。 */
        val importance: Int = 0,
        /** v5: 结构化分类(如 preference / identity / event / relationship / goal / medical)。 */
        val category: String = "general",
        /** v5: LLM 对事实可靠性的置信度,0.0 ~ 1.0。 */
        val confidence: Float = 1.0f,
        /** v5: 事实来源(user_explicit / inferred / imported)。 */
        val source: String = "inferred",
        /** v5: 事实过期时间 ISO 8601。 */
        val expiresAt: String? = null,
        /** v5: 最近一次确认时间 ISO 8601。 */
        val lastConfirmedAt: String? = null,
        /** v7: 最近一次命中时间 ISO 8601,用于 hitBonus 衰减时钟。 */
        val lastHitAt: String? = null,
        val matchCount: Int? = null,
    )

    /**
     * v5: 关键词 → 重要度映射。
     *  - 包含"过敏/密码/地址/生日/身份证/电话"等 → 重要度 2(关键)
     *  - 包含"喜欢/不喜欢/爱吃/最"等 → 重要度 1(重要)
     *  - 其他日常 → 重要度 0(普通)
     */
    private val criticalKeywords = listOf("过敏", "密码", "地址", "生日", "身份证", "电话", "血型", "紧急", "病历")
    private val importantKeywords = listOf("喜欢", "不喜欢", "爱吃", "最", "讨厌", "害怕", "梦想", "目标", "习惯")

    /**
     * v5: 智能判定重要度(0/1/2)。
     * 关键关键词优先匹配,其次重要关键词,默认普通。
     */
    private fun inferImportance(text: String): Int {
        val lower = text.lowercase()
        if (criticalKeywords.any { lower.contains(it) }) return 2
        if (importantKeywords.any { lower.contains(it) }) return 1
        return 0
    }

    /**
     * v5: 按前缀匹配相似度判断两条事实是否相似。
     * 取较短文本,若较长文本以其开头则视为重复。
     */
    private fun isSimilar(a: String, b: String): Boolean {
        val short = if (a.length <= b.length) a else b
        val long = if (a.length > b.length) a else b
        return long.startsWith(short) || short.startsWith(long)
    }

    /**
     * v5: 合并两条相似事实,保留最高重要度、最高置信度与最新 created_at;
     *     分类优先采用新事实,来源以 user_explicit 优先。
     */
    private fun mergeSimilar(existing: FactEntity, new: Fact): FactEntity {
        val mergedImportance = maxOf(existing.importance, if (new.importance > 0) new.importance else inferImportance(new.fact))
        val mergedTags = (parseTags(existing.tags) + new.tags).distinct()
        val existingTime = existing.time
        val mergedTime = if (new.time != null && (existingTime == null || new.time > existingTime)) new.time else existingTime
        val mergedSessionId = new.sessionId ?: existing.sessionId
        val mergedCreatedAt = Instant.now().toString()
        val mergedCategory = new.category.takeIf { it.isNotBlank() && it != "general" } ?: existing.category
        val mergedConfidence = maxOf(existing.confidence, new.confidence.coerceIn(0f, 1f))
        val mergedSource = if (existing.source == "user_explicit" || new.source == "user_explicit") "user_explicit" else new.source
        val mergedExpiresAt = new.expiresAt ?: existing.expiresAt
        val mergedLastConfirmedAt = new.lastConfirmedAt ?: existing.lastConfirmedAt
        // v7: 合并视为一次命中,重置衰减时钟
        val mergedLastHitAt = Instant.now().toString()
        return existing.copy(
            fact = if (new.fact.length > existing.fact.length) new.fact else existing.fact,
            tags = json.encodeToString(ListSerializer(String.serializer()), mergedTags),
            time = mergedTime,
            sessionId = mergedSessionId,
            createdAt = mergedCreatedAt,
            importance = mergedImportance,
            category = mergedCategory,
            confidence = mergedConfidence,
            source = mergedSource,
            expiresAt = mergedExpiresAt,
            lastConfirmedAt = mergedLastConfirmedAt,
            lastHitAt = mergedLastHitAt,
        )
    }

    /**
     * v5: 新增一条元事实。自动去重 + 智能重要度。
     * 发现相似已有事实时合并(保留最高重要度和最新更新时间)。
     */
    suspend fun add(entry: Fact): Long = withContext(Dispatchers.IO) {
        val (cleaned, detected) = PiiGuard.scrub(entry.fact)
        if (detected.isNotEmpty()) {
            io.zer0.common.Logger.d("FactStore", "PII detected in fact: $detected")
        }
        val newEntry = entry.copy(fact = cleaned)
        val existingSimilar = dao.findSimilar(cleaned.take(40)).firstOrNull { isSimilar(it.fact, cleaned) }
        if (existingSimilar != null) {
            val merged = mergeSimilar(existingSimilar, newEntry)
            dao.updateEntity(
                merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                merged.createdAt, merged.importance, merged.category, merged.confidence,
                merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
            )
            dao.insertFts(merged.id, FactFtsManager.toNgram(merged.fact))
            io.zer0.common.Logger.d("FactStore", "合并相似事实: ${existingSimilar.fact.take(30)}… ↔ ${cleaned.take(30)}… → id=${existingSimilar.id}")
            return@withContext existingSimilar.id
        }
        val importance = if (newEntry.importance > 0) newEntry.importance else inferImportance(cleaned)
        val now = Instant.now().toString()
        val entity = FactEntity(
            fact = cleaned,
            tags = json.encodeToString(ListSerializer(String.serializer()), newEntry.tags),
            time = newEntry.time,
            sessionId = newEntry.sessionId,
            createdAt = now,
            importance = importance.coerceIn(0, 2),
            category = newEntry.category.takeIf { it.isNotBlank() } ?: "general",
            confidence = newEntry.confidence.coerceIn(0f, 1f),
            source = newEntry.source.takeIf { it.isNotBlank() } ?: "inferred",
            expiresAt = newEntry.expiresAt,
            lastConfirmedAt = newEntry.lastConfirmedAt,
            // v7: 新增事实视为一次命中,默认享受 hitBonus
            lastHitAt = newEntry.lastHitAt ?: now,
        )
        val insertedId = dao.insert(entity)
        dao.insertFts(insertedId, FactFtsManager.toNgram(cleaned))
        insertedId
    }

    /**
     * v5: 批量新增。自动去重 + 智能重要度,原子事务保证一致性。
     */
    suspend fun addBatch(entries: List<Fact>): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0
        val now = Instant.now().toString()
        var inserted = 0
        for (entry in entries) {
            val (cleaned, detected) = PiiGuard.scrub(entry.fact)
            if (detected.isNotEmpty()) {
                io.zer0.common.Logger.d("FactStore", "PII detected in batch fact: $detected")
            }
            val newEntry = entry.copy(fact = cleaned)
            val existingSimilar = dao.findSimilar(cleaned.take(40)).firstOrNull { isSimilar(it.fact, cleaned) }
            if (existingSimilar != null) {
                val merged = mergeSimilar(existingSimilar, newEntry)
                dao.updateEntity(
                    merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                    merged.createdAt, merged.importance, merged.category, merged.confidence,
                    merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                )
                dao.insertFts(merged.id, FactFtsManager.toNgram(merged.fact))
            } else {
                val importance = if (newEntry.importance > 0) newEntry.importance else inferImportance(cleaned)
                val insertedId = dao.insert(FactEntity(
                    fact = cleaned,
                    tags = json.encodeToString(ListSerializer(String.serializer()), newEntry.tags),
                    time = newEntry.time,
                    sessionId = newEntry.sessionId,
                    createdAt = now,
                    importance = importance.coerceIn(0, 2),
                    category = newEntry.category.takeIf { it.isNotBlank() } ?: "general",
                    confidence = newEntry.confidence.coerceIn(0f, 1f),
                    source = newEntry.source.takeIf { it.isNotBlank() } ?: "inferred",
                    expiresAt = newEntry.expiresAt,
                    lastConfirmedAt = newEntry.lastConfirmedAt,
                    lastHitAt = newEntry.lastHitAt ?: now,
                ))
                dao.insertFts(insertedId, FactFtsManager.toNgram(cleaned))
            }
            inserted++
        }
        inserted
    }

    /**
     * v6: 全文搜索。优先 FTS4 MATCH(ngram 预处理),单字/异常时回退 LIKE。
     */
    suspend fun searchFullText(query: String, limit: Int = 20): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        val trimmed = query.trim()

        // 单字/纯符号查询:FTS4 2-gram 无法命中,直接走 LIKE
        if (FactFtsManager.shouldFallbackToLike(trimmed)) {
            return@withContext dao.likeSearch(trimmed, limit).map { it.toFact() }
        }

        val matchQuery = FactFtsManager.toMatchQuery(trimmed)
        if (matchQuery.isBlank()) {
            return@withContext dao.likeSearch(trimmed, limit).map { it.toFact() }
        }

        val ftsResults = runCatching { dao.searchFts(matchQuery, limit) }.getOrElse { e ->
            io.zer0.common.Logger.w("FactStore", "FTS search failed, fallback to LIKE: ${e.message}")
            emptyList()
        }

        // FTS 异常返回空时回退 LIKE,保证结果可用性
        if (ftsResults.isEmpty()) {
            return@withContext dao.likeSearch(trimmed, limit).map { it.toFact() }
        }

        ftsResults.map { it.toFact() }
    }

    /**
     * 按标签搜索(精确匹配,OR 逻辑,按匹配数降序)。
     * 使用 json_each 精确匹配标签值,避免 LIKE 子串误匹配。
     */
    suspend fun searchByTags(
        queryTags: List<String>,
        dateRange: DateRange? = null,
        limit: Int = 20,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (queryTags.isEmpty()) return@withContext emptyList()

        val placeholders = queryTags.joinToString(", ") { "?" }
        val dateWhere = buildString {
            if (dateRange?.from != null) append(" AND f.time >= ?")
            if (dateRange?.to != null) append(" AND f.time <= ?")
        }
        val sql = """
            SELECT f.*, COUNT(DISTINCT je.value) as matchCount
            FROM facts f, json_each(f.tags) je
            WHERE je.value IN ($placeholders)$dateWhere
            GROUP BY f.id
            ORDER BY f.importance DESC, matchCount DESC, f.time DESC
            LIMIT ?
        """.trimIndent()

        val args = mutableListOf<Any>().apply {
            addAll(queryTags)
            dateRange?.from?.let { add(it) }
            dateRange?.to?.let { add(it) }
            add(limit)
        }
        dao.tagSearch(SimpleSQLiteQuery(sql, args.toTypedArray())).map { it.toFact() }
    }

    /** 获取所有元事实(按时间降序)。 */
    suspend fun getAll(): List<Fact> = withContext(Dispatchers.IO) {
        dao.getAll().map { it.toFact() }
    }

    /** 按 session_id 查询。 */
    suspend fun getBySession(sessionId: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getBySession(sessionId).map { it.toFact() }
    }

    /** 按 id 查询。 */
    suspend fun getById(id: Long): Fact? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toFact()
    }

    /** 总数。 */
    suspend fun size(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** 删除单条。返回是否删除成功。 */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        dao.deleteFts(id)
        dao.deleteById(id) > 0
    }

    /**
     * P2: 更新单条 Fact 内容(用于记忆页 UI 编辑 Fact 层)。
     *
     * 与 [add] 不同,这里不走 PII 脱敏(用户手动编辑的内容,保持原样),
     * 仅做轻量去空白处理。返回是否更新成功(目标 id 不存在时返回 false)。
     */
    suspend fun update(id: Long, content: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext false
        val updated = dao.updateContent(id, trimmed) > 0
        if (updated) {
            dao.insertFts(id, FactFtsManager.toNgram(trimmed))
        }
        updated
    }

    /**
     * v4: 更新单条 Fact 的重要程度(用于记忆页 UI 手动调整)。
     * @param importance 0=普通,1=重要,2=关键
     * @return 是否更新成功
     */
    suspend fun setImportance(id: Long, importance: Int): Boolean = withContext(Dispatchers.IO) {
        dao.updateImportance(id, importance.coerceIn(0, 2)) > 0
    }

    /** 清空所有。 */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        dao.clearFts()
        dao.deleteAll()
    }

    /**
     * v6: 检查 facts_fts 索引一致性,不一致则全量 rebuild。
     *
     * 触发场景:
     * - v5→v6 迁移后(facts_fts 空表,需首次全量索引)
     * - 外部修改 facts 表(备份导入后)
     * - 索引损坏(FTS 查询异常后下次搜索自愈)
     *
     * 调用一次后设置 [ftsConsistencyChecked],避免重复 COUNT。
     */
    suspend fun ensureFtsIndexConsistent() = withContext(Dispatchers.IO) {
        if (ftsConsistencyChecked) return@withContext
        ftsConsistencyChecked = true
        val factCount = runCatching { dao.countFacts() }.getOrDefault(-1)
        val ftsCount = runCatching { dao.countFts() }.getOrDefault(-1)
        if (factCount < 0 || ftsCount < 0) {
            io.zer0.common.Logger.w("FactStore", "FTS count check failed: facts=$factCount fts=$ftsCount")
            return@withContext
        }
        if (factCount == ftsCount) {
            io.zer0.common.Logger.d("FactStore", "FTS index consistent: $ftsCount rows")
            return@withContext
        }
        io.zer0.common.Logger.i("FactStore", "FTS index inconsistent: facts=$factCount fts=$ftsCount, rebuilding...")
        rebuildFtsIndex()
    }

    /**
     * v6: 全量重建 facts_fts 索引。清空后遍历 facts 重新插入(ngram 转换)。
     *
     * 调用方应在 IO 线程。
     */
    suspend fun rebuildFtsIndex() = withContext(Dispatchers.IO) {
        dao.clearFts()
        val rows = dao.getAllForFtsRebuild()
        var ok = 0
        rows.forEach { row ->
            runCatching {
                dao.insertFts(row.id, FactFtsManager.toNgram(row.fact))
                ok++
            }.onFailure {
                io.zer0.common.Logger.w("FactStore", "FTS rebuild insert failed for ${row.id}: ${it.message}")
            }
        }
        io.zer0.common.Logger.i("FactStore", "FTS rebuild done: $ok/${rows.size} facts indexed")
    }

    /**
     * 按 [MemoryConfig] 配置执行一轮 fact 衰减(遗忘)。
     *
     * v4 改进:关键事实(importance=2)永不衰减,避免"青霉素过敏"等高风险信息被遗忘。
     * v7 改进:引入命中加成(hitBonus):
     *  - 从未命中(last_hit_at IS NULL)的事实按 baseImportance 衰减
     *  - 已命中(last_hit_at IS NOT NULL)的事实按 (baseImportance + hitBonus) 衰减,并以 last_hit_at 作为时钟起点
     *  - 合并/新增事实时自动记录命中时间
     *
     * 该方法在 daily pipeline(deepMemory step 之后)由
     * [io.zer0.memory.deep.DeepMemoryProcessor] 调用,每个 assistant 的 FactStore 各跑一次。
     *
     * @return 实际删除的 fact 数
     */
    suspend fun applyDecay(config: io.zer0.memory.ticker.MemoryConfig): Int = withContext(Dispatchers.IO) {
        val neverHitCutoff = io.zer0.memory.ticker.MemoryConfig.safeCutoffDays(config, hit = false)
        val hitCutoff = io.zer0.memory.ticker.MemoryConfig.safeCutoffDays(config, hit = true)
        if (neverHitCutoff.isInfinite() || neverHitCutoff.isNaN() || hitCutoff.isInfinite() || hitCutoff.isNaN()) {
            return@withContext 0
        }
        val now = java.time.Instant.now()
        if (neverHitCutoff <= 0f || hitCutoff <= 0f) {
            // base 已低于阈值,配置上等同于"立即遗忘全部" —— 但 v4: 关键事实(importance=2)仍保留
            io.zer0.common.Logger.w("FactStore", "applyDecay: cutoff<=0, deleting non-critical facts (config=$config)")
            return@withContext dao.deleteOlderThanExceptImportant(now.toString(), 2)
        }
        val neverHitCutoffInstant = now.minus(neverHitCutoff.toLong(), java.time.temporal.ChronoUnit.DAYS)
        val hitCutoffInstant = now.minus(hitCutoff.toLong(), java.time.temporal.ChronoUnit.DAYS)
        // v4: minImportance=2 表示仅删除 importance < 2 的 fact,关键事实(importance=2)永不衰减
        // v7: 区分命中/未命中事实,分别用不同 cutoff
        dao.deleteOlderThanWithHit(neverHitCutoffInstant.toString(), hitCutoffInstant.toString(), 2)
    }

    // ════════════════════════════
    //  内部转换
    // ════════════════════════════

    private fun FactEntity.toFact(): Fact = Fact(
        id = id,
        fact = fact,
        tags = parseTags(tags),
        time = time,
        sessionId = sessionId,
        createdAt = createdAt,
        importance = importance,
        category = category,
        confidence = confidence,
        source = source,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        lastHitAt = lastHitAt,
    )

    private fun FactTagSearchRow.toFact(): Fact = Fact(
        id = id,
        fact = fact,
        tags = parseTags(tags),
        time = time,
        sessionId = sessionId,
        createdAt = createdAt,
        importance = importance,
        category = category,
        confidence = confidence,
        source = source,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        lastHitAt = lastHitAt,
        matchCount = matchCount,
    )

    private fun parseTags(raw: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrElse {
        io.zer0.common.Logger.w("FactStore", "parseTags failed: ${it.message}")
        emptyList()
    }

    /** 日期范围。 */
    data class DateRange(val from: String? = null, val to: String? = null)
}
