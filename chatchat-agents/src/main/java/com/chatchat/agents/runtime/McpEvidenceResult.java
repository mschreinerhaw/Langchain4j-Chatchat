package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.protocol.RuntimeEvidenceEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical evidence-layer result for every MCP/tool runtime response. */
public record McpEvidenceResult(
    String schemaVersion,
    String evidenceId,
    String toolName,
    String outcome,
    GovernanceIsolationScope isolationScope,
    Object payload,
    Map<String, Object> governance
) implements RuntimeEvidenceEnvelope {

    public static final String SCHEMA_VERSION =
        com.chatchat.agents.runtime.protocol.RuntimeEvidenceProtocol.EVIDENCE_SCHEMA_VERSION;

    public McpEvidenceResult {
        schemaVersion = SCHEMA_VERSION;
        evidenceId = text(evidenceId, "mcp-evidence");
        toolName = text(toolName, "unknown-tool");
        outcome = text(outcome, "unknown");
        isolationScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope;
        governance = governance == null ? Map.of() : Map.copyOf(governance);
    }

    public Map<String, Object> descriptor() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", schemaVersion);
        values.put("evidenceId", evidenceId);
        values.put("toolName", toolName);
        values.put("outcome", outcome);
        values.put("isolationScope", isolationScope.toMap());
        values.put("governance", governance);
        return Map.copyOf(values);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
