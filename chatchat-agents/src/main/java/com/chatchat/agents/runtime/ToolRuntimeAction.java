package com.chatchat.agents.runtime;

import java.util.Locale;

public enum ToolRuntimeAction {
    AUTO_EXECUTE("auto_execute"),
    ASK_BEFORE_EXECUTE("ask_before_execute"),
    DENY("deny");

    private final String code;

    ToolRuntimeAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

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
