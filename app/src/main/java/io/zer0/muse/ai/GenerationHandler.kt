package io.zer0.muse.ai

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

/**
 * Generation handler with multi-step tool loop (RikkaHub GenerationHandler.kt port).
 *
 * Manages the agentic loop:
 * 1. Call LLM with current messages + tools
 * 2. Parse tool calls from response
 * 3. Check approval (auto/pending/denied)
 * 4. Execute approved tools, report denied
 * 5. Merge results back into conversation
 * 6. Continue until no more tool calls or max steps reached
 *
 * Tool outputs exceeding [MAX_TOOL_OUTPUT_CHARS] are truncated with a summary.
 */
class GenerationHandler(
    private val chatService: ChatService,
    private val toolRegistry: ToolRegistry,
    private val toolConfigStore: ToolConfigStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Generation result from a single step.
     */
    data class StepResult(
        val assistantMessage: UIMessage,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolResults: List<ToolResult> = emptyList(),
        val isFinal: Boolean = false,
    )

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val output: String,
        val isSuccess: Boolean,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
    )

    /**
     * Execute the multi-step generation loop.
     *
     * @param messages Initial conversation messages
     * @param model The model to use
     * @param providerConfig Provider configuration
     * @param tools Available tool definitions for the LLM
     * @param maxSteps Maximum number of agentic steps (default 32)
     * @param temperature Sampling temperature
     * @param maxTokens Max output tokens
     * @param reasoningLevel Reasoning level for thinking models
     * @param onStepResult Called for each step result (for UI updates)
     * @param approvalCallback Called when a tool needs user approval; return Approved/Denied
     * @return Final list of all messages (original + generated)
     */
    suspend fun generate(
        messages: List<UIMessage>,
        model: Model?,
        providerConfig: ProviderConfig? = null,
        tools: List<ToolDefinition>? = null,
        maxSteps: Int = 32,
        temperature: Float? = null,
        maxTokens: Int? = null,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        onStepResult: (StepResult) -> Unit = {},
        approvalCallback: (suspend (toolName: String, argsPreview: String) -> ToolApprovalState)? = null,
    ): List<UIMessage> {
        val conversationHistory = messages.toMutableList()
        val enhancedModel = model?.let { ModelRegistry.enhanceModel(it) }

        // Check if tools should be sent based on model capabilities
        val effectiveTools = if (enhancedModel?.supportsToolCalling() == true) tools else null

        for (step in 0 until maxSteps) {
            Logger.d(TAG, "Step #$step (model=${enhancedModel?.id})")

            // Collect streaming response
            val builder = StringBuilder()
            val toolCallAccumulator = mutableMapOf<Int, Triple<String?, String?, StringBuilder>>()
            var streamError: String? = null

            try {
                val flow = chatService.streamChat(
                    messages = conversationHistory,
                    model = enhancedModel,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    tools = effectiveTools,
                    reasoningLevel = reasoningLevel,
                    providerConfig = providerConfig,
                )
                collectStream(flow, builder, toolCallAccumulator) { error ->
                    streamError = error
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Stream error at step $step: ${e.message}")
                streamError = e.message ?: "Unknown error"
            }

            if (streamError != null) {
                val errorMsg = UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Error: $streamError",
                )
                conversationHistory.add(errorMsg)
                onStepResult(StepResult(assistantMessage = errorMsg, isFinal = true))
                break
            }

            val assistantContent = builder.toString()
            val toolCalls = toolCallAccumulator.values.mapNotNull { (id, name, args) ->
                if (id != null && name != null) {
                    ToolCall(id = id, name = name, arguments = args.toString())
                } else null
            }

            val assistantMessage = UIMessage(
                role = MessageRole.ASSISTANT,
                content = assistantContent,
                toolCalls = if (toolCalls.isNotEmpty()) toolCalls else null,
            )
            conversationHistory.add(assistantMessage)

            // No tool calls → done
            if (toolCalls.isEmpty()) {
                onStepResult(StepResult(assistantMessage = assistantMessage, isFinal = true))
                break
            }

            // Process tool calls with approval
            val toolResults = mutableListOf<ToolResult>()
            for (tc in toolCalls) {
                // Check approval
                val approvalState = resolveApproval(tc, approvalCallback)

                when (approvalState) {
                    is ToolApprovalState.Denied -> {
                        toolResults.add(
                            ToolResult(
                                toolCallId = tc.id,
                                toolName = tc.name,
                                output = """{"error": "Tool denied by user", "reason": "${approvalState.reason}"}""",
                                isSuccess = false,
                                approvalState = approvalState,
                            )
                        )
                    }
                    is ToolApprovalState.Auto, is ToolApprovalState.Approved -> {
                        val result = executeTool(tc)
                        toolResults.add(result)
                    }
                    else -> {
                        toolResults.add(
                            ToolResult(
                                toolCallId = tc.id,
                                toolName = tc.name,
                                output = """{"error": "Unexpected approval state"}""",
                                isSuccess = false,
                            )
                        )
                    }
                }
            }

            // Build tool results message
            val toolResultContent = toolResults.joinToString("\n") { result ->
                "[${result.toolName}]: ${truncateOutput(result.output)}"
            }
            // Add a TOOL role message with the combined results
            val toolMessage = UIMessage(
                role = MessageRole.TOOL,
                content = toolResultContent,
            )
            conversationHistory.add(toolMessage)

            onStepResult(
                StepResult(
                    assistantMessage = assistantMessage,
                    toolCalls = toolCalls,
                    toolResults = toolResults,
                    isFinal = false,
                )
            )
        }

        return conversationHistory
    }

    private suspend fun resolveApproval(
        tc: ToolCall,
        approvalCallback: (suspend (String, String) -> ToolApprovalState)?,
    ): ToolApprovalState {
        // Check stored policy first
        val storedState = toolConfigStore.resolveApprovalState(tc.name)
        return when (storedState) {
            is ToolApprovalState.Auto -> ToolApprovalState.Auto
            is ToolApprovalState.Denied -> storedState
            is ToolApprovalState.Pending -> {
                // Need user approval
                val argsPreview = tc.arguments.take(200)
                approvalCallback?.invoke(tc.name, argsPreview)
                    ?: ToolApprovalState.Auto // fallback to auto if no callback
            }
            else -> storedState
        }
    }

    private suspend fun executeTool(tc: ToolCall): ToolResult {
        return try {
            val result = toolRegistry.executeFromJson(tc.name, tc.arguments)
            ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = result,
                isSuccess = true,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Tool execution failed: ${tc.name}: ${e.message}")
            ToolResult(
                toolCallId = tc.id,
                toolName = tc.name,
                output = """{"error": "${e.message ?: "Unknown error"}"}""",
                isSuccess = false,
            )
        }
    }

    private suspend fun collectStream(
        flow: Flow<ChatStreamEvent>,
        builder: StringBuilder,
        toolCallAccumulator: MutableMap<Int, Triple<String?, String?, StringBuilder>>,
        onError: (String) -> Unit,
    ) {
        flow.collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                is ChatStreamEvent.ToolCallDelta -> {
                    val idx = event.index
                    val existing = toolCallAccumulator[idx]
                    if (existing != null) {
                        // accumulate arguments delta
                        event.argumentsDelta?.let { existing.third.append(it) }
                        // update id/name if this chunk carries them (first chunk)
                        val newId = event.id ?: existing.first
                        val newName = event.name ?: existing.second
                        toolCallAccumulator[idx] = Triple(newId, newName, existing.third)
                    } else {
                        // first chunk for this index
                        toolCallAccumulator[idx] = Triple(event.id, event.name, StringBuilder(event.argumentsDelta ?: ""))
                    }
                }
                is ChatStreamEvent.ReasoningDelta -> { /* thinking tokens, ignore for tool loop */ }
                is ChatStreamEvent.ImageDelta -> { /* image output, not relevant to tool loop */ }
                is ChatStreamEvent.Done -> { /* stream complete */ }
                is ChatStreamEvent.Error -> onError(event.message)
            }
        }
    }

    private fun truncateOutput(output: String): String {
        return if (output.length > MAX_TOOL_OUTPUT_CHARS) {
            output.take(TOOL_OUTPUT_PREVIEW_CHARS) +
                "\n... [truncated, ${output.length - TOOL_OUTPUT_PREVIEW_CHARS} chars omitted]"
        } else {
            output
        }
    }
}
