package io.zer0.muse.tools

import io.zer0.muse.data.experience.ExperienceRepository
import kotlinx.coroutines.runBlocking

/**
 * recall_experience tool (openhanako experience.ts port).
 *
 * Progressive disclosure: no params returns category index,
 * with category returns specific experiences.
 */
object RecallExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "recall_experience",
        description = "Browse the experience library. Without parameters, returns an overview of all categories. " +
            "With a category name, returns specific experiences in that category. " +
            "Check this tool before starting concrete tasks (write code, research, analyze problems). " +
            "Not needed for casual chat or Q&A.",
        parameters = mapOf(
            "category" to "Optional. Category name. Omit to get an overview of all categories.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun execute(args: Map<String, String>, repo: ExperienceRepository): String {
        val category = args["category"]?.trim()
        return runBlocking {
            val all = repo.getAll()
            if (all.isEmpty()) return@runBlocking "Experience library is empty. No experiences recorded yet."

            if (category.isNullOrEmpty()) {
                // Return index: group by category
                val grouped = all.groupBy { it.category }
                buildString {
                    appendLine("Experience Library (${all.size} entries, ${grouped.size} categories):")
                    appendLine()
                    for ((cat, entries) in grouped) {
                        val snippets = entries.take(3).map { it.content.take(30) }
                        appendLine("# $cat (${entries.size} entries)")
                        appendLine(snippets.joinToString("; "))
                        appendLine()
                    }
                }.trimEnd()
            } else {
                val matches = all.filter {
                    it.category.equals(category, ignoreCase = true) ||
                        it.category.contains(category, ignoreCase = true)
                }
                if (matches.isEmpty()) {
                    "No experiences found for category '$category'."
                } else {
                    buildString {
                        appendLine("# ${matches.first().category} (${matches.size} entries)")
                        appendLine()
                        for ((i, e) in matches.withIndex()) {
                            appendLine("${i + 1}. ${e.content}")
                        }
                    }.trimEnd()
                }
            }
        }
    }
}
