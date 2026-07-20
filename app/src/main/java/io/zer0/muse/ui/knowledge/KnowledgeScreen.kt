package io.zer0.muse.ui.knowledge

import io.zer0.common.Logger
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.ui.common.ConfirmDeleteDialog
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// L-KUI1: 内容截断上限,防止超大文档导致 OOM
private const val MAX_CONTENT_LENGTH = 500_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    dao: KnowledgeDocDao = koinInject(),
    ragService: io.zer0.muse.rag.RagService = koinInject(),
    documentParser: io.zer0.muse.doc.DocumentParser = koinInject(),
    ocrManager: io.zer0.muse.doc.OcrManager = koinInject(),
    settings: io.zer0.muse.data.SettingsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    // v1.66: 知识库排序切换(原仅按 updated_at DESC,现支持 4 种排序)
    var sortMode by remember { mutableStateOf(KnowledgeSortMode.UPDATED) }
    var showSortMenu by remember { mutableStateOf(false) }
    // v1.48: h13 首次加载(observeAll)用 null 显示加载态;搜索场景保留 emptyList,避免每次输入闪加载态
    // M-KB1: 搜索时转义 LIKE 通配符(% _ \),配合 DAO 的 ESCAPE '\' 子句
    val docs by remember(searchQuery) {
        if (searchQuery.isBlank()) dao.observeAll() else dao.search(io.zer0.muse.data.knowledge.KnowledgeDocDao.escapeLikeQuery(searchQuery))
    }.collectAsStateWithLifecycle(initialValue = if (searchQuery.isBlank()) null else emptyList())
    var detailTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }
    // v1.67-B: 导入可取消 — 保存 Job 引用,取消时清理半成品文档
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // v1.67-A: 重新索引状态
    var reindexing by remember { mutableStateOf(false) }
    var reindexProgress by remember { mutableStateOf("") }
    var reindexTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }

    // v1.54: 支持导入 txt/md/pdf/docx/epub/图片(OCR),导入后自动分块+生成 embedding 向量索引
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // v1.67-B: 保存 Job 引用以便取消
        importJob = scope.launch {
            importing = true
            importProgress = context.getString(R.string.knowledge_reading_file)
            val now = System.currentTimeMillis()
            var createdDocId: String? = null
            try {
                // L-KUI2: 用 ContentResolver 获取 DISPLAY_NAME,比 lastPathSegment 更可靠
                val fileName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
                } ?: "doc-$now"
                val lowerName = fileName.lowercase()
                // 根据扩展名选择解析方式
                val content = when {
                    lowerName.endsWith(".pdf") || lowerName.endsWith(".docx") ||
                        lowerName.endsWith(".doc") || lowerName.endsWith(".epub") ||
                        lowerName.endsWith(".pptx") -> {
                        importProgress = context.getString(R.string.knowledge_parsing_doc)
                        withContext(Dispatchers.IO) { documentParser.parse(uri, context) }
                    }
                    lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                        lowerName.endsWith(".jpeg") || lowerName.endsWith(".bmp") ||
                        lowerName.endsWith(".webp") -> {
                        importProgress = context.getString(R.string.knowledge_ocr)
                        ocrManager.recognize(uri, context)
                    }
                    else -> {
                        // txt/md/csv/json 等纯文本
                        // v1.73: 用 use{} 确保 InputStream/BufferedReader 关闭,避免 FD 泄漏
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                // v1.114: 限制读取 10MB,防止超大文件 OOM
                                val MAX_READ_BYTES = 10L * 1024 * 1024
                                val sb = StringBuilder()
                                val buffer = CharArray(8192)
                                var total = 0L
                                input.bufferedReader().use { reader ->
                                    while (true) {
                                        val read = reader.read(buffer)
                                        if (read <= 0) break
                                        total += read
                                        if (total > MAX_READ_BYTES) {
                                            error("文件过大,超过 ${MAX_READ_BYTES / 1024 / 1024}MB 限制")
                                        }
                                        sb.append(buffer, 0, read)
                                    }
                                }
                                sb.toString()
                            }
                        }
                    }
                }
                if (content.isNullOrBlank()) {
                    MuseToast.show(context.getString(R.string.knowledge_import_empty))
                    return@launch
                }
                val fileType = when {
                    lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> "md"
                    lowerName.endsWith(".pdf") -> "pdf"
                    lowerName.endsWith(".docx") || lowerName.endsWith(".doc") -> "docx"
                    lowerName.endsWith(".epub") -> "epub"
                    lowerName.endsWith(".csv") -> "csv"
                    lowerName.endsWith(".json") -> "json"
                    lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                        lowerName.endsWith(".jpeg") -> "ocr"
                    else -> "txt"
                }
                val docId = "doc-$now"
                createdDocId = docId
                // L-KUI1: 提取常量,截断时记录日志提示
                val truncatedContent = if (content.length > MAX_CONTENT_LENGTH) {
                    Logger.w("KnowledgeScreen", "内容超过 $MAX_CONTENT_LENGTH 字符,已截断(原 ${content.length} 字)")
                    content.take(MAX_CONTENT_LENGTH)
                } else content
                dao.upsert(
                    KnowledgeDocEntity(
                        id = docId,
                        title = fileName,
                        content = truncatedContent,
                        filePath = uri.toString(),
                        fileType = fileType,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                // v1.54: 自动分块 + 生成 embedding 向量索引
                importProgress = context.getString(R.string.knowledge_chunking)
                // M-KUI1: 移除 runCatching,改为 try-catch 并重抛 CancellationException
                val ragConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "读取 RAG 配置失败: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                // v1.103: 不再静默吞异常。runCatching 失败时把原因拼进 toast,
                // 让用户知道为什么没建索引(网络/模型名/Provider 不兼容等),而不是只看到"已导入但未索引"。
                val indexResult = runCatching {
                    ragService.indexDocument(docId, truncatedContent, ragConfig) { current, total ->
                        importProgress = context.getString(R.string.knowledge_generating_vector, current, total)
                    }
                }
                val chunkCount = indexResult.getOrNull()
                if (chunkCount != null && chunkCount > 0) {
                    // 更新文档的分块数和 embedding 模型
                    val modelName = ragConfig.cloudModel.ifBlank { "cloud-default" }
                    dao.upsert(
                        (dao.getById(docId) ?: return@launch).copy(
                            chunkCount = chunkCount,
                            embeddingModel = modelName,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    MuseToast.show(context.getString(R.string.knowledge_imported_indexed, fileName, chunkCount))
                } else {
                    // v1.103: 索引失败时显示具体原因,引导用户去 RAG 设置检查配置
                    val reason = indexResult.exceptionOrNull()?.message?.take(100)
                    val msg = if (reason.isNullOrBlank()) {
                        context.getString(R.string.knowledge_imported_no_index, fileName)
                    } else {
                        "${context.getString(R.string.knowledge_imported_no_index, fileName)}\n原因:$reason"
                    }
                    MuseToast.show(msg)
                    Logger.w("KnowledgeScreen", "indexDocument failed for $fileName", indexResult.exceptionOrNull())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v1.67-B: 用户取消导入,清理半成品文档 + 已生成的分块
                createdDocId?.let { id ->
                    runCatching { dao.deleteDocWithChunks(id) }
                }
                MuseToast.show(context.getString(R.string.knowledge_import_cancelled))
                throw e
            } catch (e: java.io.IOException) {
                MuseToast.show(context.getString(R.string.knowledge_import_failed_read))
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_import_failed, e.message?.take(80) ?: ""))
            } finally {
                importing = false
                importProgress = ""
                importJob = null
            }
        }
    }

    // v1.67-A: 重新索引已有文档(更换 embedding 模型后或索引失败时可用)
    fun reindexDoc(doc: KnowledgeDocEntity) {
        reindexTarget = doc
        reindexing = true
        reindexProgress = context.getString(R.string.knowledge_reindex_progress)
        scope.launch {
            try {
                // M-KUI1: 移除 runCatching,改为 try-catch 并重抛 CancellationException
                val ragConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "读取 RAG 配置失败: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                val chunkCount = ragService.indexDocument(doc.id, doc.content, ragConfig) { current, total ->
                    reindexProgress = context.getString(R.string.knowledge_generating_vector, current, total)
                }
                if (chunkCount > 0) {
                    val modelName = ragConfig.cloudModel.ifBlank { "cloud-default" }
                    dao.upsert(
                        (dao.getById(doc.id) ?: return@launch).copy(
                            chunkCount = chunkCount,
                            embeddingModel = modelName,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    MuseToast.show(context.getString(R.string.knowledge_reindexed, doc.title, chunkCount))
                } else {
                    MuseToast.show(context.getString(R.string.knowledge_reindex_failed, doc.title))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_reindex_failed, e.message?.take(80) ?: ""))
            } finally {
                reindexing = false
                reindexProgress = ""
                reindexTarget = null
            }
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.knowledge_title),
                onBack = onBack,
                actions = {
                    // v1.66: 排序切换入口
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.knowledge_sort))
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        KnowledgeSortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(stringResource(mode.labelRes)) },
                                onClick = {
                                    sortMode = mode
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!importing) importLauncher.launch("*/*") },
                shape = MuseShapes.mega,
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.knowledge_import)) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            // v0.23: 搜索框(实时过滤标题 + 内容)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.knowledge_search_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.knowledge_clear), modifier = Modifier.size(20.dp))
                        }
                    }
                },
                singleLine = true,
                shape = MuseShapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            val docsList = docs
            if (docsList == null) {
                // v1.48: h13 首次加载显示居中加载指示器,避免闪空状态
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // v0.43: 隐藏开发文档(fileType="devdoc" 是内部 devdoc,只供 LLM 通过 knowledge_search 查询,不向用户展示)
                // v1.66: 按 sortMode 排序(DAO 仅 updated_at DESC,其余维度在 UI 排序)
                // M-Kn1: 用 remember 缓存 filter+sort 结果,避免每次重组都重算
                val visibleDocs = remember(docsList, sortMode) {
                    docsList
                        .filter { it.fileType != "devdoc" }
                        .sortedWith(sortMode.comparator)
                }
                if (visibleDocs.isEmpty()) {
                    EmptyState(
                        icon = if (searchQuery.isNotBlank()) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.MenuBook,
                        title = if (searchQuery.isNotBlank()) stringResource(R.string.knowledge_no_match_title) else stringResource(R.string.knowledge_empty_title),
                        subtitle = if (searchQuery.isNotBlank()) stringResource(R.string.knowledge_no_match_subtitle) else stringResource(R.string.knowledge_empty_subtitle),
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp),
                    ) {
                        items(visibleDocs, key = { it.id }) { doc ->
                            DocCard(
                                doc = doc,
                                highlight = searchQuery.takeIf { it.isNotBlank() },
                                onClick = { detailTarget = doc },
                                onDelete = {
                                    scope.launch {
                                        dao.deleteDocWithChunks(doc.id)
                                    }
                                    MuseToast.show(context.getString(R.string.knowledge_deleted))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    detailTarget?.let { doc ->
        DocDetailDialog(
            doc = doc,
            onDismiss = { detailTarget = null },
            onReindex = { reindexDoc(doc) },
        )
    }

    if (importing) {
        MuseDialog(
            // v1.67-B: 导入可取消
            onDismissRequest = { importJob?.cancel() },
            title = stringResource(R.string.knowledge_importing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(importProgress.ifBlank { stringResource(R.string.knowledge_reading_default) }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmText = stringResource(R.string.knowledge_cancel),
            onConfirm = { importJob?.cancel() },
            dismissText = null,
        )
    }

    // v1.67-A: 重新索引进度弹窗
    if (reindexing) {
        MuseDialog(
            onDismissRequest = { /* 重新索引不可中断,避免半成品状态 */ },
            title = stringResource(R.string.knowledge_reindexing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        reindexProgress.ifBlank { stringResource(R.string.knowledge_reindexing_default) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            // 不可中断,无按钮(onConfirm=null 隐藏主按钮,dismissText=null 隐藏次按钮)
            onConfirm = null,
            dismissText = null,
        )
    }
}

@Composable
private fun DocCard(
    doc: KnowledgeDocEntity,
    highlight: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 内容预览(若搜索匹配,截取匹配片段;否则取开头)
                val preview = remember(doc.content, highlight) {
                    if (highlight.isNullOrBlank()) {
                        doc.content.take(120)
                    } else {
                        val idx = doc.content.indexOf(highlight, ignoreCase = true)
                        if (idx < 0) doc.content.take(120)
                        else {
                            val start = (idx - 40).coerceAtLeast(0)
                            val end = (idx + highlight.length + 80).coerceAtMost(doc.content.length)
                            "..." + doc.content.substring(start, end) + "..."
                        }
                    }
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // v1.71: 用 remember 缓存 SimpleDateFormat,避免列表项重组都新建对象
                val dateFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
                Text(
                    stringResource(R.string.knowledge_doc_meta_with_date, doc.fileType, doc.content.length, dateFmt.format(Date(doc.createdAt))),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.knowledge_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.knowledge_delete_doc),
            itemName = doc.title,
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun DocDetailDialog(
    doc: KnowledgeDocEntity,
    onDismiss: () -> Unit,
    onReindex: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = doc.title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    // v1.67-A: 显示分块数和 embedding 模型,让用户判断是否需要重新索引
                    text = buildString {
                        append(stringResource(R.string.knowledge_doc_chars, doc.fileType, doc.content.length))
                        append(if (doc.chunkCount > 0) stringResource(R.string.knowledge_doc_chunks, doc.chunkCount) else stringResource(R.string.knowledge_doc_not_indexed))
                        if (doc.embeddingModel.isNotBlank()) append(" · ${doc.embeddingModel}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    doc.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmText = stringResource(R.string.knowledge_close),
        onConfirm = onDismiss,
        // v1.67-A: 重新索引入口(更换 embedding 模型后或首次索引失败时可用)
        dismissText = stringResource(R.string.knowledge_reindex),
        onDismiss = {
            onReindex()
            onDismiss()
        },
    )
}

/**
 * v1.66: 知识库排序模式。
 * DAO 仅支持 updated_at DESC,其余维度在 UI 层用 comparator 排序。
 */
enum class KnowledgeSortMode(val labelRes: Int, val comparator: Comparator<KnowledgeDocEntity>) {
    UPDATED(R.string.knowledge_sort_updated, compareByDescending { it.updatedAt }),
    CREATED(R.string.knowledge_sort_created, compareByDescending { it.createdAt }),
    TITLE(R.string.knowledge_sort_title, compareBy { it.title.lowercase(Locale.getDefault()) }),
    SIZE(R.string.knowledge_sort_size, compareByDescending { it.content.length }),
}
