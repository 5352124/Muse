package io.zer0.muse.data.mcp

import android.content.Context
import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * MCP subsystem manager (RikkaHub McpManager.kt port).
 *
 * Coordinates MCP config persistence, session management, tool discovery,
 * and tool injection into the Muse ToolRegistry.
 *
 * Usage:
 * 1. Initialize with context
 * 2. Call [start] to begin observing config changes
 * 3. MCP tools are automatically injected into [ToolRegistry]
 */
class McpManager(private val context: Context) {

    private val configStore = McpConfigStore(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionManager = McpSessionManager(
        onToolsUpdated = { serverId, tools ->
            scope.launch { configStore.updateServerTools(serverId, tools) }
        },
        onStatusChanged = { serverId, status ->
            _connectionStatuses.update { current ->
                current.toMutableMap().apply { put(serverId, status) }
            }
        },
    )

    private val _connectionStatuses = MutableStateFlow<Map<Uuid, McpConnectionStatus>>(emptyMap())
    val connectionStatuses: StateFlow<Map<Uuid, McpConnectionStatus>> = _connectionStatuses.asStateFlow()

    /** Start observing config changes and managing sessions. */
    fun start() {
        scope.launch {
            configStore.serversFlow
                .distinctUntilChanged()
                .collect { servers ->
                    sessionManager.reconcile(servers)
                }
        }
    }

    /** Get all enabled MCP tools across all connected servers. */
    fun getAllAvailableTools(): List<McpToolInfo> {
        return sessionManager.getAllAvailableTools().map { (serverId, serverName, tool) ->
            McpToolInfo(
                serverId = serverId,
                serverName = serverName,
                tool = tool,
            )
        }
    }

    /** Call an MCP tool. Returns the text result from the tool execution. */
    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): String {
        return sessionManager.callTool(serverId, toolName, args)
    }

    /** Add or update an MCP server. */
    suspend fun upsertServer(config: McpServerConfig) {
        configStore.upsertServer(config)
    }

    /** Remove an MCP server. */
    suspend fun removeServer(id: Uuid) {
        configStore.removeServer(id)
    }

    /** Toggle server enable/disable. */
    suspend fun toggleServer(config: McpServerConfig, enabled: Boolean) {
        configStore.upsertServer(
            config.clone(commonOptions = config.commonOptions.copy(enable = enabled))
        )
    }

    /** Import servers from JSON. */
    suspend fun importFromJson(json: String): Int = configStore.importFromJson(json)

    /** Export servers as JSON. */
    suspend fun exportAsJson(): String = configStore.exportAsJson()

    /**
     * Inject discovered MCP tools into the Muse ToolRegistry.
     * Call this after MCP servers are connected and tools are discovered.
     */
    fun injectToolsIntoRegistry(toolRegistry: ToolRegistry) {
        val mcpTools = getAllAvailableTools()
        for (info in mcpTools) {
            val tool = info.tool
            if (!tool.enable) continue

            val params = mutableMapOf<String, String>()
            tool.inputSchema?.let { schema ->
                val properties = schema["properties"]
                if (properties is JsonObject) {
                    for ((key, value) in properties) {
                        val desc = if (value is JsonObject) {
                            (value["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: key
                        } else key
                        params[key] = desc
                    }
                }
            }

            val required = mutableSetOf<String>()
            tool.inputSchema?.let { schema ->
                val reqArr = schema["required"]
                if (reqArr is kotlinx.serialization.json.JsonArray) {
                    for (item in reqArr) {
                        if (item is kotlinx.serialization.json.JsonPrimitive) {
                            required.add(item.content)
                        }
                    }
                }
            }

            toolRegistry.register(
                ToolRegistry.ToolDef(
                    name = "mcp_${info.serverName}_${tool.name}",
                    description = tool.description ?: tool.name,
                    parameters = params,
                    required = required,
                    category = "mcp",
                ),
            ) { args: Map<String, String> ->
                val argJson = kotlinx.serialization.json.buildJsonObject {
                    for ((key, value) in args) {
                        put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    }
                }
                kotlinx.coroutines.runBlocking {
                    callTool(info.serverId, tool.name, argJson)
                }
            }
        }
    }

    /** Shutdown all MCP sessions. */
    suspend fun shutdown() {
        sessionManager.shutdownAll()
    }
}

/**
 * MCP tool info combining server context with tool definition.
 */
data class McpToolInfo(
    val serverId: Uuid,
    val serverName: String,
    val tool: McpTool,
)
