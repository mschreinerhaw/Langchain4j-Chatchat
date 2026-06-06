package com.chatchat.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Structured tool output with metadata
 *
 * Provides structured response from tool execution including:
 * - Success/failure status
 * - Output data
 * - Execution metadata
 * - Error information if applicable
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolOutput implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Execution status: true for success, false for failure
     */
    @Builder.Default
    private boolean success = true;

    /**
     * Output data (can be String, JSON, Map, etc.)
     */
    private Object data;

    /**
     * Human-readable message
     */
    private String message;

    /**
     * Error message if execution failed
     */
    private String errorMessage;

    /**
     * Exception class name if an error occurred
     */
    private String exceptionType;

    /**
     * Stack trace or error details
     */
    private String errorDetails;

    /**
     * Execution time in milliseconds
     */
    private Long executionTimeMs;

    /**
     * Token usage (if applicable for LLM-based tools)
     */
    @Builder.Default
    private Map<String, Integer> tokenUsage = new HashMap<>();

    /**
     * Additional metadata
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Create successful output with data
     */
    public static ToolOutput success(Object data) {
        return ToolOutput.builder()
            .success(true)
            .data(data)
            .build();
    }

    /**
     * Create successful output with data and message
     */
    public static ToolOutput success(Object data, String message) {
        return ToolOutput.builder()
            .success(true)
            .data(data)
            .message(message)
            .build();
    }

    /**
     * Create failed output with error message
     */
    public static ToolOutput failure(String errorMessage) {
        return ToolOutput.builder()
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }

    /**
     * Create failed output from exception
     */
    public static ToolOutput failure(Exception exception) {
        return ToolOutput.builder()
            .success(false)
            .errorMessage(exception.getMessage())
            .exceptionType(exception.getClass().getSimpleName())
            .errorDetails(getStackTrace(exception))
            .build();
    }

    /**
     * Get stack trace as string
     */
    private static String getStackTrace(Exception exception) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : exception.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Get data as string
     */
    public String getDataAsString() {
        return data != null ? data.toString() : null;
    }
}
