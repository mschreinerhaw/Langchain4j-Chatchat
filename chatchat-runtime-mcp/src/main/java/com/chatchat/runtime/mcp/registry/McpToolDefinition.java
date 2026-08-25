package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.mcp.contract.McpToolContract;
import com.chatchat.common.mcp.contract.McpToolContractValidator;
import com.chatchat.common.mcp.contract.McpToolGovernance;
import com.chatchat.common.tool.ToolParameter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record McpToolDefinition(
    String name,
    String displayName,
    String description,
    String capabilityCode,
    String provider,
    List<ToolParameter> parameters,
    boolean enabledByDefault,
    boolean agentCallable,
    Duration timeout,
    String contractVersion,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    McpToolGovernance governance
) implements McpToolContract {
    /** Legacy compatibility constructor. New tools should use the canonical constructor and declare governance. */
    @Deprecated(forRemoval = false)
    public McpToolDefinition(String name, String displayName, String description, String capabilityCode,
                             String provider, List<ToolParameter> parameters, boolean enabledByDefault,
                             boolean agentCallable, Duration timeout) {
        this(name, displayName, description, capabilityCode, provider, parameters, enabledByDefault,
            agentCallable, timeout, McpToolContractValidator.CONTRACT_VERSION,
            schema(parameters), Map.of(), McpToolGovernance.readOnly());
    }

    public McpToolDefinition {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        contractVersion = contractVersion == null || contractVersion.isBlank()
            ? McpToolContractValidator.CONTRACT_VERSION : contractVersion.trim();
        inputSchema = inputSchema == null ? schema(parameters) : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        if (governance == null) throw new IllegalArgumentException("MCP tool governance is required");
    }

    @Override public String toolName() { return name; }

    private static Map<String, Object> schema(List<ToolParameter> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = parameters == null ? List.of() : parameters.stream()
            .filter(ToolParameter::isRequired).map(ToolParameter::getName).toList();
        if (parameters != null) {
            for (ToolParameter parameter : parameters) {
                if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) continue;
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", parameter.getType() == null ? "string" : parameter.getType());
                if (parameter.getDescription() != null) property.put("description", parameter.getDescription());
                properties.put(parameter.getName(), Map.copyOf(property));
            }
        }
        return Map.of("type", "object", "properties", Map.copyOf(properties), "required", required);
    }
}
