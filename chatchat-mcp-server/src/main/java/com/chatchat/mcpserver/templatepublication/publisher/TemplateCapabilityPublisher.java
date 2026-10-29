package com.chatchat.mcpserver.templatepublication.publisher;

import com.chatchat.mcpserver.templatepublication.admin.TemplateCapabilityService;
import com.chatchat.mcpserver.tool.McpToolContributor;
import com.chatchat.mcpserver.tool.ToolPublication;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Component @RequiredArgsConstructor
public class TemplateCapabilityPublisher implements McpToolContributor {
    private final TemplateCapabilityService capabilities;
    private final ObjectProvider<McpSyncServer> server;
    private final ObjectMapper json;
    @Override public String contributorId() { return "template-capabilities"; }
    @Override public McpSyncServer publicationServer() { return server.getObject(); }
    @Override public List<ToolPublication> contribute() {
        return capabilities.running().stream().map(row -> {
            var snapshot = capabilities.definition(row);
            var schema = snapshot.path("inputSchema");
            Map<String, Object> properties = json.convertValue(schema.path("properties"), new com.fasterxml.jackson.core.type.TypeReference<>(){});
            List<String> required = new ArrayList<>(); schema.path("required").forEach(value -> required.add(value.asText()));
            boolean studioSql=snapshot.path("binding").path("sqlRevisionId").isTextual();
            String description=row.getDescription() == null || row.getDescription().isBlank() ? row.getTitle() : row.getDescription();
            if(studioSql)description+="\nReturns the existing ToolOutput envelope: SQL rows are at $.data.data.rows and rowCount at $.data.data.rowCount. SQL column names are NOT top-level result fields. Preserve this actual envelope in downstream contracts.";
            Map<String,Object> outputSchema=Map.of("type", "object", "properties", Map.of());
            if(studioSql)try {outputSchema=json.readValue(row.getOutputSchemaJson(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
                catch(Exception invalid){throw new IllegalStateException("Invalid published SQL output contract",invalid);}
            var tool = McpSchema.Tool.builder().name(TemplateCapabilityService.toolName(row)).title(row.getTitle())
                .description(description)
                .inputSchema(json.convertValue(schema, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}))
                .outputSchema(outputSchema)
                .meta(Map.of("templateCapability", true, "schemaVersion", "template-capability.v1", "toolVersion", row.getReleaseNumber() + ".0.0",
                    "visibleTenantIds", List.of(row.getTenantId()), "breakingChangeApproved", true,
                    "runtimeLevel", "controlled", "riskLevel", "high", "operationType", "execute"))
                .build();
            return ToolPublication.from(McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
                try {
                    var output = capabilities.invoke(request.name(), request.arguments(), request.meta());
                    return McpSchema.CallToolResult.builder().structuredContent(json.convertValue(output, Object.class))
                        .addTextContent(output.isSuccess() ? "模板执行完成" : Objects.toString(output.getErrorMessage(), "模板执行失败"))
                        .isError(!output.isSuccess()).build();
                } catch (SecurityException denied) {
                    return McpSchema.CallToolResult.builder().isError(true).addTextContent(denied.getMessage())
                        .structuredContent(Map.of("success", false, "code", "TEMPLATE_ACCESS_DENIED")).build();
                } catch (IllegalArgumentException invalid) {
                    return McpSchema.CallToolResult.builder().isError(true).addTextContent(invalid.getMessage())
                        .structuredContent(Map.of("success", false, "code", "TEMPLATE_PARAMETER_INVALID")).build();
                }
            }).build());
        }).toList();
    }
}
