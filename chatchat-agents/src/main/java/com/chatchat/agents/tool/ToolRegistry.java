package com.chatchat.agents.tool;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolMetadata;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Enhanced interface for tool registry and execution
 *
 * Supports both simple and rich tool definitions with metadata,
 * parameter validation, and structured I/O. Maintains backward
 * compatibility with simple string-based tools.
 */
public interface ToolRegistry {

    /**
     * Register a tool with simple interface (backward compatible)
     */
    void registerTool(String toolName, Tool tool);

    /**
     * Register a tool with rich metadata
     */
    void registerTool(String toolName, ToolMetadata metadata, EnhancedTool tool);

    /**
     * Get a tool by name
     */
    Tool getTool(String toolName);

    /**
     * Get enhanced tool with metadata
     */
    EnhancedTool getEnhancedTool(String toolName);

    /**
     * Get tool metadata
     */
    ToolMetadata getToolMetadata(String toolName);

    /**
     * Execute a tool request (LangChain4j compatible)
     */
    String executeTool(ToolExecutionRequest request);

    /**
     * Execute enhanced tool with structured input/output
     */
    ToolOutput executeEnhancedTool(String toolName, ToolInput toolInput);

    /**
     * Get all available tools
     */
    java.util.List<Tool> getAllTools();

    /**
     * Get all enhanced tools with metadata
     */
    java.util.List<EnhancedTool> getAllEnhancedTools();

    /**
     * Check if tool exists
     */
    boolean hasTool(String toolName);

    /**
     * Get all registered tool names (simple + enhanced)
     */
    java.util.Set<String> getAllToolNames();

    /**
     * Unregister a tool
     */
    void unregisterTool(String toolName);

    /**
     * Simple tool interface (backward compatible)
     */
    interface Tool {
        /**
         * Returns the name.
         *
         * @return the name
         */
        String getName();
        /**
         * Returns the description.
         *
         * @return the description
         */
        String getDescription();
        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        String execute(String input);
    }

    /**
     * Enhanced tool interface with structured I/O and metadata support
     */
    interface EnhancedTool {
        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        ToolMetadata getMetadata();
        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        ToolOutput execute(ToolInput input);
    }
}
