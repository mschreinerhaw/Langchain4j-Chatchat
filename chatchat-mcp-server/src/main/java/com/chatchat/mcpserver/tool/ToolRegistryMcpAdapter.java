package com.chatchat.mcpserver.tool;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class ToolRegistryMcpAdapter {

    private final ObjectMapper objectMapper;
    private final ChatChatMcpServerProperties properties;
    private final AgentRuntimeGovernanceFactory governanceFactory;

    public List<McpServerFeatures.SyncToolSpecification> toToolSpecifications(ToolRegistry toolRegistry) {
        return toolRegistry.getAllToolNames().stream()
            .sorted(Comparator.naturalOrder())
            .map(name -> toToolSpecification(toolRegistry, name))
            .flatMap(List::stream)
            .toList();
    }

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

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(name)
            .title(metadata == null ? name : metadata.getTitle())
            .description(description(toolRegistry, name, metadata))
            .inputSchema(toInputSchema(metadata))
            .meta(governanceFactory.metaForToolMetadata("builtin_tool", name, metadata))
            .build();

        return List.of(McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> invokeTool(toolRegistry, name, metadata, request))
            .build());
    }

    private boolean isExcluded(String name) {
        if (name == null || properties.getExcludedToolNames() == null) {
            return false;
        }
        return properties.getExcludedToolNames().stream()
            .filter(excluded -> excluded != null && !excluded.isBlank())
            .anyMatch(excluded -> excluded.equalsIgnoreCase(name));
    }

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
        return toCallToolResult(output);
    }

    private McpSchema.JsonSchema toInputSchema(ToolMetadata metadata) {
        if (metadata == null || metadata.getParameters() == null || metadata.getParameters().isEmpty()) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        }

        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter parameter : metadata.getParameters()) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            schemaProperties.put(parameter.getName(), toPropertySchema(parameter));
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }

        return new McpSchema.JsonSchema("object", schemaProperties, required, false, null, null);
    }

    private Map<String, Object> toPropertySchema(ToolParameter parameter) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", normalizeType(parameter.getType()));
        putIfPresent(property, "description", parameter.getDescription());
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

    private McpSchema.CallToolResult toCallToolResult(ToolOutput output) {
        boolean failed = output == null || !output.isSuccess();
        Object structuredContent = structuredContent(output, failed);
        String text = failed ? errorText(output) : successText(output);

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

    private String description(ToolRegistry toolRegistry, String name, ToolMetadata metadata) {
        if (metadata != null && metadata.getDescription() != null && !metadata.getDescription().isBlank()) {
            return metadata.getDescription();
        }
        ToolRegistry.Tool simpleTool = toolRegistry.getTool(name);
        if (simpleTool != null && simpleTool.getDescription() != null && !simpleTool.getDescription().isBlank()) {
            return simpleTool.getDescription();
        }
        return "ChatChat tool: " + name;
    }

    private String successText(ToolOutput output) {
        if (output == null) {
            return "";
        }
        if (output.getMessage() != null && !output.getMessage().isBlank()) {
            return output.getMessage();
        }
        return stringify(output.getData());
    }

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

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
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

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
