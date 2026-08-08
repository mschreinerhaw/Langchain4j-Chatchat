package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.McpToolNamePolicy;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;

import java.util.ArrayList;
import java.util.List;

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
            server.addTool(specification);
        }
    }
}
