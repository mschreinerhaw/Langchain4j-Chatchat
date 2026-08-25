package com.chatchat.common.mcp.service;

/** Dynamically injectable deterministic repair strategy for failed MCP result parsing. */
public interface McpResultRepairer {
    String repairerId();
    default int priority() { return 0; }
    boolean supports(McpResultRepairRequest request);
    McpResultRepairResult repair(McpResultRepairRequest request);
}
