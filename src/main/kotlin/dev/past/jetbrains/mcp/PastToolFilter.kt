package dev.past.jetbrains.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolInvocationMode
import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Makes `past_recall` a direct tool: one listed among the tools an agent sees.
 *
 * Every tool on the IDE's MCP server starts router-only: callable through `execute_tool` by a model
 * that already knows its name, and named nowhere a model reads. Junie therefore never finds a
 * router-only tool by itself. This runs after the other filters and makes past.dev's tool direct. It
 * changes only that: a tool the person switched off stays off.
 */
class PastToolFilter : McpToolFilterProvider {
    override fun applyFilters(
        context: McpToolFilterProvider.McpToolFilterContext,
        clientInfo: Implementation?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ) {
        context.updateState(routerOnly = false) { it.descriptor.name == PastToolset.RECALL }
    }

    override fun getUpdates(
        clientInfo: Implementation?,
        scope: CoroutineScope,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ): Flow<Unit> = emptyFlow()
}
