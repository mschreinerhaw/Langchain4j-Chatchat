package com.chatchat.enterprise.service;

import com.chatchat.common.mcp.capability.CapabilityManifest;
import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** No inferred affordances, executable aliases or caller-specific authorization claims. */
public final class CapabilityManifestFactory {
    private final ObjectMapper canonical;
    public CapabilityManifestFactory(ObjectMapper mapper) {
        canonical = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public CapabilityManifest generate(McpToolDescriptor tool, String status) {
        String id = id(tool);
        Map<String, Object> provider = new LinkedHashMap<>(Map.of("type", "MCP_TOOL",
            "serverId", tool.serviceId(), "toolName", tool.localToolName(), "remoteToolName", tool.remoteToolName()));
        Map<String, Object> execution = new LinkedHashMap<>(Map.of("serviceId", tool.serviceId(),
            "toolName", tool.localToolName(), "runtimeAuthorizationRequired", true));
        Object dynamic = tool.metadata().get(McpDynamicCapabilityRoute.METADATA_KEY);
        if (dynamic instanceof Map<?, ?> values && !values.isEmpty()) {
            McpDynamicCapabilityRoute.fromToolMetadata(tool.metadata()).ifPresent(route -> {
                provider.put("parentToolName", route.parentToolName());
                provider.put("kind", "DYNAMIC_CHILD");
                execution.put("dynamicRoute", route.toMetadata());
            });
        }
        Map<String, Object> contract = new LinkedHashMap<>(Map.of("inputSchema", tool.inputSchema(),
            "outputSchema", tool.outputSchema(), "paginationSupported", tool.paginationSupported()));
        if (tool.resultKind() != null) contract.put("resultKind", tool.resultKind().name());
        if (tool.resultSchemaRef() != null) contract.put("resultSchemaRef", tool.resultSchemaRef());
        Map<String, Object> discovery = new LinkedHashMap<>(Map.of("name", tool.localToolName(),
            "description", tool.description(), "capabilityCode", tool.capabilityCode()));
        Map<String, Object> declared = declared(tool.metadata());
        if (declared.get("workflowId") != null) execution.put("workflowId", declared.get("workflowId"));
        for (String key : List.of("name", "whenToUse", "keywords", "limitations")) {
            if (declared.containsKey(key)) discovery.put(key, declared.get(key));
        }
        Map<String, Object> governance = new LinkedHashMap<>();
        for (String key : List.of("operationType", "confirmation", "agentCompatible",
            "publicationStatus", "schemaVersion", "visibleTenantIds", "rolloutPercentage")) {
            if (tool.governance().containsKey(key)) governance.put(key, tool.governance().get(key));
        }
        CapabilityManifest content = new CapabilityManifest(CapabilityManifest.V1, id, "", status,
            provider, discovery, contract, execution, governance, declared);
        return new CapabilityManifest(CapabilityManifest.V1, id, hash(json(content)), status,
            provider, discovery, contract, execution, governance, declared);
    }
    public static String id(McpToolDescriptor tool) {
        return "mcp-" + hash(tool.serviceId() + "\n" + tool.localToolName());
    }
    public CapabilityManifest withStatus(CapabilityManifest source, String status) {
        CapabilityManifest content = new CapabilityManifest(CapabilityManifest.V1, source.capabilityId(), "", status,
            source.provider(), source.discovery(), source.contract(), source.execution(), source.governance(), source.publisherCapabilities());
        return new CapabilityManifest(CapabilityManifest.V1, source.capabilityId(), hash(json(content)), status,
            source.provider(), source.discovery(), source.contract(), source.execution(), source.governance(), source.publisherCapabilities());
    }
    public String json(Object value) {
        try { return canonical.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Cannot serialize capability contract", ex); }
    }
    private static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static Map<String, Object> declared(Map<String, Object> metadata) {
        Object raw = metadata.get("capabilityManifest");
        if (raw == null && metadata.get("mcpToolMeta") instanceof Map<?, ?> remote)
            raw = remote.get("capabilityManifest");
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        // Only publisher's descriptive contract is projected, never arbitrary transport metadata.
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("schemaVersion", "workflowId", "operations", "datasets", "dataCapabilities",
            "searchContract", "readContract", "name", "description", "whenToUse", "keywords", "limitations")) {
            if (values.get(key) != null) result.put(key, values.get(key));
        }
        return result;
    }
}
