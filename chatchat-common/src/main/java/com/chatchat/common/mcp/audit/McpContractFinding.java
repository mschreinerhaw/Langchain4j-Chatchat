package com.chatchat.common.mcp.audit;

/** Actionable contract drift finding backed by a concrete protocol path. */
public record McpContractFinding(
    McpContractSeverity severity,
    String code,
    String serviceId,
    String toolName,
    String domainCode,
    McpContractSource source,
    String path,
    String message,
    String observed,
    String recoveryAction
) { }
