package com.chatchat.common.mcp.contract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects backward-incompatible MCP tool contract changes without depending on
 * one server implementation or generated deployment tool name.
 */
public final class McpSchemaCompatibilityChecker {

    public CompatibilityReport compare(Collection<ToolContract> baseline,
                                       Collection<ToolContract> candidate) {
        Map<String, ToolContract> oldTools = index(baseline);
        Map<String, ToolContract> newTools = index(candidate);
        List<BreakingChange> changes = new ArrayList<>();
        for (Map.Entry<String, ToolContract> entry : oldTools.entrySet()) {
            String toolName = entry.getKey();
            ToolContract next = newTools.get(toolName);
            if (next == null) {
                changes.add(new BreakingChange(toolName, "$", "TOOL_REMOVED",
                    "Published MCP tool was removed"));
                continue;
            }
            compareSchema(toolName, "input", entry.getValue().inputSchema(), next.inputSchema(), true, changes);
            compareSchema(toolName, "output", entry.getValue().outputSchema(), next.outputSchema(), false, changes);
        }
        return new CompatibilityReport(changes.isEmpty(), List.copyOf(changes));
    }

    private void compareSchema(String toolName,
                               String path,
                               Map<String, Object> baseline,
                               Map<String, Object> candidate,
                               boolean input,
                               List<BreakingChange> changes) {
        if (baseline.isEmpty()) {
            return;
        }
        if (candidate.isEmpty()) {
            changes.add(new BreakingChange(toolName, path, "SCHEMA_REMOVED", "Schema was removed"));
            return;
        }
        String oldType = text(baseline.get("type"));
        String newType = text(candidate.get("type"));
        if (oldType != null && newType != null && !oldType.equals(newType)) {
            changes.add(new BreakingChange(toolName, path, "TYPE_CHANGED",
                "Type changed from " + oldType + " to " + newType));
            return;
        }
        Set<String> oldEnum = strings(baseline.get("enum"));
        Set<String> newEnum = strings(candidate.get("enum"));
        if (!oldEnum.isEmpty() && !newEnum.isEmpty() && !newEnum.containsAll(oldEnum)) {
            changes.add(new BreakingChange(toolName, path, "ENUM_NARROWED",
                "Candidate enum no longer accepts every baseline value"));
        }
        Map<String, Map<String, Object>> oldProperties = properties(baseline.get("properties"));
        Map<String, Map<String, Object>> newProperties = properties(candidate.get("properties"));
        Set<String> oldRequired = strings(baseline.get("required"));
        Set<String> newRequired = strings(candidate.get("required"));
        if (input) {
            for (String required : newRequired) {
                if (!oldRequired.contains(required)) {
                    changes.add(new BreakingChange(toolName, path + "." + required,
                        "REQUIRED_INPUT_ADDED", "A new required input parameter was added"));
                }
            }
        } else {
            for (String required : oldRequired) {
                if (!newRequired.contains(required)) {
                    changes.add(new BreakingChange(toolName, path + "." + required,
                        "REQUIRED_OUTPUT_REMOVED", "A required output guarantee was removed"));
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> property : oldProperties.entrySet()) {
            Map<String, Object> next = newProperties.get(property.getKey());
            if (next == null) {
                changes.add(new BreakingChange(toolName, path + "." + property.getKey(),
                    input ? "INPUT_PROPERTY_REMOVED" : "OUTPUT_PROPERTY_REMOVED",
                    "Published schema property was removed"));
            } else {
                compareSchema(toolName, path + "." + property.getKey(), property.getValue(), next, input, changes);
            }
        }
        if (Boolean.TRUE.equals(baseline.get("additionalProperties"))
            && Boolean.FALSE.equals(candidate.get("additionalProperties"))) {
            changes.add(new BreakingChange(toolName, path, "ADDITIONAL_PROPERTIES_RESTRICTED",
                "Additional properties changed from allowed to rejected"));
        }
    }

    private Map<String, ToolContract> index(Collection<ToolContract> contracts) {
        Map<String, ToolContract> indexed = new LinkedHashMap<>();
        for (ToolContract contract : contracts == null ? List.<ToolContract>of() : contracts) {
            if (contract == null || contract.name() == null || contract.name().isBlank()) {
                continue;
            }
            if (indexed.putIfAbsent(contract.name(), contract) != null) {
                throw new IllegalArgumentException("Duplicate MCP tool contract: " + contract.name());
            }
        }
        return indexed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> properties(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item instanceof Map<?, ?> map) {
                Map<String, Object> property = new LinkedHashMap<>();
                map.forEach((nestedKey, nestedValue) -> {
                    if (nestedKey != null) {
                        property.put(String.valueOf(nestedKey), nestedValue);
                    }
                });
                result.put(String.valueOf(key), property);
            }
        });
        return result;
    }

    private Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        values.stream().filter(item -> item != null).map(String::valueOf).forEach(result::add);
        return result;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    public record ToolContract(String name,
                               Map<String, Object> inputSchema,
                               Map<String, Object> outputSchema) {
        public ToolContract {
            inputSchema = inputSchema == null ? Map.of() : new LinkedHashMap<>(inputSchema);
            outputSchema = outputSchema == null ? Map.of() : new LinkedHashMap<>(outputSchema);
        }
    }

    public record BreakingChange(String toolName, String path, String code, String message) {
    }

    public record CompatibilityReport(boolean compatible, List<BreakingChange> breakingChanges) {
    }
}
