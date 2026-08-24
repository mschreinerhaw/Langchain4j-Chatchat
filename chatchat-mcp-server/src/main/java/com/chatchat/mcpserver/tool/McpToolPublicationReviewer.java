package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.McpToolNamePolicy;
import com.chatchat.mcpserver.mcp.McpInvocationArguments;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central naming gate for tools added to a running MCP server. */
public final class McpToolPublicationReviewer {

    private McpToolPublicationReviewer() {
    }

    public static void addReviewedTool(McpSyncServer server,
                                       McpServerFeatures.SyncToolSpecification specification) {
        if (server == null) {
            throw new IllegalArgumentException("MCP server is required for tool publication");
        }
        if (specification == null || specification.tool() == null) {
            throw new IllegalArgumentException("MCP tool specification is required for publication");
        }
        synchronized (server) {
            String pendingName = specification.tool().name();
            List<String> publicationNames = new ArrayList<>(server.listTools().stream()
                .map(tool -> tool.name())
                .filter(name -> !name.equals(pendingName))
                .toList());
            publicationNames.add(pendingName);
            McpToolNamePolicy.auditPublicationNames(publicationNames);
            server.addTool(withInvocationContext(specification));
        }
    }

    private static McpServerFeatures.SyncToolSpecification withInvocationContext(
        McpServerFeatures.SyncToolSpecification specification
    ) {
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(specification.tool())
            .callHandler((exchange, request) -> {
                Map<String, Object> arguments = new LinkedHashMap<>(
                    request.arguments() == null ? Map.of() : request.arguments());
                McpInvocationArguments.enrich(request.name(), arguments, request.meta());
                McpSchema.CallToolRequest enrichedRequest = new McpSchema.CallToolRequest(
                    request.name(), arguments, request.meta());
                return specification.callHandler().apply(exchange, enrichedRequest);
            })
            .build();
    }
}
