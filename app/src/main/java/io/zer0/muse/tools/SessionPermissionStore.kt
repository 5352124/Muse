package io.zer0.muse.tools

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话级权限模式持久化。
 *
 * 按 sessionId 存储当前会话的工具权限模式,切换会话时自动恢复。
 */
class SessionPermissionStore(private val context: Context) {

    private val Context.permissionDataStore by preferencesDataStore(name = "muse_session_permission")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val PERMISSION_MODES_KEY = stringPreferencesKey("session_permission_modes")
    }

    @Serializable
    data class StoredModes(
        val modes: Map<String, SessionPermissionMode> = emptyMap(),
    )

    /** 全部会话权限模式流。 */
    val modesFlow: Flow<Map<String, SessionPermissionMode>> =
        context.permissionDataStore.data.map { prefs ->
            parseModes(prefs).modes
        }

    /** 读取指定会话的权限模式,未设置时返回默认 [SessionPermissionMode.ASK]。 */
    suspend fun getMode(sessionId: String): SessionPermissionMode {
        return modesFlow.first()[sessionId] ?: SessionPermissionMode.ASK
    }

    /** 设置指定会话的权限模式。 */
    suspend fun setMode(sessionId: String, mode: SessionPermissionMode) {
        context.permissionDataStore.edit { prefs ->
            val current = parseModes(prefs).modes.toMutableMap()
            current[sessionId] = mode
            prefs[PERMISSION_MODES_KEY] = json.encodeToString(StoredModes(current))
        }
    }

    /** 清除指定会话的权限设置(会话删除时调用)。 */
    suspend fun clearMode(sessionId: String) {
        context.permissionDataStore.edit { prefs ->
            val current = parseModes(prefs).modes.toMutableMap()
            current.remove(sessionId)
            prefs[PERMISSION_MODES_KEY] = json.encodeToString(StoredModes(current))
        }
    }

    private fun parseModes(prefs: Preferences): StoredModes {
        val raw = prefs[PERMISSION_MODES_KEY] ?: return StoredModes()
        return try {
            json.decodeFromString<StoredModes>(raw)
        } catch (_: Exception) {
            StoredModes()
        }
    }
}
