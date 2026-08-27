package com.chatchat.mcpserver.templatepublication.policy;

import java.util.Map;

/**
 * Maps persisted template-query parent contracts to their public domain bridges.
 *
 * <p>Bindings keep the historical parent name so existing rows remain valid. Only the MCP routing
 * metadata is translated. Domain bridges delegate back through the historical parent contract when
 * applying the service, tenant, role and template allow-list policy.</p>
 */
public final class TemplateQueryBridgeRoutingPolicy {
    private static final Map<String, String> PUBLIC_BRIDGES = Map.of(
        "api_template_query", "api_service_query",
        "ssh_template_query", "server_capability_query",
        "http_endpoint_template_query", "http_capability_query",
        "database_ops_template_search", "database_capability_query",
        "sql_datasource_template_query", "database_capability_query",
        "database_query_template_query", "data_query_query"
    );

    private TemplateQueryBridgeRoutingPolicy() {
    }

    public static String publicBridge(String persistedParentToolName) {
        if (persistedParentToolName == null || persistedParentToolName.isBlank()) {
            throw new IllegalArgumentException("Template query parent tool name is required");
        }
        String parent = persistedParentToolName.trim();
        return PUBLIC_BRIDGES.getOrDefault(parent, parent);
    }
}
