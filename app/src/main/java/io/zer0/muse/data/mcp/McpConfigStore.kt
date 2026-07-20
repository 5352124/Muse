package io.zer0.muse.data.mcp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val Context.mcpDataStore by preferencesDataStore(name = "muse_mcp_servers")

/**
 * MCP server configuration persistence (RikkaHub SettingsStore MCP port).
 *
 * Stores the list of MCP server configs in DataStore as JSON.
 * Provides Flow for reactive UI updates.
 */
class McpConfigStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    companion object {
        private val MCP_SERVERS_KEY = stringPreferencesKey("mcp_servers")
    }

    /** Flow of all MCP server configurations. */
    val serversFlow: Flow<List<McpServerConfig>> =
        context.mcpDataStore.data.map { prefs ->
            val raw = prefs[MCP_SERVERS_KEY] ?: return@map emptyList()
            try {
                json.decodeFromString<List<McpServerConfig>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** Add or update a server config. */
    suspend fun upsertServer(config: McpServerConfig) {
        context.mcpDataStore.edit { prefs ->
            val current = parseServers(prefs[MCP_SERVERS_KEY])
            val updated = current.filter { it.id != config.id } + config
            prefs[MCP_SERVERS_KEY] = json.encodeToString(updated)
        }
    }

    /** Remove a server by ID. */
    suspend fun removeServer(id: Uuid) {
        context.mcpDataStore.edit { prefs ->
            val current = parseServers(prefs[MCP_SERVERS_KEY])
            val updated = current.filter { it.id != id }
            prefs[MCP_SERVERS_KEY] = json.encodeToString(updated)
        }
    }

    /** Update tools for a specific server (after tool discovery). */
    suspend fun updateServerTools(serverId: Uuid, tools: List<McpTool>) {
        context.mcpDataStore.edit { prefs ->
            val current = parseServers(prefs[MCP_SERVERS_KEY])
            val updated = current.map { config ->
                if (config.id != serverId) return@map config
                config.clone(
                    commonOptions = config.commonOptions.copy(tools = tools)
                )
            }
            prefs[MCP_SERVERS_KEY] = json.encodeToString(updated)
        }
    }

    /** Import servers from JSON string. */
    suspend fun importFromJson(jsonString: String): Int {
        return try {
            val imported = json.decodeFromString<List<McpServerConfig>>(jsonString)
            context.mcpDataStore.edit { prefs ->
                val current = parseServers(prefs[MCP_SERVERS_KEY])
                val existingIds = current.map { it.id }.toSet()
                val newServers = imported.filter { it.id !in existingIds }
                val merged = current + newServers
                prefs[MCP_SERVERS_KEY] = json.encodeToString(merged)
            }
            imported.size
        } catch (_: Exception) {
            0
        }
    }

    /** Export all servers as JSON string. */
    suspend fun exportAsJson(): String {
        return context.mcpDataStore.data.map { it[MCP_SERVERS_KEY] }.first() ?: "[]"
    }

    private fun parseServers(raw: String?): List<McpServerConfig> {
        if (raw == null) return emptyList()
        return try {
            json.decodeFromString<List<McpServerConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
