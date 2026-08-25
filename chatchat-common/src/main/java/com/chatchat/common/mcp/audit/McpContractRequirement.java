package com.chatchat.common.mcp.audit;

/** One machine-checkable requirement declared by a domain service contract. */
public record McpContractRequirement(
    McpContractSource source,
    String path,
    String code,
    McpContractSeverity severity,
    String description,
    String recoveryAction
) {
    public McpContractRequirement {
        if (source == null) throw new IllegalArgumentException("source is required");
        path = required(path, "path");
        code = required(code, "code");
        severity = severity == null ? McpContractSeverity.ERROR : severity;
        description = required(description, "description");
        recoveryAction = required(recoveryAction, "recoveryAction");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
