package com.chatchat.mcpserver.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
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
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;

    /**
     * Converts the value to tool specification.
     *
     * @param config the config value
     * @return the converted tool specification
     */
    public McpServerFeatures.SyncToolSpecification toToolSpecification(ApiServiceConfig config) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(config.getToolName())
            .title(config.getTitle())
            .description(config.getDescription() == null ? "External API service" : config.getDescription())
            .inputSchema(toInputSchema(config.getInputSchemaJson()))
            .meta(withLimitMeta(withLegacyId(governanceFactory.metaForApi(config), "apiServiceId", config.getId()),
                config.getToolName(), "http"))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                config.getToolName(),
                "http",
                request.arguments(),
                () -> {
                log.info("MCP external API tool call received tool={} apiServiceId={} argKeys={}",
                    config.getToolName(),
                    config.getId(),
                    argumentKeys(request.arguments()));
                return toCallToolResult(invokeService.invoke(config, request.arguments()));
            }))
            .build();
    }

    /**
     * Converts the value to input schema.
     *
     * @param schemaJson the schema json value
     * @return the converted input schema
     */
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

    /**
     * Converts the value to call tool result.
     *
     * @param result the result value
     * @return the converted call tool result
     */
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

    /**
     * Reads the map.
     *
     * @param value the value value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        return Map.of();
    }

    /**
     * Reads the string list.
     *
     * @param value the value value
     * @return the operation result
     */
    private List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * Performs the summarize body operation.
     *
     * @param body the body value
     * @param rawBody the raw body value
     * @return the operation result
     */
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

    /**
     * Performs the argument keys operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }

    /**
     * Performs the with legacy id operation.
     *
     * @param meta the meta value
     * @param key the key value
     * @param value the value value
     * @return the operation result
     */
    private Map<String, Object> withLegacyId(Map<String, Object> meta, String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put(key, value);
        return values;
    }

    private Map<String, Object> withLimitMeta(Map<String, Object> meta, String toolName, String runtimeLevel) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("mcp_tool_limit", concurrencyManager.limitMeta(toolName, runtimeLevel));
        return values;
    }
}
