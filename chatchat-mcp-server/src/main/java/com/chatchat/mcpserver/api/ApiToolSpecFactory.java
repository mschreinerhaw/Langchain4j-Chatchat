package com.chatchat.mcpserver.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiToolSpecFactory {

    private final ApiInvokeService invokeService;
    private final ObjectMapper objectMapper;

    public McpServerFeatures.SyncToolSpecification toToolSpecification(ApiServiceConfig config) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(config.getToolName())
            .title(config.getTitle())
            .description(config.getDescription() == null ? "External API service" : config.getDescription())
            .inputSchema(toInputSchema(config.getInputSchemaJson()))
            .meta(Map.of("source", "external_api", "apiServiceId", config.getId()))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                log.info("MCP external API tool call received tool={} apiServiceId={} argKeys={}",
                    config.getToolName(),
                    config.getId(),
                    argumentKeys(request.arguments()));
                return toCallToolResult(invokeService.invoke(config, request.arguments()));
            })
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

    private McpSchema.CallToolResult toCallToolResult(ApiInvokeResult result) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("statusCode", result.statusCode());
        structured.put("headers", result.headers());
        structured.put("body", result.body());

        String text = result.success()
            ? summarizeBody(result.body(), result.rawBody())
            : result.errorMessage();

        return McpSchema.CallToolResult.builder()
            .addTextContent(text == null ? "" : text)
            .structuredContent(structured)
            .isError(!result.success())
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

    private String summarizeBody(Object body, String rawBody) {
        if (body == null) {
            return "";
        }
        if (body instanceof String text) {
            return text;
        }
        if (rawBody != null && !rawBody.isBlank()) {
            return rawBody;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            return String.valueOf(body);
        }
    }

    private List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }
}
