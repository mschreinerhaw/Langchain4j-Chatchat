package com.chatchat.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool execution context carrying metadata about tool execution
 *
 * Provides execution tracing, monitoring, and debugging information:
 * - Execution timing
 * - User and request context
 * - Error tracking
 * - Tool call chain tracking
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionContext {

    /**
     * Unique execution ID for tracing
     */
    private String executionId;

    /**
     * Tool being executed
     */
    private String toolName;

    /**
     * User who triggered the execution
     */
    private String userId;

    /**
     * Conversation ID context
     */
    private String conversationId;

    /**
     * Request ID for distributed tracing
     */
    private String requestId;

    /**
     * Timestamp when execution started
     */
    @Builder.Default
    private long startTime = System.currentTimeMillis();

    /**
     * Timestamp when execution ended
     */
    private long endTime;

    /**
     * Execution status: pending, running, succeeded, failed
     */
    @Builder.Default
    private String status = "pending";

    /**
     * Input parameters passed to tool
     */
    private ToolInput input;

    /**
     * Output from tool execution
     */
    private ToolOutput output;

    /**
     * Chain of tool calls (for multi-step operations)
     */
    @Builder.Default
    private java.util.List<String> toolCallChain = new java.util.ArrayList<>();

    /**
     * Custom metadata
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Get execution time in milliseconds
     */
    public long getExecutionTimeMs() {
        if (endTime == 0) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }

    /**
     * Mark execution as started
     */
    public void markStarted() {
        this.status = "running";
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Mark execution as completed
     */
    public void markCompleted(ToolOutput output) {
        this.status = output.isSuccess() ? "succeeded" : "failed";
        this.output = output;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * Mark execution as failed
     */
    public void markFailed(Exception exception) {
        this.status = "failed";
        this.output = ToolOutput.failure(exception);
        this.endTime = System.currentTimeMillis();
    }

    /**
     * Add tool call to chain (for nested tool invocations)
     */
    public void addToolCall(String toolName) {
        this.toolCallChain.add(toolName);
    }

    /**
     * Get tool call chain as string
     */
    public String getToolCallChainAsString() {
        return String.join(" -> ", toolCallChain);
    }
}
