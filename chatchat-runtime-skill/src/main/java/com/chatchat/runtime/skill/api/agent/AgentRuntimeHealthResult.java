package com.chatchat.runtime.skill.api;

import java.util.Map;

/** Health-check result returned without selecting a fallback engine. */
public record AgentRuntimeHealthResult(String status, Map<String, Object> details) {
    public AgentRuntimeHealthResult {
        status = status == null || status.isBlank() ? "UNKNOWN" : status.trim();
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
