package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpServiceResult;

import java.util.Set;

/** Runtime contract audit query, optionally carrying an execution result for evidence checks. */
public record McpContractAuditRequest(
    String schemaVersion,
    String serviceId,
    String toolName,
    String templateId,
    Set<String> requiredArguments,
    McpServiceResult executionResult
) {
    public static final String SCHEMA_VERSION = "mcp_contract_audit_request.v1";

    public McpContractAuditRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported MCP audit request schema: " + schemaVersion);
        serviceId = clean(serviceId);
        toolName = clean(toolName);
        templateId = clean(templateId);
        requiredArguments = requiredArguments == null ? Set.of() : Set.copyOf(requiredArguments);
    }

    public McpContractAuditRequest(String serviceId, String toolName, String templateId,
                                   Set<String> requiredArguments, McpServiceResult executionResult) {
        this(null, serviceId, toolName, templateId, requiredArguments, executionResult);
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
