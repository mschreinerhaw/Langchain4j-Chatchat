package com.chatchat.chat.interaction.model;

/**
 * Unified interaction modes aligned with ChatChat product interaction patterns.
 */
public enum InteractionMode {
    /** Role-based model conversation without tool planning or execution. */
    ROLE_CHAT("role_chat"),
    /** Plain model conversation without a maintained Agent configuration. */
    LLM_CHAT("llm_chat"),
    /** Maintained Agent execution with planning and optional tools. */
    AGENT_CHAT("agent_chat"),
    /** Direct invocation of one explicitly selected tool. */
    TOOL_DIRECT("tool_direct");

    private final String code;

    /**
     * Creates a new InteractionMode instance.
     *
     * @param code the code value
     */
    InteractionMode(String code) {
        this.code = code;
    }

    /**
     * Performs the code operation.
     *
     * @return the operation result
     */
    public String code() {
        return code;
    }

    public boolean isRoleConversation() {
        return this == ROLE_CHAT || this == LLM_CHAT;
    }

    /**
     * Creates the value from from.
     *
     * @param value the value value
     * @return the operation result
     */
    public static InteractionMode from(String value) {
        if (value == null || value.isBlank()) {
            return LLM_CHAT;
        }
        for (InteractionMode mode : values()) {
            if (mode.code.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        if ("tool_agent".equalsIgnoreCase(value)) {
            return AGENT_CHAT;
        }
        throw new IllegalArgumentException("Unsupported interaction mode: " + value);
    }

    /** Maintained Agents historically default to tool-agent execution when no mode is persisted. */
    public static InteractionMode fromAgentConfiguration(String value) {
        return value == null || value.isBlank() ? AGENT_CHAT : from(value);
    }
}
