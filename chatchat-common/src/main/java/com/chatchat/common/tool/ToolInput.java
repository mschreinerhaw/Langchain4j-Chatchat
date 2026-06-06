package com.chatchat.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Structured tool input with validation context
 *
 * Replaces simple string input with structured format supporting:
 * - Typed parameters
 * - Validation context
 * - Execution metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInput implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Raw input string (for backward compatibility)
     */
    private String rawInput;

    /**
     * Structured input parameters
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * Request ID for tracing
     */
    private String requestId;

    /**
     * User ID who initiated this tool call
     */
    private String userId;

    /**
     * Conversation ID context
     */
    private String conversationId;

    /**
     * Additional execution context
     */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();

    /**
     * Get parameter value by name
     */
    public Object getParameter(String name) {
        return parameters.get(name);
    }

    /**
     * Get parameter value as string with default
     */
    public String getParameterAsString(String name, String defaultValue) {
        Object value = parameters.get(name);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Get parameter value as number
     */
    public Number getParameterAsNumber(String name) {
        Object value = parameters.get(name);
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Parameter '" + name + "' is not a valid number: " + value);
            }
        }
        return null;
    }

    /**
     * Get parameter value as boolean
     */
    public boolean getParameterAsBoolean(String name, boolean defaultValue) {
        Object value = parameters.get(name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
}
