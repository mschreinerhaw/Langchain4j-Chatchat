package com.chatchat.mcpserver.database;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
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
public class DatabaseQueryToolSpecFactory {

    private final DatabaseQueryInvokeService invokeService;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;
    private final StandardToolExecutionResultFactory standardResultFactory;

    /**
     * Converts the value to tool specification.
     *
     * @param config the config value
     * @return the converted tool specification
     */
    public McpServerFeatures.SyncToolSpecification toToolSpecification(DatabaseQueryConfig config) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(config.getToolName())
            .title(config.getTitle())
            .description(description(config))
            .inputSchema(toInputSchema(config.getInputSchemaJson()))
            .meta(withLimitMeta(withProtocolMeta(
                    withLegacyId(governanceFactory.metaForDatabaseQuery(config), "databaseQueryId", config.getId()),
                    config),
                config.getToolName(), "sql"))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                config.getToolName(),
                "sql",
                request.arguments(),
                () -> {
                log.info("MCP database query tool call received tool={} databaseQueryId={} argKeys={}",
                    config.getToolName(),
                    config.getId(),
                    argumentKeys(request.arguments()));
                return toCallToolResult(config, request.arguments(), invokeService.invoke(config, request.arguments()));
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
     * @param output the output value
     * @return the converted call tool result
     */
    private McpSchema.CallToolResult toCallToolResult(DatabaseQueryConfig config, Map<String, Object> arguments,
                                                      ToolOutput output) {
        Object structured = standardResultFactory.fromDatabaseQuery(config, arguments, output);
        boolean success = output != null && output.isSuccess();
        String text = success
            ? summarizeData(config, output.getData())
            : output == null ? "database_query returned no output" : output.getErrorMessage();
        return McpSchema.CallToolResult.builder()
            .addTextContent(text == null ? "" : text)
            .structuredContent(structured)
            .isError(!success)
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
     * Performs the summarize data operation.
     *
     * @param data the data value
     * @return the operation result
     */
    private String summarizeData(DatabaseQueryConfig config, Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("analysisContext", Map.of(
                "businessGroupCode", firstText(config.getBusinessGroup(), "default"),
                "businessGroupName", firstText(config.getBusinessGroupName(), firstText(config.getBusinessGroup(), "default")),
                "businessGroupDescription", firstText(config.getBusinessGroupDescription(), ""),
                "templateIntent", firstText(config.getTemplateIntent(), "general_query")
            ));
            summary.put("rowCount", map.get("rowCount"));
            summary.put("columns", map.get("columns"));
            summary.put("rows", map.get("rows"));
            try {
                return ModelProtocolJson.compact(summary);
            } catch (Exception ignored) {
                return String.valueOf(data);
            }
        }
        return String.valueOf(data);
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

    private Map<String, Object> withProtocolMeta(Map<String, Object> meta, DatabaseQueryConfig config) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("assetType", "database_query");
        values.put("targetRoutingRequired", false);
        values.put("templateId", config.getToolName());
        values.put("intent", firstText(config.getTemplateIntent(), "general_query"));
        values.put("businessGroup", businessGroupMeta(config));
        values.put("databaseType", firstText(config.getDatabaseType(), "generic"));
        values.put("tags", readJsonList(config.getTagsJson()));
        values.put("riskLevel", firstText(config.getRiskLevel(), "read_only"));
        values.put("owner", firstText(config.getOwner(), "admin"));
        values.put("rating", config.getRating());
        values.put("usageCount", config.getUsageCount());
        values.put("marketplace", Map.of(
            "registry", "business_database_query",
            "publishMode", "template_to_mcp_tool"
        ));
        values.put("routingLabels", readJsonList(config.getRoutingLabelsJson()));
        values.put("capabilities", readJsonList(config.getCapabilitiesJson()));
        return values;
    }

    private Map<String, Object> businessGroupMeta(DatabaseQueryConfig config) {
        return Map.of(
            "code", firstText(config.getBusinessGroup(), "default"),
            "name", firstText(config.getBusinessGroupName(), firstText(config.getBusinessGroup(), "default")),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    private String description(DatabaseQueryConfig config) {
        String description = firstText(config.getDescription(), "Read-only database query");
        String groupCode = firstText(config.getBusinessGroup(), "default");
        String groupName = firstText(config.getBusinessGroupName(), groupCode);
        String groupDescription = firstText(config.getBusinessGroupDescription(), "");
        StringBuilder builder = new StringBuilder(description)
            .append(" Business group: ")
            .append(groupName);
        if (!groupCode.equals(groupName)) {
            builder.append(" (").append(groupCode).append(")");
        }
        if (!groupDescription.isBlank()) {
            builder.append(". Group context: ").append(groupDescription);
        }
        return builder.toString();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }
}
