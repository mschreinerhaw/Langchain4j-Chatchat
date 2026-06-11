package com.chatchat.agents.runtime;

import java.util.Locale;

public enum ToolRuntimeAction {
    /**
     * Creates a new ToolRuntimeAction instance.
     *
     * @param ASK_BEFORE_EXECUTE the ask before execute value
     * @param DENY the deny value
     */
    AUTO_EXECUTE("auto_execute"),
    /**
     * Creates a new ToolRuntimeAction instance.
     *
     * @param DENY the deny value
     */
    ASK_BEFORE_EXECUTE("ask_before_execute"),
    /**
     * Creates a new ToolRuntimeAction instance.
     */
    DENY("deny");

    private final String code;

    /**
     * Creates a new ToolRuntimeAction instance.
     *
     * @param code the code value
     */
    ToolRuntimeAction(String code) {
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
     * @param fallback the fallback value
     * @return the operation result
     */
    public static ToolRuntimeAction from(Object value, ToolRuntimeAction fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return fallback;
        }
        for (ToolRuntimeAction action : values()) {
            if (action.code.equals(text) || action.name().equalsIgnoreCase(text)) {
                return action;
            }
        }
        return fallback;
    }
}
