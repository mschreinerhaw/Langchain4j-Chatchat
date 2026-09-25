package com.chatchat.common.runtime.agent;

import java.util.Locale;

/** Orthogonal to agent origin and to INLINE/DURABLE workflow placement. */
public enum AgentExecutionMode {
    DOMAIN_INFERENCE,
    AGENTIC_EXECUTION;

    public static AgentExecutionMode parse(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return DOMAIN_INFERENCE;
        try { return valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unsupported agent execution mode: " + value); }
    }
}
