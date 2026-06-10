package com.chatchat.chat.interaction.model;

/**
 * Unified interaction modes aligned with ChatChat product interaction patterns.
 */
public enum InteractionMode {
    LLM_CHAT("llm_chat"),
    AGENT_CHAT("agent_chat"),
    TOOL_DIRECT("tool_direct");

    private final String code;

    InteractionMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

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

