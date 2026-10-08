package com.chatchat.mcpserver.datacapability.publication;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.*;
import com.chatchat.mcpserver.license.McpLicenseService;
import com.chatchat.mcpserver.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class CapabilityMcpPublisher implements McpToolContributor {
    private final McpSyncServer server;
    private final CapabilityService capabilities;
    private final CapabilityExecutionService executions;
    private final McpToolConcurrencyManager concurrency;
    private final McpLicenseService license;
    private final ObjectMapper json;
    @Override public String contributorId() { return "data_capability_center"; }
    @Override public McpSyncServer publicationServer() { return server; }
    @Override public List<ToolPublication> contribute() {
        if (!license.allowsModule("databaseMcp")) return List.of();
        return capabilities.list(null).stream().filter(d -> d.enabled() && d.mcpPublished())
            .map(this::publication).toList();
    }
    private ToolPublication publication(CapabilityDefinition d) {
        String name = "data_query_" + d.code();
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).title(d.title())
            .description(d.description() == null || d.description().isBlank() ? d.title() : d.description())
            .inputSchema(d.inputSchema())
            .meta(Map.of("category", "data_capability", "capabilityCode", d.code(), "capabilityType", d.type().name(),
                "operationType", "read", "runtimeLevel", "readonly", "riskLevel", "low"))
            .build();
        return ToolPublication.from(McpServerFeatures.SyncToolSpecification.builder().tool(tool)
            .callHandler((exchange, request) -> concurrency.execute(name, "sql", request.arguments(), () -> {
                try {
                    CapabilityDefinition current = capabilities.get(d.code());
                    if (!license.allowsModule("databaseMcp") || !current.enabled() || !current.mcpPublished())
                        throw new IllegalArgumentException("Capability MCP tool is disabled or unpublished");
                    CapabilityExecution result = executions.execute(current, request.arguments(), false, false);
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("executionId", result.getId()); structured.put("status", result.getStatus());
                    structured.put("durationMs", result.getDurationMs());
                    structured.put("result", result.getResultJson() == null ? null : json.readTree(result.getResultJson()));
                    structured.put("error", result.getError());
                    return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(structured))
                        .structuredContent(structured).isError(!"SUCCEEDED".equals(result.getStatus())).build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage() == null ? "Capability execution failed" : ex.getMessage()).isError(true).build();
                }
            })).build());
    }
}
