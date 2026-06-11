package com.chatchat.chat.interaction.model;

/**
 * Unified interaction modes aligned with ChatChat product interaction patterns.
 */
public enum InteractionMode {
    /**
     * Creates a new InteractionMode instance.
     *
     * @param AGENT_CHAT the agent chat value
     * @param TOOL_DIRECT the tool direct value
     */
    LLM_CHAT("llm_chat"),
    /**
     * Creates a new InteractionMode instance.
     *
     * @param TOOL_DIRECT the tool direct value
     */
    AGENT_CHAT("agent_chat"),
    /**
     * Creates a new InteractionMode instance.
     */
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
        throw new IllegalArgumentException("Unsupported interaction mode: " + value);
    }
}

