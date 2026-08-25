package com.chatchat.integration.mcp.model;

import com.chatchat.common.mcp.contract.McpToolContract;
import com.chatchat.common.mcp.contract.McpToolContractValidator;
import com.chatchat.common.mcp.contract.McpToolGovernance;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record McpToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    String category,
    String riskLevel,
    String operationType,
    String runtimeLevel,
    Boolean userVisible,
    Map<String, Object> confirmation,
    Map<String, Object> permissions,
    Map<String, Object> inputPolicy,
    Map<String, Object> outputPolicy,
    Long timeoutMillis,
    Map<String, Object> meta
) implements McpToolContract {
    /**
     * Creates a new McpToolDefinition instance.
     *
     * @param name the name value
     * @param description the description value
     * @param inputSchema the input schema value
     */
    public McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, null, null, null, null, null, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of());
    }

    public McpToolDefinition {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        confirmation = confirmation == null ? Map.of() : Map.copyOf(confirmation);
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
        inputPolicy = inputPolicy == null ? Map.of() : Map.copyOf(inputPolicy);
        outputPolicy = outputPolicy == null ? Map.of() : Map.copyOf(outputPolicy);
        meta = meta == null ? Map.of() : new LinkedHashMap<>(meta);
    }

    @Override public String toolName() { return name; }
    @Override public String displayName() { return text(meta.get("title"), name); }
    @Override public String capabilityCode() { return text(meta.get("capabilityCode"), category, "external"); }
    @Override public String provider() { return text(meta.get("providerModule"), meta.get("serviceId"), "external-mcp"); }
    @Override public String contractVersion() {
        return text(meta.get("contractVersion"), McpToolContractValidator.CONTRACT_VERSION);
    }
    @Override public Map<String, Object> outputSchema() {
        return map(first(meta.get("outputSchema"), meta.get("resultSchema"), meta.get("output_schema")));
    }
    @Override public McpToolGovernance governance() {
        return new McpToolGovernance(text(riskLevel, "low"), text(operationType, "read"),
            text(runtimeLevel, "readonly"), confirmationRequired(), permissions, inputPolicy,
            outputPolicy, null);
    }
    @Override public Duration timeout() {
        return Duration.ofMillis(timeoutMillis == null || timeoutMillis <= 0 ? 30_000 : timeoutMillis);
    }

    private boolean confirmationRequired() {
        Object value = first(confirmation.get("required"), confirmation.get("confirmationRequired"));
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static Object first(Object... values) {
        if (values != null) for (Object value : values) if (value != null) return value;
        return null;
    }

    private static String text(Object... values) {
        if (values != null) {
            for (Object value : values) {
                if (value == null) continue;
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) return text;
            }
        }
        return null;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return Map.copyOf(result);
    }
}
