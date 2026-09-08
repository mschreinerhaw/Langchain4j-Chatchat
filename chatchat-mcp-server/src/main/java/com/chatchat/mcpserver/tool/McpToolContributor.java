package com.chatchat.mcpserver.tool;

import io.modelcontextprotocol.server.McpSyncServer;

import java.util.List;
import java.util.Set;

/** A capability provider describes publications; it never mutates the MCP server directly. */
public interface McpToolContributor {
    String contributorId();
    McpSyncServer publicationServer();
    List<ToolPublication> contribute();
    default Set<String> retiredToolNames() { return Set.of(); }

    default McpToolPublicationPipeline.PublicationResult refreshPublication() {
        return McpToolPublicationPipeline.publish(publicationServer(), this);
    }
}
