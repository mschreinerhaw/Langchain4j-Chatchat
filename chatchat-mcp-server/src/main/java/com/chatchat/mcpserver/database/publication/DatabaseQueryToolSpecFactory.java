package com.chatchat.mcpserver.database.publication;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.database.definition.DatabaseQuerySqlStep;

import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.DataAnalysisContextProtocol;
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
    private final DatabaseQueryMcpNamingPolicy namingPolicy;

    /**
     * Converts the value to tool specification.
     *
     * @param config the config value
     * @return the converted tool specification
     */
    public McpServerFeatures.SyncToolSpecification toToolSpecification(DatabaseQueryConfig config) {
        String publishedToolName = namingPolicy.toolName(config);
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(publishedToolName)
            .title(namingPolicy.title(config))
            .description(description(config))
            .inputSchema(toInputSchema(config.getInputSchemaJson()))
            .meta(withLimitMeta(withProtocolMeta(
                    withLegacyId(governanceFactory.metaForDatabaseQuery(config), "databaseQueryId", config.getId()),
                    config, publishedToolName),
                publishedToolName, "sql"))
            .build();

        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                publishedToolName,
                "sql",
                request.arguments(),
                () -> {
                log.info("MCP database query tool call received tool={} databaseQueryId={} argKeys={}",
                    publishedToolName,
                    config.getId(),
                    argumentKeys(request.arguments()));
                return toCallToolResult(config, publishedToolName, request.arguments(),
                    invokeService.invoke(config, request.arguments()));
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
    private McpSchema.CallToolResult toCallToolResult(DatabaseQueryConfig config, String publishedToolName,
                                                      Map<String, Object> arguments,
                                                      ToolOutput output) {
        Object structured = standardResultFactory.fromDatabaseQuery(
            config, arguments, output, publishedToolName);
        boolean success = output != null && output.isSuccess();
        String text = success
            ? summarizeData(config, output.getData())
            : output == null ? "数据库查询未返回任何结果。" : output.getErrorMessage();
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
            String groupCode = firstText(config.getBusinessGroup(), "default");
            Object fields = firstPresent(map.get("columnMetadata"), map.get("columns"));
            summary.put("analysisContext", DataAnalysisContextProtocol.create(
                Map.of(
                    "type", "database_query_template",
                    "displayName", firstText(config.getTitle(), config.getToolName()),
                    "toolName", firstText(config.getToolName(), "database_query"),
                    "description", firstText(config.getDescription(), "")
                ),
                Map.of(
                    "intent", firstText(config.getTemplateIntent(), "general_query"),
                    "implementationSteps", firstText(config.getImplementationSteps(), "")
                ),
                Map.of(
                    "code", groupCode,
                    "name", firstText(config.getBusinessGroupName(), groupCode),
                    "description", firstText(config.getBusinessGroupDescription(), "")
                ),
                Map.of("fields", fields == null ? List.of() : fields),
                Map.of()
            ));
            Object resultSets = firstPresent(map.get("resultSets"), map.get("results"));
            if (resultSets instanceof List<?>) {
                summary.put("executionMode", "SEQUENTIAL_MULTI_SQL");
                summary.put("resultSetCount", map.get("resultSetCount"));
                summary.put("resultSets", resultSets);
            }
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

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private Map<String, Object> withProtocolMeta(Map<String, Object> meta, DatabaseQueryConfig config,
                                                 String publishedToolName) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("assetType", "database_query");
        values.put("targetRoutingRequired", false);
        values.put("templateId", config.getToolName());
        values.put("publishedToolName", publishedToolName);
        values.put("intent", firstText(config.getTemplateIntent(), "general_query"));
        values.put("domain", firstText(config.getDomain(), "finance"));
        values.put("category", firstText(config.getCapabilityCategory(),
            firstText(config.getBusinessGroup(), "data_asset_exploration")));
        values.put("businessScope", firstText(config.getBusinessScope(), ""));
        values.put("indexTags", readJsonList(config.getIndexTagsJson()));
        values.put("implementationSteps", firstText(config.getImplementationSteps(), ""));
        values.put("workflowSteps", workflowStepMeta(config));
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
        values.put(McpToolApplicability.META_KEY, McpToolApplicability.of(
            "database_query:" + firstText(config.getCapabilityCategory(), "business_query_execution"),
            namingPolicy.title(config) + "专项数据能力",
            List.of("database_query", firstText(config.getCapabilityCategory(), "data_asset_exploration")),
            "按照已声明的参数契约执行“" + namingPolicy.title(config) + "”专项查询，返回有来源依据的结构化数据。",
            List.of(
                "用户需求与“" + firstText(config.getBusinessGroupName(), config.getCapabilityCategory())
                    + "”业务场景相符，并且需要查询该工具描述的数据。"
            ),
            List.of(
                "数据库运行维护或故障诊断",
                "临时编写或执行任意 SQL",
                "数据库表结构探索",
                "选择、安装或替换 Agent 工具"
            )
        ));
        return values;
    }

    private Map<String, Object> businessGroupMeta(DatabaseQueryConfig config) {
        return Map.of(
            "code", firstText(config.getBusinessGroup(), "default"),
            "name", firstText(config.getBusinessGroupName(), firstText(config.getBusinessGroup(), "default")),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    private List<Map<String, Object>> workflowStepMeta(DatabaseQueryConfig config) {
        if (config.getSqlStepsJson() == null || config.getSqlStepsJson().isBlank()) {
            return List.of();
        }
        try {
            List<DatabaseQuerySqlStep> steps = objectMapper.readValue(
                config.getSqlStepsJson(), new TypeReference<List<DatabaseQuerySqlStep>>() {});
            return steps.stream().map(step -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("code", firstText(step.getSqlCode(), ""));
                value.put("name", firstText(step.getSqlName(), ""));
                value.put("description", firstText(step.getSqlDescription(), ""));
                return Map.copyOf(value);
            }).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String description(DatabaseQueryConfig config) {
        String description = firstText(config.getDescription(), "执行只读数据库查询。");
        String groupCode = firstText(config.getBusinessGroup(), "default");
        String groupName = firstText(config.getBusinessGroupName(), groupCode);
        String groupDescription = firstText(config.getBusinessGroupDescription(), "");
        String businessScope = firstText(config.getBusinessScope(), "");
        String implementationSteps = firstText(config.getImplementationSteps(), "");
        StringBuilder builder = new StringBuilder(description)
            .append(" 业务领域：").append(domainName(config.getDomain()))
            .append("。能力分类：")
            .append(groupName)
            .append("（")
            .append(firstText(config.getCapabilityCategory(), groupCode))
            .append("）");
        if (!groupDescription.isBlank()) {
            builder.append("。分类用途：").append(groupDescription);
        }
        if (!businessScope.isBlank() && !businessScope.equals(description)) {
            builder.append("。适用范围：").append(businessScope);
        }
        if (!implementationSteps.isBlank()) {
            builder.append("。实现步骤：").append(implementationSteps);
        }
        return builder.toString();
    }

    private String domainName(String domain) {
        String value = firstText(domain, "finance");
        return "finance".equalsIgnoreCase(value) ? "金融" : value;
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
