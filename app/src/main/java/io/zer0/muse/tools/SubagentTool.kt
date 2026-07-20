package io.zer0.muse.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * subagent_task tool (openhanako subagent-tool.ts port).
 *
 * Non-blocking background task execution with progress reporting.
 * The existing delegate_agent in SkillExecutor handles synchronous delegation;
 * this tool adds async/non-blocking execution with progress tracking.
 */
object SubagentTool {

    data class TaskState(
        val taskId: String,
        val agentId: String,
        val description: String,
        val status: String, // pending / running / completed / failed / cancelled
        val progress: String = "",
        val result: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val tasks = ConcurrentHashMap<String, TaskState>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _taskListFlow = MutableStateFlow<List<TaskState>>(emptyList())
    val taskListFlow: StateFlow<List<TaskState>> = _taskListFlow

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_task",
        description = "Launch a non-blocking background task for an assistant. " +
            "Returns a taskId immediately; use action=status to check progress. " +
            "Supports: launch (start task), status (check progress), cancel (abort task), list (all tasks).",
        parameters = mapOf(
            "action" to "Required. One of: launch / status / cancel / list.",
            "agent_id" to "Required for launch. The assistant id to run the task.",
            "task" to "Required for launch. Task description / prompt.",
            "task_id" to "Required for status/cancel. The task id returned by launch.",
        ),
        required = setOf("action"),
        category = "built-in",
    )

    fun execute(
        args: Map<String, String>,
        scope: CoroutineScope,
        onRunTask: suspend (agentId: String, task: String, onProgress: (String) -> Unit) -> String,
    ): String {
        val action = args["action"]?.trim() ?: return "Error: action parameter is required."

        return when (action) {
            "launch" -> {
                val agentId = args["agent_id"]?.trim()
                    ?: return "Error: agent_id is required for launch."
                val task = args["task"]?.trim()
                    ?: return "Error: task is required for launch."
                val taskId = UUID.randomUUID().toString().take(12)
                val state = TaskState(taskId, agentId, task, "pending")
                tasks[taskId] = state
                updateFlow()

                val job = scope.launch(Dispatchers.IO) {
                    tasks[taskId] = state.copy(status = "running", progress = "Starting...")
                    updateFlow()
                    try {
                        val result = onRunTask(agentId, task) { progress ->
                            // v1.131: 任务可能被并发 cancel/clear,用安全更新替代 !! — 防止 NPE。
                            tasks[taskId]?.let { current ->
                                tasks[taskId] = current.copy(progress = progress)
                            }
                            updateFlow()
                        }
                        tasks[taskId]?.let { current ->
                            tasks[taskId] = current.copy(
                                status = "completed",
                                progress = "Done",
                                result = result,
                            )
                        }
                    } catch (e: Exception) {
                        tasks[taskId]?.let { current ->
                            tasks[taskId] = current.copy(
                                status = "failed",
                                progress = "Error: ${e.message}",
                            )
                        }
                    }
                    updateFlow()
                }
                jobs[taskId] = job
                "Task launched: taskId=$taskId, agent=$agentId. Use action=status&task_id=$taskId to check progress."
            }

            "status" -> {
                val taskId = args["task_id"]?.trim()
                    ?: return "Error: task_id is required for status."
                val state = tasks[taskId] ?: return "Error: task '$taskId' not found."
                buildString {
                    append("Task ${state.taskId}: status=${state.status}, progress=${state.progress}")
                    if (state.result != null) append("\nResult: ${state.result.take(200)}")
                }
            }

            "cancel" -> {
                val taskId = args["task_id"]?.trim()
                    ?: return "Error: task_id is required for cancel."
                val job = jobs[taskId] ?: return "Error: task '$taskId' not found."
                job.cancel()
                // v1.131: 任务可能已被清理,用安全更新替代 !!。
                tasks[taskId]?.let { current ->
                    tasks[taskId] = current.copy(status = "cancelled")
                }
                updateFlow()
                "Task $taskId cancelled."
            }

            "list" -> {
                val all = tasks.values.sortedByDescending { it.createdAt }
                if (all.isEmpty()) return "No background tasks."
                buildString {
                    appendLine("Background tasks (${all.size}):")
                    for (t in all.take(20)) {
                        appendLine("- ${t.taskId}: ${t.status} | ${t.description.take(40)} | ${t.progress}")
                    }
                }.trimEnd()
            }

            else -> "Error: action must be launch/status/cancel/list."
        }
    }

    private fun updateFlow() {
        _taskListFlow.value = tasks.values.toList()
    }

    fun getTask(taskId: String): TaskState? = tasks[taskId]
}
