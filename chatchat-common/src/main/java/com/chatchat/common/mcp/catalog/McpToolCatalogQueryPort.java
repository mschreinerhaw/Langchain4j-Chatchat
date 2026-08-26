package com.chatchat.common.mcp.catalog;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import com.chatchat.common.mcp.capability.McpCapabilityNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only MCP service and tool catalog boundary for every Runtime OS consumer. */
public interface McpToolCatalogQueryPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.catalog_query.v1";

    List<ServiceSummary> enabledServices();

    List<RegisteredTool> registeredTools();

    record ServiceSummary(String id, String name, boolean enabled, Instant updatedAt) {
    }

    record RegisteredTool(
        String localToolName,
        String serviceId,
        String serviceName,
        String remoteToolName,
        String description,
        String backendServiceType,
        String category,
        List<String> categories,
        List<String> tags,
        Map<String, Object> applicability,
        McpCapabilityNode capabilityNode
    ) {
        public RegisteredTool(String localToolName, String serviceId, String serviceName,
                              String remoteToolName, String description, String backendServiceType,
                              String category, List<String> categories, List<String> tags,
                              Map<String, Object> applicability) {
            this(localToolName, serviceId, serviceName, remoteToolName, description,
                backendServiceType, category, categories, tags, applicability, null);
        }

        public RegisteredTool(String localToolName, String serviceId, String serviceName,
                              String remoteToolName, String description) {
            this(localToolName, serviceId, serviceName, remoteToolName, description, null,
                null, List.of(), List.of(), Map.of(), null);
        }
    }
}
