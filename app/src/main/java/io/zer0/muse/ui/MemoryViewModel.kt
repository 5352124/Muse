package io.zer0.muse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.R
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter

/**
 * 阶段 6: 记忆页 UI 数据模型。
 *
 * 4 层记忆系统(Fact / Summary / Compile / Deep)的统一 UI 投影:
 *  - Fact 层:元事实列表(可搜索)
 *  - Summary 层:会话滚动摘要列表
 *  - Compile 层:4 段编译产物(facts/today/week/longterm)
 *  - Deep 层:无独立存储,展示统计信息(由 Fact 总数 + 最近更新时间推断)
 */
data class MemoryUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val factItems: List<MemoryItem> = emptyList(),
    val summaryItems: List<MemoryItem> = emptyList(),
    val compileItems: List<MemoryItem> = emptyList(),
    val searchResults: List<MemoryItem> = emptyList(),
    val isSearching: Boolean = false,
    val factCount: Int = 0,
    val summaryCount: Int = 0,
    /** v2.0: 筛选条件 */
    val importanceFilter: Int? = null,
    val timeFilter: String? = null,
    val typeFilter: String? = null,
    val lastUpdatedAt: String? = null,
    /** 完整错误堆栈(供 UI 可滚动展示,方便用户复制给开发者定位问题)。 */
    val errorTrace: String? = null,
    /**
     * v0.51: 记忆 dashboard 概览字段。
     *
     *  - [compiledMarkdown]:最近编译的 memory.md 摘要(由 [MemoryTicker.readCompiledMemoryMarkdown]
     *    裁剪到 tokenBudget 后的完整 markdown 文本,供"AI 对你的理解"卡片折叠展示)。
     *  - [healthMap]:MemoryTicker 各步骤健康快照(rollingSummary/compileDaily/compileToday/
     *    rollDailyWindow/compileFacts/deepMemory),用于在 dashboard 展示记忆健康状态。
     *  - [lastCompileTime]:最近一次成功编译时间(从 [healthMap] 的 lastSuccessAt 取最大值)。
     */
    val compiledMarkdown: String = "",
    val healthMap: Map<String, MemoryTicker.StepHealth> = emptyMap(),
    val lastCompileTime: String? = null,
    /** v4: 同步状态文案(如"最近编译 5 分钟前,最近的对话可能还未进入记忆")。 */
    val syncStatus: String = "",
    /** v5: 记忆统计字段。 */
    val totalFactCount: Int = 0,
    val weekNewCount: Int = 0,
    val monthNewCount: Int = 0,
    /** v5: 重要度分布 (0/1/2 的计数)。 */
    val importanceDistribution: Map<Int, Int> = emptyMap(),
    /** v5: 最活跃会话(top 5, sessionId → count)。 */
    val topSessions: List<Pair<String, Int>> = emptyList(),
    /** v5: 近30天每日新增数(日期 → 条数)。 */
    val dailyTrend: List<Pair<String, Int>> = emptyList(),
    /**
     * v1.98: 经验库相关 state。
     *
     * - [experienceEnabled]:经验库开关(用户在设置页切换,实时反映)。
     * - [experienceItems]:经验条目列表(按 updatedAt 降序,供 UI 展示与编辑)。
     * - [experienceCount]:条目总数(用于 dashboard 指标)。
     */
    val experienceEnabled: Boolean = false,
    val experienceItems: List<MemoryItem> = emptyList(),
    val experienceCount: Int = 0,
)

/**
 * 单条记忆 UI 项 — 跨 4 层统一形态。
 *
 * @param id 唯一标识(factId / sessionId / sectionKey)
 * @param title 标题(事实摘要 / 会话标题 / 编译段名)
 * @param content 完整内容(markdown 友好)
 * @param tags 标签(仅 Fact 层有)
 * @param time 时间戳文案
 * @param source 来源层("Fact" / "Summary" / "Compile")
 */
data class MemoryItem(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val time: String? = null,
    val source: String,
    /** v4: 重要程度(0=普通,1=重要,2=关键)。仅 Fact 层有值。 */
    val importance: Int = 0,
    /** v4: 来源会话 id(仅 Fact 层有值,用于显示来源)。 */
    val sessionId: String? = null,
    /** v4: 入库时间 ISO 8601(用于显示"X天前入库")。 */
    val createdAt: String? = null,
    /** v1.98: 分类(仅 Experience 层有值,编辑时回填到表单)。 */
    val category: String? = null,
)

/**
 * 阶段 6: 记忆页 ViewModel。
 *
 * 数据来源(全部已通过 Koin 注册):
 *  - [FactStore]:Fact 层 + LIKE 搜索
 *  - [SessionSummaryManager]:Summary 层
 *  - [MemoryCompiler]:Compile 层(读取 4 段编译产物)
 *
 * Deep 层无独立存储,通过 Fact 总数 + 时间戳推断展示。
 */
class MemoryViewModel(
    application: Application,
    private val factStore: FactStore,
    private val summaryManager: SessionSummaryManager,
    private val memoryCompiler: MemoryCompiler,
    /** v0.51: 注入 MemoryTicker 用于读取 healthFlow 与裁剪后的 compiledMarkdown。 */
    private val memoryTicker: MemoryTicker,
    /** v1.98: 注入 SettingsRepository 用于读取经验库开关。 */
    private val settings: SettingsRepository,
    /** v1.98: 注入 ExperienceRepository 用于经验库 CRUD + observeAll。 */
    private val experienceRepository: ExperienceRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    init {
        // v0.51: 收集 MemoryTicker 的 healthFlow,实时反映记忆健康状态到 UI。
        // 各步骤成功/失败时间会在 dashboard 卡片展示,无需用户手动刷新。
        viewModelScope.launch {
            memoryTicker.healthFlow.collect { health ->
                val lastCompileTime = health.values
                    .mapNotNull { it.lastSuccessAt }
                    .maxOrNull()
                _state.update {
                    it.copy(
                        healthMap = health,
                        lastCompileTime = lastCompileTime,
                        syncStatus = computeSyncStatus(lastCompileTime),
                    )
                }
            }
        }
        // v1.98: 订阅经验库开关,实时反映设置页切换到 UI(决定是否展示经验库卡片)。
        viewModelScope.launch {
            settings.experienceEnabledFlow.collect { enabled ->
                _state.update { it.copy(experienceEnabled = enabled) }
            }
        }
        // v1.98: 订阅经验库条目流,CRUD 后自动刷新 UI(无需手动 loadAll)。
        viewModelScope.launch {
            experienceRepository.observeAll().collect { experiences ->
                val items = experiences.map { it.toMemoryItem() }
                _state.update {
                    it.copy(
                        experienceItems = items,
                        experienceCount = experiences.size,
                    )
                }
            }
        }
        loadAll()
    }

    /**
     * v1.78 (#7): 手动触发立即编译(对照 MemoryTicker.forceCompileNow)。
     * 用户在记忆页点"立即编译"按钮时调用,不等待 ticker 自动调度。
     */
    fun compileNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryTicker.forceCompileNow() }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "forceCompileNow 失败: $msg", t) }
            }
            loadAll()
        }
    }

    /**
     * 拉取全部 4 层数据(memory 模块 Repository 层全是 suspend,无 Flow,需主动 pull)。
     * 捕获完整崩溃堆栈到 state.errorTrace,供 UI 可滚动展示(方便用户复制给开发者)。
     */
    fun loadAll() {
        _state.update { it.copy(isLoading = true, errorTrace = null) }
        viewModelScope.launch {
            try {
                val facts = withContext(Dispatchers.IO) { factStore.getAll() }
                val summaries = withContext(Dispatchers.IO) { summaryManager.getAllSummaries() }
                val compileFacts = withContext(Dispatchers.IO) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.FACTS) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(FACTS) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileToday = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.TODAY) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(TODAY) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileWeek = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.WEEK) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(WEEK) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileLongterm = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.LONGTERM) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(LONGTERM) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                // v0.51: 读取裁剪后的 compiledMarkdown(供"AI 对你的理解"卡片折叠展示)。
                // 与 ChatService 注入 system prompt 同源,反映 AI 真正"看到"的记忆。
                val compiledMarkdown = withContext(Dispatchers.IO) {
                    resultOf { memoryTicker.readCompiledMemoryMarkdown() }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readCompiledMemoryMarkdown 失败: $msg", t) }
                        .getOrNull() ?: ""
                }

                val factItems = facts.map { fact ->
                    MemoryItem(
                        id = fact.id.toString(),
                        title = fact.fact.take(60),
                        content = fact.fact,
                        tags = fact.tags,
                        time = fact.time,
                        source = "Fact",
                        importance = fact.importance,
                        sessionId = fact.sessionId,
                        createdAt = fact.createdAt,
                    )
                }
                val summaryItems = summaries.map { summary ->
                    MemoryItem(
                        id = summary.sessionId,
                        title = getApplication<Application>().getString(R.string.memory_summary_title, summary.sessionId.take(8)),
                        content = summary.summary,
                        time = summary.updatedAt,
                        source = "Summary",
                    )
                }
                val compileItems = buildList {
                    if (compileFacts.isNotBlank()) add(MemoryItem("facts", getApplication<Application>().getString(R.string.memory_compile_section_facts), compileFacts, source = "Compile"))
                    if (compileToday.isNotBlank()) add(MemoryItem("today", getApplication<Application>().getString(R.string.memory_compile_section_today), compileToday, source = "Compile"))
                    if (compileWeek.isNotBlank()) add(MemoryItem("week", getApplication<Application>().getString(R.string.memory_compile_section_week), compileWeek, source = "Compile"))
                    if (compileLongterm.isNotBlank()) add(MemoryItem("longterm", getApplication<Application>().getString(R.string.memory_compile_section_longterm), compileLongterm, source = "Compile"))
                }

                // v5: 计算统计
                val now = Instant.now()
                val weekAgo = now.minus(7, java.time.temporal.ChronoUnit.DAYS)
                val monthAgo = now.minus(30, java.time.temporal.ChronoUnit.DAYS)
                val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault())
                val total = facts.size
                val weekNew = facts.count { f ->
                    runCatching { Instant.parse(f.createdAt).isAfter(weekAgo) }.getOrDefault(false)
                }
                val monthNew = facts.count { f ->
                    runCatching { Instant.parse(f.createdAt).isAfter(monthAgo) }.getOrDefault(false)
                }
                val impDist = facts.groupBy { it.importance.coerceIn(0, 2) }.mapValues { it.value.size }
                val topSessionsList = facts.groupBy { it.sessionId ?: "unknown" }
                    .mapValues { it.value.size }
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key to it.value }
                // 近30天每日趋势
                val dailyMap = mutableMapOf<String, Int>()
                facts.forEach { f ->
                    runCatching {
                        val day = dateFormat.format(Instant.parse(f.createdAt))
                        dailyMap[day] = (dailyMap[day] ?: 0) + 1
                    }
                }
                val dailyTrendList = dailyMap.entries
                    .sortedBy { it.key }
                    .takeLast(30)
                    .map { it.key to it.value }

                _state.update {
                    it.copy(
                        isLoading = false,
                        factItems = factItems,
                        summaryItems = summaryItems,
                        compileItems = compileItems,
                        factCount = facts.size,
                        summaryCount = summaries.size,
                        lastUpdatedAt = facts.maxByOrNull { it.createdAt }?.createdAt,
                        compiledMarkdown = compiledMarkdown,
                        syncStatus = computeSyncStatus(it.lastCompileTime),
                        totalFactCount = total,
                        weekNewCount = weekNew,
                        monthNewCount = monthNew,
                        importanceDistribution = impDist,
                        topSessions = topSessionsList,
                        dailyTrend = dailyTrendList,
                    )
                }
            } catch (e: CancellationException) {
                // v1.78 (H1): 必须重抛协程取消信号,否则会破坏协程取消语义
                throw e
            } catch (t: Throwable) {
                // 捕获完整堆栈信息(类名 + 消息 + stack trace),
                // 供 UI 在可滚动框中展示,用户可上下滑动查看完整错误并复制给开发者
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                val fullTrace = buildString {
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_header))
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_time) + java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.getDefault()).format(java.util.Date()))
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_exception) + t.javaClass.name)
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_message) + t.message)
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_device) + "${android.os.Build.BRAND} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_stack_header))
                    append(sw.toString())
                }
                _state.update { it.copy(isLoading = false, errorTrace = fullTrace) }
            }
        }
    }

    /**
     * 搜索查询(onValueChange 防抖由 UI 层处理,这里直接执行)。
     * 空查询清空搜索结果,非空走 LIKE 搜索。
     */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { factStore.searchFullText(query) }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "searchFullText 失败: $msg", t) }
                    .getOrNull() ?: emptyList()
            }
            val items = results.map { fact ->
                MemoryItem(
                    id = fact.id.toString(),
                    title = fact.fact.take(60),
                    content = fact.fact,
                    tags = fact.tags,
                    time = fact.time,
                    source = "Fact",
                    importance = fact.importance,
                    sessionId = fact.sessionId,
                    createdAt = fact.createdAt,
                )
            }
            _state.update { it.copy(searchResults = items, isSearching = false) }
        }
    }

    /**
     * 删除单条 Fact(仅 Fact 层支持删除)。
     */
    fun deleteFact(factId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { factStore.delete(id) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * P2: 删除单条 Summary(根据 sessionId)。
     */
    fun deleteSummary(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { summaryManager.delete(sessionId) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
            }
            loadAll()
        }
    }

    /**
     * P2: 清空单段 Compile 产物(根据 sectionKey:facts/today/week/longterm)。
     */
    fun deleteCompile(sectionKey: String) {
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryCompiler.clearSection(section) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
            }
            loadAll()
        }
    }

    /**
     * P2: 编辑单条 Fact 内容。
     */
    fun editFact(factId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { factStore.update(id, newContent) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * v4: 更新单条 Fact 的重要程度。
     * @param factId fact 的字符串 id
     * @param importance 0=普通,1=重要,2=关键
     */
    fun setFactImportance(factId: String, importance: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { factStore.setImportance(id, importance) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_set_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * 手动新增一条元事实。
     */
    fun addFact(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            // v1.78 (H6): loadAll 移出 runCatching,避免 add 成功但 loadAll 失败时
            // 错误信息显示"添加失败"(错误归因错位)
            val ok = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    factStore.add(FactStore.Fact(fact = content.trim()))
                }.onError { msg, t ->
                    _state.update { it.copy(errorTrace = (it.errorTrace ?: "") + "\n" + getApplication<Application>().getString(R.string.memory_add_failed, msg)) }
                }.isSuccess
            }
            if (ok) loadAll()
        }
    }

    /**
     * P2: 编辑单条 Summary 内容。
     */
    fun editSummary(sessionId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    val existing = summaryManager.getSummary(sessionId) ?: return@resultOf
                    summaryManager.saveSummary(
                        sessionId,
                        existing.copy(
                            summary = newContent.trim(),
                            updatedAt = Instant.now().toString(),
                        )
                    )
                }
            }
            loadAll()
        }
    }

    /**
     * P2: 编辑单段 Compile 产物内容。
     */
    fun editCompile(sectionKey: String, newContent: String) {
        if (newContent.isBlank()) return
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryCompiler.writeSection(section, newContent.trim()) }
            }
            loadAll()
        }
    }

    /**
     * v4: 根据 lastCompileTime(ISO 8601)计算同步状态文案。
     *  - null: 尚未编译
     *  - < 10 分钟: 记忆已同步
     *  - >= 10 分钟: 最近的对话可能还未进入记忆
     */
    private fun computeSyncStatus(lastCompileTime: String?): String {
        if (lastCompileTime == null) {
            return getApplication<Application>().getString(R.string.memory_sync_never)
        }
        return try {
            val minutes = Duration.between(
                Instant.parse(lastCompileTime),
                Instant.now(),
            ).toMinutes()
            if (minutes < 10) {
                getApplication<Application>().getString(R.string.memory_sync_recent, minutes)
            } else {
                getApplication<Application>().getString(R.string.memory_sync_stale, minutes)
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ── v2.0: 记忆筛选 CRUD ───────────────────────────────────────────────

    /** 设置重要性筛选(null=全部, 0=普通, 1=重要, 2=关键)。 */
    fun setImportanceFilter(importance: Int?) {
        _state.update { it.copy(importanceFilter = importance) }
    }

    /** 设置时间范围筛选(null=全部, "today"/"week"/"month")。 */
    fun setTimeFilter(time: String?) {
        _state.update { it.copy(timeFilter = time) }
    }

    /** 设置类型筛选(null=全部, "fact"/"summary"/"compile")。 */
    fun setTypeFilter(type: String?) {
        _state.update { it.copy(typeFilter = type) }
    }

    /** v2.0: 根据筛选条件过滤记忆条目。 */
    private fun filterItems(items: List<MemoryItem>, importance: Int?, time: String?, type: String?): List<MemoryItem> {
        return items.filter { item ->
            val matchesImportance = importance == null || item.importance == importance
            val matchesType = type == null || item.source.equals(type, ignoreCase = true)
            val matchesTime = when (time) {
                null -> true
                "today" -> isToday(item.createdAt)
                "week" -> isThisWeek(item.createdAt)
                "month" -> isThisMonth(item.createdAt)
                else -> true
            }
            matchesImportance && matchesType && matchesTime
        }
    }

    private fun isToday(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate == today
        } catch (e: Exception) { false }
    }

    private fun isThisWeek(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
        } catch (e: Exception) { false }
    }

    private fun isThisMonth(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate.year == today.year && itemDate.month == today.month
        } catch (e: Exception) { false }
    }

    // ── v1.98: 经验库 CRUD ──────────────────────────────────────────────

    /**
     * v1.98: 手动新增一条经验。
     * @param title 标题(简短描述)
     * @param content 详细内容(经验正文)
     * @param category 分类(默认"通用")
     * @param tags 标签列表(默认空)
     */
    fun addExperience(title: String, content: String, category: String = "通用", tags: List<String> = emptyList()) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val entity = ExperienceEntity(
                    id = "exp_${now}_${(0..9999).random()}",
                    title = title.trim(),
                    content = content.trim(),
                    category = category.trim().ifBlank { "通用" },
                    tagsJson = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags),
                    source = "manual",
                    createdAt = now,
                    updatedAt = now,
                )
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { experienceRepository.upsert(entity) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_add_experience_failed, msg)) }
            }
            // observeAll Flow 会自动刷新 UI,无需手动 loadAll
        }
    }

    /**
     * v1.98: 编辑已有经验条目(标题/内容/分类/标签)。
     */
    fun editExperience(
        id: String,
        title: String,
        content: String,
        category: String = "通用",
        tags: List<String> = emptyList(),
    ) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    val existing = experienceRepository.getById(id) ?: return@resultOf
                    val updated = existing.copy(
                        title = title.trim(),
                        content = content.trim(),
                        category = category.trim().ifBlank { "通用" },
                        tagsJson = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags),
                        updatedAt = System.currentTimeMillis(),
                    )
                    experienceRepository.upsert(updated)
                }.onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_experience_failed, msg)) }
            }
        }
    }

    /**
     * v1.98: 删除单条经验。
     */
    fun deleteExperience(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { experienceRepository.delete(id) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_experience_failed, msg)) }
            }
        }
    }

    /**
     * v1.98: ExperienceEntity → MemoryItem 转换(复用 UI 统一形态)。
     *
     * source 字段标注为 "Experience",供 UI 层区分来源。
     * tags 从 tagsJson(JSON 数组字符串)解析为 List<String>。
     */
    private fun ExperienceEntity.toMemoryItem(): MemoryItem {
        val parsedTags = runCatching {
            AppJson.decodeFromString<List<String>>(tagsJson)
        }.getOrDefault(emptyList())
        val timeText = runCatching {
            java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.CHINA)
                .format(java.util.Date(updatedAt))
        }.getOrNull()
        return MemoryItem(
            id = id,
            title = title,
            content = content,
            tags = parsedTags,
            time = timeText,
            source = "Experience",
            category = category,
        )
    }
}
