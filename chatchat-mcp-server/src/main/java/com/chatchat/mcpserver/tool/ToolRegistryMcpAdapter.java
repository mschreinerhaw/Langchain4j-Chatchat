package com.chatchat.mcpserver.tool;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class ToolRegistryMcpAdapter {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String DOCUMENT_SEARCH_EVIDENCE_GUIDANCE = String.join(" ",
        "document_search retrieves bounded, topK document evidence chunks for grounded answers.",
        "Treat document_search as bounded retrieval, not full dataset scanning.",
        "Use concise, specific queries with key terms, document titles, product names, codes, dates, or domain terms when available.",
        "Queries must include at least one concrete constraint when possible, such as entity, time, keyword, or domain.",
        "The tool returns only topK relevant chunks and may return empty results.",
        "Empty results do not imply permission to scan all documents or broaden search space.",
        "If results are empty, refine or rewrite the query at most once.",
        "If the refined query still returns empty, stop retrieval and report insufficient evidence.",
        "Do not perform wildcard, exhaustive, or full-library search strategies.",
        "Prefer evidence quality over broad recall.",
        "Use returned citations and snippets as the sole grounding source for answers.",
        "The stable result contract is document_evidence_v1.",
        "Set debug=true only when retrieval diagnostics are needed."
    );
    private static final String DOCUMENT_SEARCH_QUERY_GUIDANCE = String.join(" ",
        "Question or concise retrieval query used to find document evidence.",
        "Preserve exact document titles, product names, codes, versions, dates, and domain terms.",
        "Avoid broad or ambiguous single generic terms; include at least one concrete constraint when possible."
    );

    private final ObjectMapper objectMapper;
    private final ChatChatMcpServerProperties properties;
    private final AgentRuntimeGovernanceFactory governanceFactory;
    private final McpToolConcurrencyManager concurrencyManager;

    /**
     * Converts the value to tool specifications.
     *
     * @param toolRegistry the tool registry value
     * @return the converted tool specifications
     */
    public List<McpServerFeatures.SyncToolSpecification> toToolSpecifications(ToolRegistry toolRegistry) {
        return toolRegistry.getAllToolNames().stream()
            .sorted(Comparator.naturalOrder())
            .map(name -> toToolSpecification(toolRegistry, name))
            .flatMap(List::stream)
            .toList();
    }

    /**
     * Converts the value to tool specification.
     *
     * @param toolRegistry the tool registry value
     * @param name the name value
     * @return the converted tool specification
     */
    private List<McpServerFeatures.SyncToolSpecification> toToolSpecification(ToolRegistry toolRegistry, String name) {
        ToolMetadata metadata = toolRegistry.getToolMetadata(name);
        if (isExcluded(name)) {
            return List.of();
        }
        if (properties.isExposeAgentCompatibleOnly()
            && metadata != null
            && !metadata.isAgentCompatible()) {
            return List.of();
        }

        String runtimeLevel = runtimeLevelFor(name, metadata);
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(name)
            .title(metadata == null ? name : metadata.getTitle())
            .description(description(toolRegistry, name, metadata))
            .inputSchema(toInputSchema(name, metadata))
            .meta(withLimitMeta(governanceFactory.metaForToolMetadata("builtin_tool", name, metadata), name, runtimeLevel))
            .build();

        return List.of(McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> concurrencyManager.execute(
                name,
                runtimeLevel,
                request.arguments(),
                () -> invokeTool(toolRegistry, name, metadata, request)))
            .build());
    }

    /**
     * Returns whether is excluded.
     *
     * @param name the name value
     * @return whether the condition is satisfied
     */
    private boolean isExcluded(String name) {
        if (name == null || properties.getExcludedToolNames() == null) {
            return false;
        }
        return properties.getExcludedToolNames().stream()
            .filter(excluded -> excluded != null && !excluded.isBlank())
            .anyMatch(excluded -> excluded.equalsIgnoreCase(name));
    }

    /**
     * Performs the invoke tool operation.
     *
     * @param toolRegistry the tool registry value
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @param request the request value
     * @return the operation result
     */
    private McpSchema.CallToolResult invokeTool(
        ToolRegistry toolRegistry,
        String toolName,
        ToolMetadata metadata,
        McpSchema.CallToolRequest request
    ) {
        Map<String, Object> arguments = applyDefaults(metadata, request.arguments());
        ToolOutput output;
        long startedAt = System.currentTimeMillis();
        log.info("MCP server tool call started tool={} timeoutMs={} args={}",
            toolName,
            metadata == null ? null : metadata.getTimeoutMillis(),
            ToolLogSummarizer.summarize(arguments));

        ToolRegistry.EnhancedTool enhancedTool = toolRegistry.getEnhancedTool(toolName);
        try {
            if (enhancedTool != null) {
                output = toolRegistry.executeEnhancedTool(toolName, ToolInput.builder()
                    .parameters(arguments)
                    .rawInput(rawInput(arguments))
                    .build());
            } else {
                ToolRegistry.Tool simpleTool = toolRegistry.getTool(toolName);
                if (simpleTool == null) {
                    output = ToolOutput.failure("Tool not found: " + toolName);
                } else {
                    output = ToolOutput.success(simpleTool.execute(rawInput(arguments)));
                }
            }
        } catch (Throwable throwable) {
            output = ToolOutput.failure(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            log.warn("MCP built-in tool call threw tool={} durationMs={} error={}",
                toolName,
                Math.max(0L, System.currentTimeMillis() - startedAt),
                throwable.getMessage(),
                throwable);
        }

        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        if (output != null && output.isSuccess()) {
            log.info("MCP server tool call succeeded tool={} durationMs={} message={} result={}",
                toolName,
                durationMs,
                successText(output),
                ToolLogSummarizer.summarize(output.getData()));
        } else {
            log.warn("MCP server tool call failed tool={} durationMs={} error={} result={}",
                toolName,
                durationMs,
                errorText(output),
                ToolLogSummarizer.summarize(output == null ? null : output.getData()));
        }
        return toCallToolResult(toolName, output);
    }

    /**
     * Converts the value to input schema.
     *
     * @param metadata the metadata value
     * @return the converted input schema
     */
    private McpSchema.JsonSchema toInputSchema(String toolName, ToolMetadata metadata) {
        if (metadata == null || metadata.getParameters() == null || metadata.getParameters().isEmpty()) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        }

        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter parameter : metadata.getParameters()) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            schemaProperties.put(parameter.getName(), toPropertySchema(toolName, parameter));
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }

        return new McpSchema.JsonSchema("object", schemaProperties, required, false, null, null);
    }

    /**
     * Converts the value to property schema.
     *
     * @param parameter the parameter value
     * @return the converted property schema
     */
    private Map<String, Object> toPropertySchema(String toolName, ToolParameter parameter) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", normalizeType(parameter.getType()));
        putIfPresent(property, "description", parameterDescription(toolName, parameter));
        putIfPresent(property, "default", parameter.getDefaultValue());
        putIfPresent(property, "minLength", parameter.getMinLength());
        putIfPresent(property, "maxLength", parameter.getMaxLength());
        putIfPresent(property, "minimum", parameter.getMinimum());
        putIfPresent(property, "maximum", parameter.getMaximum());
        putIfPresent(property, "exclusiveMinimum", parameter.getExclusiveMinimum());
        putIfPresent(property, "exclusiveMaximum", parameter.getExclusiveMaximum());
        putIfPresent(property, "pattern", parameter.getPattern());
        if (parameter.getEnumValues() != null && parameter.getEnumValues().length > 0) {
            property.put("enum", List.of(parameter.getEnumValues()));
        }
        if (parameter.getMetadata() != null && !parameter.getMetadata().isEmpty()) {
            property.putAll(parameter.getMetadata());
        }
        return property;
    }

    /**
     * Performs the apply defaults operation.
     *
     * @param metadata the metadata value
     * @param arguments the arguments value
     * @return the operation result
     */
    private Map<String, Object> applyDefaults(ToolMetadata metadata, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (arguments != null) {
            normalized.putAll(arguments);
        }
        if (metadata == null || metadata.getParameters() == null) {
            return normalized;
        }
        for (ToolParameter parameter : metadata.getParameters()) {
            if (parameter == null || parameter.getName() == null) {
                continue;
            }
            if (!normalized.containsKey(parameter.getName()) && parameter.getDefaultValue() != null) {
                normalized.put(parameter.getName(), parameter.getDefaultValue());
            }
        }
        return normalized;
    }

    /**
     * Converts the value to call tool result.
     *
     * @param output the output value
     * @return the converted call tool result
     */
    private McpSchema.CallToolResult toCallToolResult(String toolName, ToolOutput output) {
        boolean failed = output == null || !output.isSuccess();
        Object structuredContent = structuredContent(output, failed);
        if (isDocumentSearchToolName(toolName)) {
            structuredContent = sanitizeDocumentSearchContent(structuredContent);
        }
        String text = failed ? errorText(output) : successText(output, structuredContent);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (output != null && output.getExecutionTimeMs() != null) {
            meta.put("executionTimeMs", output.getExecutionTimeMs());
        }
        if (output != null && output.getMetadata() != null && !output.getMetadata().isEmpty()) {
            meta.putAll(output.getMetadata());
        }

        return McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .structuredContent(structuredContent)
            .isError(failed)
            .meta(meta)
            .build();
    }

    /**
     * Performs the structured content operation.
     *
     * @param output the output value
     * @param failed the failed value
     * @return the operation result
     */
    private Object structuredContent(ToolOutput output, boolean failed) {
        if (output != null && output.getData() != null) {
            return output.getData();
        }
        if (!failed) {
            return Map.of("result", successText(output));
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("errorMessage", errorText(output));
        if (output != null && output.getExceptionType() != null) {
            error.put("exceptionType", output.getExceptionType());
        }
        return error;
    }

    /**
     * Performs the description operation.
     *
     * @param toolRegistry the tool registry value
     * @param name the name value
     * @param metadata the metadata value
     * @return the operation result
     */
    private String description(ToolRegistry toolRegistry, String name, ToolMetadata metadata) {
        String baseDescription;
        if (metadata != null && metadata.getDescription() != null && !metadata.getDescription().isBlank()) {
            baseDescription = metadata.getDescription();
        } else {
            ToolRegistry.Tool simpleTool = toolRegistry.getTool(name);
            if (simpleTool != null && simpleTool.getDescription() != null && !simpleTool.getDescription().isBlank()) {
                baseDescription = simpleTool.getDescription();
            } else {
                baseDescription = "ChatChat tool: " + name;
            }
        }
        if (isDocumentSearchToolName(name) && !containsEvidenceGuidance(baseDescription)) {
            return baseDescription + " " + DOCUMENT_SEARCH_EVIDENCE_GUIDANCE;
        }
        return baseDescription;
    }

    private Map<String, Object> withLimitMeta(Map<String, Object> meta, String toolName, String runtimeLevel) {
        Map<String, Object> values = new LinkedHashMap<>(meta == null ? Map.of() : meta);
        values.put("mcp_tool_limit", concurrencyManager.limitMeta(toolName, runtimeLevel));
        if (isDocumentSearchToolName(toolName)) {
            values.put("result_contract", "document_evidence_chunks");
            values.put("contract_version", "document_evidence_v1");
            values.put("retrieval_guidance", DOCUMENT_SEARCH_EVIDENCE_GUIDANCE);
            values.put("default_debug", false);
        }
        return values;
    }

    private String parameterDescription(String toolName, ToolParameter parameter) {
        String baseDescription = parameter.getDescription();
        if (isDocumentSearchToolName(toolName)
            && "query".equals(parameter.getName())
            && !containsEvidenceGuidance(baseDescription)) {
            if (baseDescription == null || baseDescription.isBlank()) {
                return DOCUMENT_SEARCH_QUERY_GUIDANCE;
            }
            return baseDescription + " " + DOCUMENT_SEARCH_QUERY_GUIDANCE;
        }
        return baseDescription;
    }

    private boolean isDocumentSearchToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalized = toolName.trim().toLowerCase();
        return DOCUMENT_SEARCH_TOOL.equals(normalized) || normalized.endsWith("_" + DOCUMENT_SEARCH_TOOL);
    }

    private boolean containsEvidenceGuidance(String text) {
        return text != null && text.toLowerCase().contains("evidence");
    }

    private String runtimeLevelFor(String name, ToolMetadata metadata) {
        if (metadata != null && metadata.getCategory() != null) {
            String category = metadata.getCategory().toLowerCase();
            if (category.contains("http") || category.contains("api")) {
                return "http";
            }
            if (category.contains("sql") || category.contains("database")) {
                return "sql";
            }
            if (category.contains("notification")) {
                return "notification";
            }
        }
        return null;
    }

    /**
     * Performs the success text operation.
     *
     * @param output the output value
     * @return the operation result
     */
    private String successText(ToolOutput output) {
        return successText(output, output == null ? null : output.getData());
    }

    private String successText(ToolOutput output, Object content) {
        if (output == null) {
            return "";
        }
        if (output.getMessage() != null && !output.getMessage().isBlank()) {
            return output.getMessage();
        }
        return stringify(content);
    }

    private Object sanitizeDocumentSearchContent(Object value) {
        Object normalized = value;
        if (value != null
            && !(value instanceof Map<?, ?>)
            && !(value instanceof List<?>)
            && !(value instanceof String)
            && !(value instanceof Number)
            && !(value instanceof Boolean)) {
            normalized = objectMapper.convertValue(value, Object.class);
        }
        return sanitizeDocumentSearchValue(normalized);
    }

    private Object sanitizeDocumentSearchValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key == null || isDocumentSearchInternalSignalKey(String.valueOf(key))) {
                    return;
                }
                sanitized.put(String.valueOf(key), sanitizeDocumentSearchValue(item));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::sanitizeDocumentSearchValue)
                .toList();
        }
        return value;
    }

    private boolean isDocumentSearchInternalSignalKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase().replace("_", "").replace("-", "");
        return Set.of(
            "matchedkeywords",
            "fieldscores",
            "scorebreakdown",
            "querytokens",
            "expandedtokens",
            "significantterms",
            "memoryrecallraw",
            "memoryrecall",
            "trace"
        ).contains(normalized);
    }

    /**
     * Performs the error text operation.
     *
     * @param output the output value
     * @return the operation result
     */
    private String errorText(ToolOutput output) {
        if (output == null) {
            return "Tool execution failed";
        }
        if (output.getErrorMessage() != null && !output.getErrorMessage().isBlank()) {
            return output.getErrorMessage();
        }
        if (output.getMessage() != null && !output.getMessage().isBlank()) {
            return output.getMessage();
        }
        return "Tool execution failed";
    }

    /**
     * Performs the raw input operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private String rawInput(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        if (arguments.size() == 1) {
            Object onlyValue = arguments.values().iterator().next();
            return onlyValue == null ? "" : String.valueOf(onlyValue);
        }
        return stringify(arguments);
    }

    /**
     * Performs the stringify operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return ModelProtocolJson.compact(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
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
     * Normalizes the type.
     *
     * @param type the type value
     * @return the operation result
     */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "string";
        }
        String normalized = type.trim().toLowerCase();
        return switch (normalized) {
            case "integer", "int", "long" -> "integer";
            case "number", "double", "float", "decimal" -> "number";
            case "boolean", "bool" -> "boolean";
            case "array", "object", "string" -> normalized;
            default -> "string";
        };
    }

    /**
     * Stores the if present.
     *
     * @param map the map value
     * @param key the key value
     * @param value the value value
     */
    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
