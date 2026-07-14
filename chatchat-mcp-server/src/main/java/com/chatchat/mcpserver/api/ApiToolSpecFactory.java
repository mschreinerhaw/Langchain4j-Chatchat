package com.chatchat.mcpserver.api;

import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.agents.protocol.ModelProtocolJson;

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
            .meta(withLimitMeta(withProtocolMeta(withLegacyId(governanceFactory.metaForApi(config), "apiServiceId", config.getId()), config),
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
                return toCallToolResult(config, invokeService.invoke(config, request.arguments()));
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
    private McpSchema.CallToolResult toCallToolResult(ApiServiceConfig config, ApiInvokeResult result) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("statusCode", result.statusCode());
        structured.put("headers", result.headers());
        structured.put("body", result.body());
        structured.put("sourceMetadata", Map.of(
            "schemaVersion", "execution_source.v1",
            "executionType", "HTTP_REQUEST",
            "sourceType", "external_api",
            "toolName", config.getToolName(),
            "asset", nullableMap(
                "type", "api_service",
                "id", config.getId(),
                "name", config.getTitle(),
                "environment", null
            ),
            "operation", nullableMap(
                "method", config.getMethod(),
                "gatewayId", config.getGatewayId()
            ),
            "business", nullableMap(
                "name", firstText(config.getBusinessGroupName(), config.getTitle()),
                "description", firstText(config.getBusinessGroupDescription(), config.getDescription()),
                "category", config.getBusinessGroup()
            )
        ));

        String text = result.success()
            ? summarizeBody(result.body(), result.rawBody())
            : result.errorMessage();

        return McpSchema.CallToolResult.builder()
            .addTextContent(text == null ? "" : text)
            .structuredContent(structured)
            .isError(!result.success())
            .build();
    }

    private Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
            return ModelProtocolJson.compact(body);
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

    private Map<String, Object> withProtocolMeta(Map<String, Object> meta, ApiServiceConfig config) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("assetType", "api_service");
        values.put("targetRoutingRequired", false);
        values.put("templateId", config.getToolName());
        values.put("businessGroup", businessGroupMeta(config));
        values.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "api_service:published_operation",
            "Published API operation: " + config.getToolName(),
            List.of("api_service"),
            "Invoke the published API operation " + config.getToolName() + " with its declared parameter contract.",
            List.of("The user-bound tool directly represents the required external API operation."),
            List.of("Discovering unrelated APIs", "Changing the bound endpoint", "Selecting or replacing Agent-bound tools")
        ));
        return values;
    }

    private Map<String, Object> businessGroupMeta(ApiServiceConfig config) {
        String code = firstText(config.getBusinessGroup(), "default");
        return Map.of(
            "code", code,
            "name", firstText(config.getBusinessGroupName(), code),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
