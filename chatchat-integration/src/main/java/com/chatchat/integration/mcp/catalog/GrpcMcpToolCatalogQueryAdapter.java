package com.chatchat.integration.mcp.catalog;

import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpCapabilityNode;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** gRPC-backed read model so every API MCP catalog consumer shares the southbound transport. */
@Service
@ConditionalOnProperty(prefix = "chatchat.mcp.grpc.client", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class GrpcMcpToolCatalogQueryAdapter implements McpToolCatalogQueryPort {
    private final McpRuntimeTransportPort transport;

    public GrpcMcpToolCatalogQueryAdapter(
        @Qualifier("mcpRuntimeTransportPort") McpRuntimeTransportPort transport
    ) {
        this.transport = transport;
    }

    @Override
    public List<ServiceSummary> enabledServices() {
        return transport.services().stream().filter(service -> service.enabled())
            .map(service -> new ServiceSummary(service.serviceId(), service.name(), true,
                instant(service.metadata().get("updatedAt"))))
            .toList();
    }

    @Override
    public List<RegisteredTool> registeredTools() {
        return transport.tools(McpToolQuery.all()).stream().map(tool -> {
            Map<String, Object> metadata = tool.metadata();
            return new RegisteredTool(tool.localToolName(), tool.serviceId(),
                text(metadata.get("serviceName"), tool.serviceId()), tool.remoteToolName(),
                tool.description(), text(metadata.get("backendServiceType"), null),
                tool.capabilityCode(), strings(metadata.get("categories")),
                strings(metadata.get("tags")), map(metadata.get("applicability")),
                McpCapabilityNode.fromMetadata(
                    map(metadata.get(McpCapabilityHierarchy.METADATA_KEY)),
                    tool.serviceId(), tool.localToolName()).orElse(null));
        }).toList();
    }

    private Instant instant(Object value) {
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Instant.parse(String.valueOf(value)); }
        catch (RuntimeException ignored) { return null; }
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(item -> { if (item != null) result.add(String.valueOf(item)); });
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
    }
}
