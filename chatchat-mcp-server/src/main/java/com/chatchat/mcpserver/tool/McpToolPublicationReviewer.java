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
        addReviewedTool(server, ToolPublication.from(specification));
    }

    public static void addReviewedTool(McpSyncServer server, ToolPublication publication) {
        if (server == null) {
            throw new IllegalArgumentException("MCP server is required for tool publication");
        }
        review(publication);
        McpServerFeatures.SyncToolSpecification specification = governedSpecification(publication);
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

    public static void review(ToolPublication publication) {
        if (publication == null || publication.specification() == null
            || publication.specification().tool() == null) {
            throw new IllegalArgumentException("MCP tool publication is required");
        }
        McpSchema.Tool tool = publication.specification().tool();
        McpToolNamePolicy.requirePublishableName(tool.name());
        if (tool.title() == null || tool.title().isBlank()) {
            throw new IllegalArgumentException("MCP tool title is required: " + tool.name());
        }
        if (tool.description() == null || tool.description().isBlank()) {
            throw new IllegalArgumentException("MCP tool description is required: " + tool.name());
        }
        validateInputSchema(tool.name(), tool.inputSchema());
        McpToolPublicationDescriptor descriptor = publication.descriptor();
        if (!tool.name().equals(descriptor.metadata().getId())) {
            throw new IllegalArgumentException("MCP descriptor id must equal tool name: " + tool.name());
        }
        if (descriptor.status() == McpToolPublicationStatus.DEPRECATED
            && (descriptor.deprecationMessage() == null || descriptor.deprecationMessage().isBlank())) {
            throw new IllegalArgumentException("Deprecated MCP tool requires migration guidance: " + tool.name());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateInputSchema(String toolName, Map<String, Object> schema) {
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("MCP tool input schema must be an object: " + toolName);
        }
        Object rawProperties = schema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            throw new IllegalArgumentException("MCP tool input schema requires properties: " + toolName);
        }
        Object rawRequired = schema.get("required");
        if (rawRequired != null && !(rawRequired instanceof Iterable<?>)) {
            throw new IllegalArgumentException("MCP tool required must be an array: " + toolName);
        }
        if (rawRequired instanceof Iterable<?> required) {
            for (Object name : required) {
                if (name == null || !properties.containsKey(String.valueOf(name))) {
                    throw new IllegalArgumentException("MCP required property is not declared: "
                        + toolName + "." + name);
                }
            }
        }
        Object additional = schema.get("additionalProperties");
        if (additional != null && !(additional instanceof Boolean) && !(additional instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("MCP additionalProperties must be boolean or schema: " + toolName);
        }
    }

    static McpServerFeatures.SyncToolSpecification governedSpecification(ToolPublication publication) {
        McpSchema.Tool source = publication.specification().tool();
        McpToolPublicationDescriptor descriptor = publication.descriptor();
        Map<String, Object> meta = new LinkedHashMap<>(source.meta() == null ? Map.of() : source.meta());
        meta.put("toolVersion", descriptor.metadata().getVersion());
        meta.put("schemaVersion", descriptor.schemaVersion());
        meta.put("publicationStatus", descriptor.status().name().toLowerCase(java.util.Locale.ROOT));
        meta.put("riskLevel", descriptor.metadata().getRiskLevel());
        meta.put("operationType", descriptor.metadata().getOperationType());
        meta.put("runtimeLevel", descriptor.metadata().getRuntimeLevel());
        meta.put("visibleTenantIds", descriptor.visibleTenantIds());
        meta.put("rolloutLabels", descriptor.rolloutLabels());
        meta.put("rolloutPercentage", descriptor.rolloutPercentage());
        if (descriptor.deprecationMessage() != null) {
            meta.put("deprecationMessage", descriptor.deprecationMessage());
        }
        String description = source.description();
        if (descriptor.status() == McpToolPublicationStatus.DEPRECATED) {
            description = "[DEPRECATED] " + descriptor.deprecationMessage() + " " + description;
        }
        McpSchema.Tool governedTool = McpSchema.Tool.builder()
            .name(source.name()).title(source.title()).description(description)
            .inputSchema(source.inputSchema()).outputSchema(source.outputSchema())
            .annotations(source.annotations()).icons(source.icons()).meta(meta).build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(governedTool)
            .callHandler((exchange, request) -> {
                Map<String, Object> arguments = new LinkedHashMap<>(
                    request.arguments() == null ? Map.of() : request.arguments());
                McpInvocationArguments.enrich(request.name(), arguments, request.meta());
                if (!visibleToCaller(publication.descriptor(), arguments)) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("Tool is not enabled for this caller rollout")
                        .structuredContent(Map.of("success", false,
                            "code", "MCP_TOOL_NOT_ENABLED_FOR_CALLER", "toolName", request.name()))
                        .isError(true).build();
                }
                McpSchema.CallToolRequest enriched = new McpSchema.CallToolRequest(
                    request.name(), arguments, request.meta());
                return publication.specification().callHandler().apply(exchange, enriched);
            }).build();
    }

    private static boolean visibleToCaller(McpToolPublicationDescriptor descriptor,
                                           Map<String, Object> arguments) {
        String tenantId = stringValue(arguments.get("tenantId"));
        if (!descriptor.visibleTenantIds().isEmpty()
            && (tenantId == null || !descriptor.visibleTenantIds().contains(tenantId))) return false;
        if (descriptor.rolloutPercentage() >= 100) return true;
        if (descriptor.rolloutPercentage() <= 0) return false;
        String userId = stringValue(arguments.get("userId"));
        String bucketKey = (tenantId == null ? "anonymous" : tenantId) + ":"
            + (userId == null ? "anonymous" : userId) + ":" + descriptor.metadata().getId();
        return Math.floorMod(bucketKey.hashCode(), 100) < descriptor.rolloutPercentage();
    }

    private static String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

}
