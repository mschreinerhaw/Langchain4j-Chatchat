package com.chatchat.mcpserver.database;

import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DatabaseQueryToolSpecFactory {

    private final DatabaseQueryInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public McpServerFeatures.SyncToolSpecification toToolSpecification(DatabaseQueryConfig config) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(config.getToolName())
            .title(config.getTitle())
            .description(config.getDescription() == null ? "Read-only database query" : config.getDescription())
            .inputSchema(toInputSchema(config.getInputSchemaJson()))
            .meta(Map.of("source", "database_query_config", "databaseQueryId", config.getId()))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> toCallToolResult(invokeService.invoke(config, request.arguments())))
            .build();
    }

    private McpSchema.JsonSchema toInputSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null);
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, new TypeReference<>() {});
            String type = String.valueOf(schema.getOrDefault("type", "object"));
            Map<String, Object> properties = readMap(schema.get("properties"));
            List<String> required = readStringList(schema.get("required"));
            Boolean additionalProperties = schema.get("additionalProperties") instanceof Boolean value ? value : true;
            Map<String, Object> defs = readMap(schema.get("$defs"));
            Map<String, Object> definitions = readMap(schema.get("definitions"));
            return new McpSchema.JsonSchema(type, properties, required, additionalProperties, defs, definitions);
        } catch (Exception ex) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null);
        }
    }

    private McpSchema.CallToolResult toCallToolResult(ToolOutput output) {
        Object structured = output.getData();
        String text = output.isSuccess()
            ? summarizeData(output.getData())
            : output.getErrorMessage();
        return McpSchema.CallToolResult.builder()
            .addTextContent(text == null ? "" : text)
            .structuredContent(structured)
            .isError(!output.isSuccess())
            .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        return Map.of();
    }

    private List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String summarizeData(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("rowCount", map.get("rowCount"));
            summary.put("columns", map.get("columns"));
            summary.put("rows", map.get("rows"));
            try {
                return objectMapper.writeValueAsString(summary);
            } catch (Exception ignored) {
                return String.valueOf(data);
            }
        }
        return String.valueOf(data);
    }
}
