package io.zer0.muse.data.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * MCP server configuration (RikkaHub McpConfig.kt port).
 *
 * Supports SSE and Streamable HTTP transports.
 * Each server has common options (enable, name, headers, tools) and transport-specific URL.
 */
@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
)

/**
 * MCP tool definition discovered from a remote MCP server.
 */
@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: JsonObject? = null,
    val needsApproval: Boolean = false,
)

/**
 * MCP server config sealed hierarchy.
 * Supports SSE and Streamable HTTP transports.
 */
@Serializable
sealed class McpServerConfig {
    abstract val id: Uuid
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: Uuid = this.id,
        commonOptions: McpCommonOptions = this.commonOptions,
    ): McpServerConfig

    val serverUrl: String
        get() = when (this) {
            is SseTransportServer -> url
            is StreamableHTTPServer -> url
        }

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig =
            copy(id = id, commonOptions = commonOptions)
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig =
            copy(id = id, commonOptions = commonOptions)
    }
}

/**
 * MCP connection status for UI display.
 */
sealed class McpConnectionStatus {
    data object Idle : McpConnectionStatus()
    data object Connecting : McpConnectionStatus()
    data object Connected : McpConnectionStatus()
    data object NeedsAuthorization : McpConnectionStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpConnectionStatus()
    data class Error(val message: String) : McpConnectionStatus()
}
