package io.zer0.muse.tools

import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.notification.MuseNotificationManager
import android.content.Context

/**
 * HanaAgent tools registrar (openhanako tool system port).
 *
 * Registers all HanaAgent-ported tools into the existing ToolRegistry:
 *  - pin_memory / unpin_memory (Phase 1C)
 *  - recall_experience / record_experience (Phase 3B)
 *  - search_memory (Phase 1 v6)
 *  - todo_write (Phase 4A)
 *  - show_card (Phase 4B)
 *  - notify (Phase 4C)
 *  - current_status (Phase 4D)
 *  - subagent_task (Phase 5A)
 */
class HanaAgentToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val pinnedMemoryStore: PinnedMemoryStore,
    private val experienceRepository: ExperienceRepository,
    private val factStore: FactStore,
    private val notificationManager: MuseNotificationManager,
    private val context: Context,
) {
    init { registerAll() }

    fun registerAll() {
        // Phase 1C: Pinned memories
        toolRegistry.register(PinMemoryTool.toolDef()) { args ->
            PinMemoryTool.execute(args, pinnedMemoryStore)
        }
        toolRegistry.register(UnpinMemoryTool.toolDef()) { args ->
            UnpinMemoryTool.execute(args, pinnedMemoryStore)
        }

        // Phase 3B: Experience tools
        toolRegistry.register(RecallExperienceTool.toolDef()) { args ->
            RecallExperienceTool.execute(args, experienceRepository)
        }
        toolRegistry.register(RecordExperienceTool.toolDef()) { args ->
            RecordExperienceTool.execute(args, experienceRepository)
        }

        // Phase 1 v6: Long-term memory search
        toolRegistry.register(SearchMemoryTool.toolDef()) { args ->
            SearchMemoryTool.execute(args, factStore)
        }

        // Phase 4A: Todo
        toolRegistry.register(TodoTool.toolDef()) { args ->
            TodoTool.execute(args)
        }

        // Phase 4B: Show card
        toolRegistry.register(ShowCardTool.toolDef()) { args ->
            ShowCardTool.execute(args)
        }

        // Phase 4C: Notify
        toolRegistry.register(NotifyTool.toolDef()) { args ->
            NotifyTool.execute(args, notificationManager)
        }

        // Phase 4D: Current status
        toolRegistry.register(CurrentStatusTool.toolDef()) { args ->
            CurrentStatusTool.execute(args, context)
        }

    }
}
