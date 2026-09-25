package com.chatchat.mcpserver.external;

import com.chatchat.mcpserver.tool.McpToolContributor;
import com.chatchat.mcpserver.tool.ToolPublication;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Publishes only explicitly approved, read-only remote tools as local template tools. */
@Component
@RequiredArgsConstructor
public class ExternalMcpToolPublisher implements McpToolContributor {
    private final McpSyncServer server;
    private final ExternalMcpRegistryService registry;

    @Override public String contributorId() { return "external_mcp_templates"; }
    @Override public McpSyncServer publicationServer() { return server; }

    @Override public List<ToolPublication> contribute() {
        List<ToolPublication> publications = new ArrayList<>();
        for (ExternalMcpService service : registry.list()) {
            if (!service.isEnabled()) continue;
            for (ExternalMcpRegistryService.ToolTemplate template : registry.templates(service)) {
                if (!template.readOnly()) continue;
                String publishedName = publishedName(service.getId(), template.name());
                Map<String, Object> schema = new LinkedHashMap<>(template.inputSchema());
                schema.putIfAbsent("type", "object");
                schema.putIfAbsent("properties", Map.of());
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("schemaVersion", "external_mcp_template.v1");
                meta.put("parentToolName", service.getParentToolName());
                meta.put("sourceServiceId", service.getId());
                meta.put("sourceToolName", template.name());
                meta.put("executionWorkflow", service.getWorkflowId());
                meta.put("operationType", "read");
                meta.put("runtimeLevel", "readonly");
                meta.put("assetType", registry.parentAssetType(service));
                meta.put("templateSource", "external_mcp");
                McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(publishedName)
                    .title(template.title())
                    .description(template.description().isBlank() ? "External MCP template: " + template.title()
                        : template.description())
                    .inputSchema(schema)
                    .meta(meta)
                    .build();
                McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> registry.invoke(service.getId(), template.name(), request.arguments()))
                    .build();
                publications.add(ToolPublication.from(spec));
            }
        }
        return publications;
    }

    public static String publishedName(String serviceId, String sourceName) {
        String id = serviceId.replaceAll("[^A-Za-z0-9]", "");
        String name = sourceName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.length() > 55) name = name.substring(0, 55);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(sourceName.getBytes(StandardCharsets.UTF_8));
            return "external_" + id + "_" + name + "_" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
