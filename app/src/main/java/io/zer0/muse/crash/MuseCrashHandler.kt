package io.zer0.muse.crash

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.10: 全局崩溃处理器 + Safe Mode 崩溃恢复。
 *
 * 实现:
 *  1. **崩溃捕获**: [Thread.setDefaultUncaughtExceptionHandler] 拦截未捕获异常
 *  2. **崩溃日志**: 写入 `filesDir/crash/crash-yyyyMMdd-HHmmss.txt`(含堆栈 + 设备信息)
 *  3. **Safe Mode 标记**: 崩溃后写 `safe_mode.flag` 文件,下次启动 [checkSafeMode] 返回 true
 *     v2.0+: 同时写 SharedPreferences(safe_mode_pending + crash_time + crash_trace),
 *     持久化崩溃时间与堆栈摘要,供 SafeModeScreen 直接展示,无需再读文件
 *  4. **Safe Mode 行为**: 启动时检测到 flag → 跳过 Koin 模块加载 / Web 服务器 / 后台任务,
 *     仅加载最小 UI 提示用户"上次崩溃,已进入安全模式,崩溃日志已保存"
 *  5. **flag 清理**: 用户确认后调 [clearSafeModeFlag] 清除 flag,下次正常启动
 *
 * 设计:
 *  - 崩溃日志文件保留最近 5 份,超过自动删最旧的
 *  - Safe Mode flag 是普通文件(非 DataStore),避免 DataStore 初始化失败时读不到
 *  - v2.0+: SP 持久化层与文件 flag 双写,SP 不依赖 Koin,可独立读取
 *  - 崩溃后不重启 App(避免崩溃循环),直接 finish 让系统回到桌面
 *
 * 限制:
 *  - 仅捕获未处理异常(OutOfMemoryError 等 Error 子类也能捕获)
 *  - ANR 不触发(由系统 ANR 机制处理)
 *  - v1.56: Compose 渲染异常通过 RuntimeExceptionHandler + logComposeException 捕获
 */
class MuseCrashHandler private constructor(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    private val previousHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    /** 安装为全局未捕获异常处理器。 */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        // v1.52: 启动时清理旧崩溃日志(保留最近 MAX_CRASH_LOGS 份),
        // 避免 crash/ 目录越积越大(之前只在崩溃时清理,正常启动不触发)。
        resultOf {
            val crashDir = File(appContext.filesDir, "crash")
            crashDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CRASH_LOGS)
                ?.forEach { it.delete() }
        }
        Logger.i(TAG, "Crash handler installed")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        Logger.e(TAG, "Uncaught exception on ${t.name}", e)
        // M9: 注意:崩溃线程同步磁盘 IO,OOM/磁盘满场景可能二次失败。
        // resultOf 包裹确保不传播,但日志可能丢失。这是 Android CrashHandler 的常见权衡
        resultOf {
            val crashText = writeCrashLog(t, e)
            // v2.0+: 同时写 SP 持久化层,带上崩溃时间和堆栈摘要,
            // SafeModeScreen 启动后可直接从 SP 读取,无需依赖文件 IO
            markSafeMode(crashText)
        }
        // 交给 previous handler(通常是系统默认,会弹"应用已停止"对话框)
        previousHandler?.uncaughtException(t, e)
    }

    /**
     * 写崩溃日志到 filesDir/crash/crash-yyyyMMdd-HHmmss.txt。
     *
     * v2.0+: 返回脱敏后的崩溃文本,供调用方写入 SharedPreferences 持久化层,
     * SafeModeScreen 可直接从 SP 读取展示,无需再读文件。
     */
    private fun writeCrashLog(t: Thread, e: Throwable): String {
        val crashDir = File(appContext.filesDir, "crash").apply { mkdirs() }
        val fmt = SimpleDateFormat(CRASH_TIME_FMT, Locale.US)
        val fileName = "crash-${fmt.format(Date())}.txt"
        val logFile = File(crashDir, fileName)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== muse crash log =====")
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${t.name} (id=${t.id})")
            pw.println()
            pw.println("----- Device Info -----")
            pw.println("Brand: ${Build.BRAND}")
            pw.println("Model: ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println()
            pw.println("----- Stack Trace -----")
            e.printStackTrace(pw)
        }
        // v1.104: 脱敏后写盘(避免 API key / Bearer token / Authorization 头泄露到崩溃日志)
        val redacted = redactSensitive(sw.toString())
        logFile.writeText(redacted)

        // 清理旧崩溃日志(保留最近 5 份)
        crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_LOGS)
            ?.forEach { it.delete() }

        return redacted
    }

    /**
     * 写 safe_mode.flag 文件 + SharedPreferences 持久化层。
     *
     * v2.0+: 在原文件 flag 基础上,同步写 SP:
     *  - safe_mode_pending = true
     *  - crash_time = 当前时间(yyyy-MM-dd HH:mm:ss 格式,供 UI 展示)
     *  - crash_trace = 崩溃堆栈摘要(截断至 [MAX_SP_TRACE_LENGTH] 字符,避免 SP 写入超大字符串)
     *
     * 双写策略:文件 flag 是历史兼容路径,SP 是新增结构化数据层。
     * SafeModeScreen 优先读 SP(结构化),回退读文件(无 SP 时旧版本升级场景)。
     */
    private fun markSafeMode(crashTrace: String = "") {
        File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
        val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
        writeSafeModeSp(appContext, now, crashTrace.take(MAX_SP_TRACE_LENGTH))
    }

    companion object {
        private const val TAG = "MuseCrashHandler"
        private const val SAFE_MODE_FLAG = "safe_mode.flag"
        private const val MAX_CRASH_LOGS = 5
        // L4-1: 崩溃日志时间戳格式串
        private const val CRASH_TIME_FMT = "yyyyMMdd-HHmmss"
        // v2.0+: SP 中崩溃时间展示格式(可读格式,供 SafeModeScreen 直接显示)
        private const val CRASH_TIME_DISPLAY_FMT = "yyyy-MM-dd HH:mm:ss"
        // v2.0+: SP 文件名 — 独立 SP 文件,避免与其他模块 SP 冲突,且不依赖 Koin
        private const val SAFE_MODE_SP = "muse_safe_mode"
        // v2.0+: SP 字段 key — 与任务规约保持一致(safe_mode_pending + crash_time + crash_trace)
        private const val SP_KEY_PENDING = "safe_mode_pending"
        private const val SP_KEY_CRASH_TIME = "crash_time"
        private const val SP_KEY_CRASH_TRACE = "crash_trace"
        // v2.0+: SP crash_trace 字段最大长度(避免 SP 写入超大字符串导致 ANR)
        private const val MAX_SP_TRACE_LENGTH = 8000

        /** 获取 Safe Mode 专用 SharedPreferences(不依赖 Koin,可在启动早期读取)。 */
        private fun safeModeSp(appContext: Context): SharedPreferences =
            appContext.getSharedPreferences(SAFE_MODE_SP, Context.MODE_PRIVATE)

        /** 写 SharedPreferences 的 Safe Mode 持久化层(含崩溃时间和堆栈摘要)。 */
        private fun writeSafeModeSp(appContext: Context, crashTime: String, crashTrace: String) {
            safeModeSp(appContext).edit()
                .putBoolean(SP_KEY_PENDING, true)
                .putString(SP_KEY_CRASH_TIME, crashTime)
                .putString(SP_KEY_CRASH_TRACE, crashTrace)
                .apply()
        }

        /**
         * v1.104: 崩溃日志脱敏 — 用正则把 API key / Bearer token / Authorization 头替换为 ***。
         *
         * 崩溃堆栈可能包含 OkHttp 请求 URL(含 apiKey 查询参数)或自定义异常 message
         * (含 Authorization 头)。这些敏感串若随 zip 邮件外发会泄露凭据。
         * 覆盖常见模式:
         *  - `apiKey=xxx` / `api_key=xxx`(URL 查询参数)
         *  - `Authorization: Bearer xxx` / `Authorization: xxx`(HTTP 头)
         *  - `Bearer xxx`(裸 token)
         *  - `sk-` / `sk-ant-` 开头的 OpenAI/Anthropic key
         */
        private val REDACT_PATTERNS = listOf(
            // URL 查询参数 apiKey=xxx / api_key=xxx
            Regex("""(?i)(api[_-]?key=)[^&\s"'<>]+"""),
            // Authorization: Bearer xxx / Authorization: xxx
            Regex("""(?i)(authorization\s*:\s*)(bearer\s+)?[^\s\r\n<>]+"""),
            // 裸 Bearer xxx
            Regex("""(?i)(bearer\s+)[A-Za-z0-9\-_.=]+"""),
            // OpenAI sk- / Anthropic sk-ant- 开头的 key(至少 20 字符)
            Regex("""\bsk-(?:ant-)?[A-Za-z0-9\-_]{20,}"""),
        )

        fun redactSensitive(text: String): String {
            var result = text
            for (p in REDACT_PATTERNS) {
                result = p.replace(result) { mr ->
                    val g1 = mr.groupValues.getOrNull(1)
                    if (g1.isNullOrEmpty()) "***" else "$g1***"
                }
            }
            return result
        }

        /** 安装全局崩溃处理器(应在 [android.app.Application.onCreate] 最早调用)。 */
        fun install(appContext: Context) {
            MuseCrashHandler(appContext).install()
        }

        /**
         * 检查是否应进入 Safe Mode(上次崩溃)。
         *
         * v2.0+: 同时检查文件 flag 和 SP safe_mode_pending,任一存在即返回 true。
         * 双检保证旧版本(只写文件)升级后仍能识别 Safe Mode 状态。
         */
        fun checkSafeMode(appContext: Context): Boolean =
            File(appContext.filesDir, SAFE_MODE_FLAG).exists() ||
                safeModeSp(appContext).getBoolean(SP_KEY_PENDING, false)

        /**
         * 清除 Safe Mode 标记(用户确认后调用,下次正常启动)。
         *
         * v2.0+: 同时清除文件 flag 和 SP 持久化层,避免任一残留导致重复进入 Safe Mode。
         */
        fun clearSafeModeFlag(appContext: Context) {
            File(appContext.filesDir, SAFE_MODE_FLAG).delete()
            safeModeSp(appContext).edit().clear().apply()
        }

        /**
         * v1.91-hotfix: 主动标记 Safe Mode(用于 startKoin 失败等非崩溃场景)。
         *
         * 当 startKoin 抛异常时,MuseCrashHandler 的 uncaughtException 不会被触发
         * (因为异常被 resultOf 捕获),需通过此方法手动标记 Safe Mode,
         * 确保下次启动进入安全模式而非重复崩溃。
         *
         * v2.0+: 同步写 SP 持久化层,记录崩溃时间和提示信息(非崩溃场景 trace 为提示文案)。
         */
        fun markSafeMode(appContext: Context) {
            File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
            val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
            // 非崩溃场景(startKoin 失败等),trace 字段写入提示文案,
            // 让 SafeModeScreen 能区分展示
            writeSafeModeSp(appContext, now, "Koin 启动失败(应用初始化阶段异常,非主线程崩溃)")
        }

        /**
         * v2.0+: 读取上次崩溃时间(yyyy-MM-dd HH:mm:ss 格式)。
         *
         * 数据来源:SharedPreferences 持久化层,崩溃时由 [markSafeMode] 写入。
         * 若 SP 中无值(旧版本升级场景),返回 null,调用方可回退到崩溃日志文件的 mtime。
         */
        fun getCrashTime(appContext: Context): String? =
            safeModeSp(appContext).getString(SP_KEY_CRASH_TIME, null)

        /**
         * v2.0+: 读取上次崩溃堆栈摘要(脱敏后的完整崩溃日志文本)。
         *
         * 数据来源:SharedPreferences 持久化层,已截断至 [MAX_SP_TRACE_LENGTH] 字符。
         * 若 SP 中无值,返回 null,调用方应回退到 [readLatestCrashLog] 读文件。
         */
        fun getCrashTrace(appContext: Context): String? =
            safeModeSp(appContext).getString(SP_KEY_CRASH_TRACE, null)

        /** 列出全部崩溃日志文件(按时间降序)。 */
        fun listCrashLogs(appContext: Context): List<File> {
            val dir = File(appContext.filesDir, "crash")
            return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        }

        /** 读取最近一份崩溃日志内容(用于 Safe Mode UI 展示)。 */
        fun readLatestCrashLog(appContext: Context): String? {
            val logs = listCrashLogs(appContext)
            return logs.firstOrNull()?.readText()
        }

        /**
         * v1.95: 把全部崩溃日志 + 设备信息打包成 zip,供邮件附件一键发送。
         *
         * zip 内含 device_info.txt(设备/版本摘要)+ 各崩溃日志文件。
         * 位于 cacheDir(已通过 FileProvider 暴露),无日志时返回 null。
         */
        fun packageCrashLogsToZip(appContext: Context): File? {
            val logs = listCrashLogs(appContext)
            val zipFile = File(appContext.cacheDir, "muse_crash_logs.zip")
            // 无论有无日志都生成 zip(至少包含设备信息),便于用户反馈
            zipFile.outputStream().use { fos ->
                java.util.zip.ZipOutputStream(fos).use { zos ->
                    val deviceInfo = buildString {
                        appendLine("===== muse crash report =====")
                        appendLine("Time: ${Date()}")
                        appendLine("Brand: ${Build.BRAND}")
                        appendLine("Model: ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                    }
                    zos.putNextEntry(java.util.zip.ZipEntry("device_info.txt"))
                    zos.write(deviceInfo.toByteArray())
                    zos.closeEntry()
                    logs.forEach { log ->
                        zos.putNextEntry(java.util.zip.ZipEntry(log.name))
                        zos.write(log.readBytes())
                        zos.closeEntry()
                    }
                }
            }
            return zipFile.takeIf { logs.isNotEmpty() }
        }

        /**
         * v1.56: 记录 Compose 渲染异常(不杀进程,仅写日志 + 标记 Safe Mode)。
         *
         * Compose 的 RuntimeExceptionHandler 不会走 Thread.setDefaultUncaughtExceptionHandler,
         * 需通过此方法手动记录。下次启动时用户可在 Safe Mode 中查看崩溃日志。
         *
         * v2.0+: 同步写 SP 持久化层(含崩溃时间和堆栈摘要),与 uncaughtException 路径对齐。
         */
        fun logComposeException(appContext: Context, throwable: Throwable) {
            Logger.e(TAG, "Compose runtime exception", throwable)
            resultOf {
                val crashDir = File(appContext.filesDir, "crash").apply { mkdirs() }
                val fmt = SimpleDateFormat(CRASH_TIME_FMT, Locale.US)
                val fileName = "crash-compose-${fmt.format(Date())}.txt"
                val logFile = File(crashDir, fileName)

                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("===== muse compose crash log =====")
                    pw.println("Time: ${Date()}")
                    pw.println("Thread: ${Thread.currentThread().name} (id=${Thread.currentThread().id})")
                    pw.println()
                    pw.println("----- Device Info -----")
                    pw.println("Brand: ${Build.BRAND}")
                    pw.println("Model: ${Build.MODEL}")
                    pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
                    pw.println()
                    pw.println("----- Stack Trace -----")
                    throwable.printStackTrace(pw)
                }
                // v1.104: 脱敏后写盘
                val redacted = redactSensitive(sw.toString())
                logFile.writeText(redacted)

                // 清理旧崩溃日志
                crashDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_CRASH_LOGS)
                    ?.forEach { it.delete() }

                // 标记 Safe Mode,下次启动提示用户
                // v2.0+: 文件 flag + SP 双写(SP 含崩溃时间和堆栈摘要)
                File(appContext.filesDir, SAFE_MODE_FLAG).writeText("1")
                val now = SimpleDateFormat(CRASH_TIME_DISPLAY_FMT, Locale.US).format(Date())
                writeSafeModeSp(appContext, now, redacted.take(MAX_SP_TRACE_LENGTH))
            }
        }
    }
}
