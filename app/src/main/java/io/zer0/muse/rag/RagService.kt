package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsRow
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * v1.54: RAG 编排服务(分块 + embedding + 索引 + 检索 + 注入)。
 *
 * v1.133 改造:
 *  - embedding 存储:BLOB(替代 JSON,5× 加速)
 *  - FTS4 同步:索引文档时同步建立 FTS 索引,删文档时同步清理
 *  - 多知识库:retrieve 支持 [scopeDocIds](@mention 定向 / 助手绑定 KB)
 *  - 混合检索:[hybridSearchService] 提供 BM25+向量 RRF 融合
 *  - Rerank:[rerankProvider] 对 top-K×5 候选重排序
 *  - token 预算:[tokenBudget] 控制注入 token 数,与 webSearch 共享预算池
 *  - 引用返回:[buildInjectionContextWithCitations] 返回 [RagInjection](含 citations)
 */
class RagService(
    private val chunkDao: KnowledgeChunkDao,
    private val docDao: KnowledgeDocDao,
    private val ftsDao: KnowledgeChunkFtsDao,
    private val docTitleProvider: suspend () -> Map<String, String>,
    private val embeddingService: EmbeddingService,
    /** v1.133: 混合检索服务(可选,仅 [RagConfig.hybridEnabled]=true 时启用)。 */
    private val hybridSearchService: HybridSearchService? = null,
    /** v1.133: Rerank Provider(可选,仅 [RagConfig.rerankEnabled]=true 时启用)。 */
    private val rerankProvider: RerankProvider? = null,
    /**
     * v1.134: 本地 ONNX Cross-Encoder Rerank Provider(可选)。
     *
     * 仅当 [RagConfig.rerankProvider] == "onnx" 时启用;模型不可用时降级到 [rerankProvider]。
     */
    private val onnxRerankProvider: OnnxRerankProvider? = null,
    /**
     * v1.103: 向量检索无结果时的关键词兜底。
     * v1.133 改进:返回 Pair<title, snippet> 列表,snippet 取首个 chunk(替代原 content.take(500))。
     */
    private val keywordSearchFallback: (suspend (String, Int) -> List<Pair<String, String>>)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val embedBatchSize = 50

    // v1.133: docTitle TTL 缓存 — 避免每次检索都查全表(原 docTitleProvider 每次 observeAll().first())
    // 5 分钟过期,文档增删后最多 5 分钟同步;indexDocument/deleteDocIndex 时主动失效
    @Volatile
    private var cachedTitles: Map<String, String>? = null
    @Volatile
    private var cachedTitlesAt: Long = 0
    private val titlesMutex = kotlinx.coroutines.sync.Mutex()

    /** v1.133: 带缓存的 docTitle 获取 — 5 分钟 TTL,失效后重新加载。 */
    private suspend fun getCachedTitles(): Map<String, String> {
        val now = System.currentTimeMillis()
        cachedTitles?.let {
            if (now - cachedTitlesAt < TITLES_TTL_MS) return it
        }
        return titlesMutex.withLock {
            // 双重检查,避免并发时重复加载
            cachedTitles?.let {
                if (now - cachedTitlesAt < TITLES_TTL_MS) return@withLock it
            }
            val titles = resultOf { docTitleProvider() }
                .onError { msg, e -> Logger.w("RagService", "docTitle 加载失败: $msg", e) }
                .getOrNull() ?: emptyMap()
            cachedTitles = titles
            cachedTitlesAt = System.currentTimeMillis()
            titles
        }
    }

    /** v1.133: 主动失效 docTitle 缓存(文档增删后调用)。 */
    private fun invalidateTitlesCache() {
        cachedTitles = null
        cachedTitlesAt = 0
    }

    private val vectorSearch = VectorSearchService(
        chunkPageProvider = { limit, offset ->
            val titles = getCachedTitles()
            chunkDao.getPageWithEmbedding(limit, offset).map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                )
            }
        },
        chunkCountProvider = { chunkDao.countIndexed() },
        chunkPageByDocIdsProvider = { docIds, limit, offset ->
            val titles = getCachedTitles()
            // v1.133: 按 docIds 过滤的候选集(@mention 定向检索用)
            val chunks = if (docIds.isNotEmpty()) chunkDao.getByDocIds(docIds) else emptyList()
            chunks.map { chunk ->
                VectorSearchService.ChunkWithDoc(
                    chunkId = chunk.id,
                    docId = chunk.docId,
                    docTitle = titles[chunk.docId] ?: "Unknown Document",
                    content = chunk.content,
                    embedding = chunk.embedding,
                    embeddingBlob = chunk.embeddingBlob,
                    chunkIndex = chunk.chunkIndex,
                )
            }
        },
    )

    /**
     * 索引文档:分块 + embedding + 存储 + FTS 同步。
     * @return 分块数
     */
    suspend fun indexDocument(
        docId: String,
        content: String,
        ragConfig: RagConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        if (content.isBlank()) return 0
        val perfTimer = Perf.start("rag-index-$docId")

        // 1. 分块(v1.133:支持 markdownAware / chunkByToken)
        val chunker = TextChunker(
            targetSize = ragConfig.chunkSize,
            overlap = ragConfig.chunkOverlap,
            markdownAware = ragConfig.markdownAware,
            chunkByToken = ragConfig.chunkByToken,
        )
        val chunks = chunker.split(content)
        if (chunks.isEmpty()) return 0
        Logger.d("RagService", "文档 $docId 分块 ${chunks.size} 块(markdownAware=${ragConfig.markdownAware}, chunkByToken=${ragConfig.chunkByToken})")
        perfTimer.split("chunk")

        // 2. 批量 embedding
        val provider = embeddingService.getProvider(ragConfig)
        val embeddings = mutableListOf<FloatArray>()
        for (i in chunks.indices step embedBatchSize) {
            val batch = chunks.subList(i, minOf(i + embedBatchSize, chunks.size))
            val vectors = resultOf { provider.embed(batch.map { it.content }) }
                .onError { msg, e -> Logger.e("RagService", "Embedding 批次 $i 失败: $msg", e) }
                .getOrThrow()
            check(vectors.size == batch.size) {
                "Provider returned ${vectors.size} vectors for ${batch.size} chunks (batch $i)"
            }
            embeddings.addAll(vectors)
            onProgress(minOf(i + embedBatchSize, chunks.size), chunks.size)
        }
        perfTimer.split("embedding")

        // v1.133: embedding 维度一致性校验 — 新 embedding 维度必须与库中已有 chunk 维度一致
        // 维度不一致会导致向量检索 cosSim 永远为 0(被 size mismatch 拦截),知识库"看起来索引了但搜不到"。
        // 校验失败时抛异常,由上层 reindexAllInKbs/调用方记录为失败,不写入数据库。
        val newDim = embeddings.firstOrNull()?.size ?: 0
        if (newDim > 0) {
            val existingDim = resultOf { chunkDao.getFirstIndexedEmbeddingDim() }.getOrNull()
            if (existingDim != null && existingDim > 0 && existingDim != newDim) {
                Logger.e(
                    "RagService",
                    "Embedding 维度不匹配:库中已有 chunk 维度=$existingDim,新文档 $docId 维度=$newDim。" +
                        "请到知识库管理页点击\"重新索引全部\",用同一 embedding 模型重建索引。",
                )
                throw IllegalStateException(
                    "Embedding dimension mismatch: existing=$existingDim, new=$newDim (doc=$docId). " +
                        "Run 'reindex all' with the same embedding model.",
                )
            }
        }

        // 3. embedding 成功后删旧分块
        chunkDao.deleteByDoc(docId)
        ftsDao.deleteByDoc(docId)

        // 4. 存储 chunk + embedding(BLOB)
        val now = System.currentTimeMillis()
        val entities = chunks.mapIndexed { idx, chunk ->
            KnowledgeChunkEntity(
                id = "chunk-$docId-$idx",
                docId = docId,
                content = chunk.content,
                embedding = "",  // v1.133: 新数据只写 BLOB,JSON 列留空
                embeddingBlob = VectorSearchService.floatArrayToBlob(embeddings[idx]),
                chunkIndex = idx,
                tokenCount = chunker.estimateTokens(chunk.content),
                metadataJson = encodeMetadata(chunk.metadata),
                createdAt = now,
            )
        }
        chunkDao.insertAll(entities)

        // 5. FTS 同步索引(用于混合检索 BM25 路径)
        val ftsRows = entities.map {
            KnowledgeChunkFtsRow(chunkId = it.id, docId = it.docId, content = it.content)
        }
        resultOf { ftsDao.insertAll(ftsRows) }
            .onError { msg, e -> Logger.w("RagService", "FTS 同步失败(不影响向量检索): $msg", e) }

        perfTimer.split("store")

        // 6. 失效向量缓存 + 标题缓存(新文档入库后 docTitle 表会变)
        vectorSearch.invalidateCache()
        invalidateTitlesCache()

        Logger.d("RagService", "文档 $docId 索引完成:${entities.size} 块,维度 ${embeddings.firstOrNull()?.size ?: 0}")
        perfTimer.end()
        return entities.size
    }

    /**
     * v1.133: 检索 — 支持混合检索 / MMR / scopeDocIds。
     *
     * @param scopeDocIds 限定检索范围(@mention 定向检索 / 助手绑定 KB 用)
     */
    suspend fun retrieve(
        query: String,
        topK: Int,
        threshold: Float,
        ragConfig: RagConfig,
        scopeDocIds: List<String>? = null,
    ): List<VectorSearchService.SearchResult> {
        if (query.isBlank()) return emptyList()
        val start = System.currentTimeMillis()
        val provider = embeddingService.getProvider(ragConfig)
        val queryVector: FloatArray = resultOf { provider.embed(query) }
            .onError { msg, e -> Logger.w("RagService", "Query embedding 失败: $msg", e) }
            .getOrNull()
            ?: return emptyList<VectorSearchService.SearchResult>().also { Perf.log("rag-retrieve", System.currentTimeMillis() - start) }

        // v1.133: 混合检索路径
        if (ragConfig.hybridEnabled && hybridSearchService != null) {
            val hybrid = resultOf {
                hybridSearchService.hybridSearch(
                    query = query,
                    queryVector = queryVector,
                    topK = topK,
                    threshold = threshold,
                    mmrLambda = ragConfig.mmrLambda,
                    scopeDocIds = scopeDocIds,
                )
            }.onError { msg, e -> Logger.w("RagService", "混合检索失败,降级向量: $msg", e) }
                .getOrNull()
            if (hybrid != null) {
                Perf.log("rag-retrieve-hybrid", System.currentTimeMillis() - start)
                return hybrid.map {
                    VectorSearchService.SearchResult(
                        docId = it.docId,
                        docTitle = it.docTitle,
                        chunkContent = it.chunkContent,
                        score = it.rrfScore,
                        chunkIndex = it.chunkIndex,
                        chunkId = it.chunkId,
                    )
                }
            }
        }

        val results = vectorSearch.search(
            queryVector = queryVector,
            topK = topK,
            threshold = threshold,
            mmrLambda = ragConfig.mmrLambda,
            scopeDocIds = scopeDocIds,
        )
        Perf.log("rag-retrieve", System.currentTimeMillis() - start)
        return results
    }

    /**
     * v1.54: 构建注入文本(向后兼容)。新代码应使用 [buildInjectionContextWithCitations]。
     */
    suspend fun buildInjectionContext(
        query: String,
        ragConfig: RagConfig,
    ): String = buildInjectionContextWithCitations(query, ragConfig, scopeDocIds = null).text

    /**
     * v1.133: 构建注入 — 返回 [RagInjection](含文本 + 引用列表)。
     *
     * 支持:
     *  - 混合检索 / MMR / scopeDocIds
     *  - Rerank(对 top-K×5 候选重排序)
     *  - Token 预算控制(按相似度降序累加,超预算即停)
     *  - 引用列表(供 ChatUI 渲染)
     *
     * @param scopeDocIds 限定检索范围(@mention 定向检索用)
     */
    suspend fun buildInjectionContextWithCitations(
        query: String,
        ragConfig: RagConfig,
        scopeDocIds: List<String>? = null,
    ): RagInjection {
        val start = System.currentTimeMillis()
        if (query.isBlank()) return RagInjection("", emptyList(), 0)

        // 1. 检索(扩大候选池到 topK×5 供 Rerank 用)
        val activeRerankProvider = resolveRerankProvider(ragConfig)
        val candidateK = if (ragConfig.rerankEnabled && activeRerankProvider != null) ragConfig.topK * 5 else ragConfig.topK
        var results = retrieve(query, candidateK, ragConfig.threshold, ragConfig, scopeDocIds)

        // 2. Rerank
        var matchType = if (ragConfig.hybridEnabled) "hybrid" else "vector"
        if (ragConfig.rerankEnabled && activeRerankProvider != null && results.isNotEmpty()) {
            val candidates = results.map {
                RerankCandidate(
                    chunkId = it.chunkId,
                    docId = it.docId,
                    docTitle = it.docTitle,
                    content = it.chunkContent,
                    chunkIndex = it.chunkIndex,
                )
            }
            val reranked = resultOf { activeRerankProvider.rerank(query, candidates, ragConfig.topK) }
                .onError { msg, e -> Logger.w("RagService", "Rerank 失败,降级原序: $msg", e) }
                .getOrNull()
            if (reranked != null) {
                results = reranked.map { r ->
                    VectorSearchService.SearchResult(
                        docId = r.docId,
                        docTitle = r.docTitle,
                        chunkContent = r.content,
                        score = r.score,
                        chunkIndex = r.chunkIndex,
                        chunkId = r.chunkId,
                    )
                }
                matchType = if (ragConfig.rerankProvider == "onnx") "rerank-onnx" else "rerank"
            }
        } else {
            results = results.take(ragConfig.topK)
        }

        // 3. 构造文本 + 引用(应用 token 预算)
        if (results.isNotEmpty()) {
            val citations = mutableListOf<RagCitation>()
            val sb = StringBuilder()
            sb.appendLine("以下是从知识库检索到的相关资料,回答时请参考(相似度仅供参考):")
            var usedTokens = 0
            val budget = ragConfig.tokenBudget
            for ((idx, r) in results.withIndex()) {
                val chunkSnippet = r.chunkContent.take(200)
                val chunkTokens = TokenEstimator.estimate(r.chunkContent)
                // token 预算控制(0 = 不限制)
                if (budget > 0 && usedTokens + chunkTokens > budget) {
                    Logger.d("RagService", "Token 预算用尽(budget=$budget, used=$usedTokens, 剩余 ${results.size - idx} 条丢弃)")
                    break
                }
                usedTokens += chunkTokens
                sb.appendLine("[${idx + 1}] 来源: ${r.docTitle} (相似度 ${"%.2f".format(r.score)})")
                sb.appendLine(r.chunkContent)
                sb.appendLine()
                citations.add(
                    RagCitation(
                        index = idx + 1,
                        docId = r.docId,
                        docTitle = r.docTitle,
                        chunkId = r.chunkId,
                        chunkIndex = r.chunkIndex,
                        snippet = chunkSnippet,
                        score = r.score,
                        matchType = matchType,
                    ),
                )
            }
            return RagInjection(
                text = sb.toString().trimEnd(),
                citations = citations,
                elapsedMs = System.currentTimeMillis() - start,
            )
        }

        // 4. 向量检索无结果 → 关键词兜底(LIKE 子串匹配)
        val fallback = keywordSearchFallback ?: return RagInjection("", emptyList(), System.currentTimeMillis() - start)
        val fallbackResults = resultOf { fallback(query, ragConfig.topK) }
            .onError { msg, e -> Logger.w("RagService", "关键词兜底失败: $msg", e) }
            .getOrNull() ?: emptyList()
        if (fallbackResults.isEmpty()) return RagInjection("", emptyList(), System.currentTimeMillis() - start)

        val citations = mutableListOf<RagCitation>()
        val sb = StringBuilder()
        sb.appendLine("以下是从知识库检索到的相关资料(关键词匹配),回答时请参考:")
        var usedTokens = 0
        val budget = ragConfig.tokenBudget
        for ((idx, pair) in fallbackResults.withIndex()) {
            val (title, snippet) = pair
            val snippetTokens = TokenEstimator.estimate(snippet)
            if (budget > 0 && usedTokens + snippetTokens > budget) break
            usedTokens += snippetTokens
            sb.appendLine("[${idx + 1}] 来源: $title")
            sb.appendLine(snippet)
            sb.appendLine()
            citations.add(
                RagCitation(
                    index = idx + 1,
                    docId = "",
                    docTitle = title,
                    chunkId = "",
                    chunkIndex = 0,
                    snippet = snippet.take(200),
                    score = 0f,
                    matchType = "keyword_fallback",
                ),
            )
        }
        return RagInjection(
            text = sb.toString().trimEnd(),
            citations = citations,
            elapsedMs = System.currentTimeMillis() - start,
        )
    }

    /** 删除文档的全部分块索引(含 FTS)。 */
    suspend fun deleteDocIndex(docId: String) {
        chunkDao.deleteByDoc(docId)
        resultOf { ftsDao.deleteByDoc(docId) }
            .onError { msg, e -> Logger.w("RagService", "FTS 清理失败: $msg", e) }
        vectorSearch.invalidateCache()
        invalidateTitlesCache()
    }

    /**
     * v1.133: 批量重新索引指定 KB 下的全部文档(用于 KB 管理页"重新索引全部"按钮)。
     *
     * 流程:
     *  1. 通过 [KnowledgeDocDao.getByKbIds] 取 KB 下全部文档
     *  2. 逐篇调用 [indexDocument](内部会先 deleteByDoc 再重建)
     *  3. 每篇完成后回调 [onProgress](current, total)
     *  4. 任意一篇失败不中断,继续下一篇,最终返回失败的 docId 列表
     *
     * @param kbIds 要重索引的 KB id 列表(空列表 = 不执行)
     * @param ragConfig 使用的 RAG 配置(分块参数/embedding 来源)
     * @param onProgress 进度回调,(current, total) — 1-based
     * @return 失败的 docId → 失败原因 Map
     */
    suspend fun reindexAllInKbs(
        kbIds: List<String>,
        ragConfig: RagConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, String> {
        if (kbIds.isEmpty()) return emptyMap()
        val docs = resultOf { docDao.getByKbIds(kbIds) }
            .onError { msg, e -> Logger.e("RagService", "KB 重索引:加载文档失败: $msg", e) }
            .getOrNull() ?: return emptyMap<String, String>().also {
            Logger.w("RagService", "KB 重索引:无文档可处理")
        }
        val failures = mutableMapOf<String, String>()
        val total = docs.size
        Logger.i("RagService", "KB 重索引开始:共 $total 篇文档(kbIds=$kbIds)")
        for ((idx, doc) in docs.withIndex()) {
            try {
                indexDocument(doc.id, doc.content, ragConfig)
            } catch (e: Throwable) {
                Logger.w("RagService", "KB 重索引:文档 ${doc.id} 失败: ${e.message}", e)
                failures[doc.id] = e.message ?: e.javaClass.simpleName
            }
            onProgress(idx + 1, total)
        }
        Logger.i("RagService", "KB 重索引完成:成功 ${total - failures.size}/$total,失败 ${failures.size}")
        return failures
    }

    fun invalidateVectorCache() = vectorSearch.invalidateCache()

    suspend fun indexedChunkCount(): Int = chunkDao.countIndexed()

    /**
     * v1.134: 根据当前 [RagConfig] 解析实际使用的 Rerank Provider。
     *
     * - rerankEnabled=false → null(不重排)
     * - rerankProvider="onnx" + onnxRerankProvider 可用 → OnnxRerankProvider
     * - rerankProvider="onnx" + onnxRerankProvider 不可用 → 降级 rerankProvider(LocalRerankProvider)
     * - 其他 → rerankProvider(LocalRerankProvider)
     */
    private fun resolveRerankProvider(ragConfig: RagConfig): RerankProvider? {
        if (!ragConfig.rerankEnabled) return null
        if (ragConfig.rerankProvider == "onnx" && onnxRerankProvider != null && onnxRerankProvider.isAvailable()) {
            return onnxRerankProvider
        }
        return rerankProvider
    }

    /**
     * v1.133: 解析 @mention 文本为 docId 列表(@mention 定向检索用)。
     *
     * 流程:
     *  1. 从 [text] 中提取所有 @xxx 标记(去掉 @ 前缀)
     *  2. 对每个 mention 关键词调用 [KnowledgeDocDao.findByTitleKeyword] 模糊匹配
     *  3. 合并所有命中文档的 id(去重)
     *
     * @return 去重后的 docId 列表;空列表表示未命中任何文档(调用方应走全库检索)
     */
    suspend fun resolveMentionToDocIds(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val mentions = MENTION_REGEX.findAll(text).map { it.value.removePrefix("@").trim() }
            .filter { it.isNotBlank() }.toList()
        if (mentions.isEmpty()) return emptyList()
        val docIds = linkedSetOf<String>()
        for (mention in mentions) {
            val escaped = KnowledgeDocDao.escapeLikeQuery(mention)
            val docs = resultOf { docDao.findByTitleKeyword(escaped) }
                .onError { msg, e -> Logger.w("RagService", "@mention 解析失败 '$mention': $msg", e) }
                .getOrNull() ?: emptyList()
            docs.forEach { docIds.add(it.id) }
        }
        Logger.d("RagService", "@mention 解析: ${mentions.size} 个 mention → ${docIds.size} 个 docId")
        return docIds.toList()
    }

    /** v1.133: @mention 提取正则(与 ChatViewModel.KNOWLEDGE_MENTION_REGEX 同义,RagService 自用)。 */
    private companion object {
        val MENTION_REGEX = Regex("@[^\\s@]+")
        /** docTitle 缓存 TTL:5 分钟(文档增删时主动失效,无需等过期)。 */
        const val TITLES_TTL_MS = 5L * 60 * 1000
    }

    /** v1.133: 计算 content 的 SHA-256 哈希(增量更新用)。 */
    fun computeContentHash(content: String): String =
        resultOf {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: ""

    /** v1.133: 把 chunk metadata Map 序列化为 JSON。 */
    private fun encodeMetadata(meta: Map<String, String>): String =
        resultOf { json.encodeToString(MapSerializer(String.serializer(), String.serializer()), meta) }
            .getOrNull() ?: "{}"
}
