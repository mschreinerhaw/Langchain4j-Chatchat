package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.common.tool.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/** Field groups supplied by the active database-backed MCP tool contract. */
public record McpArgumentBindingFieldPolicy(List<String> logicalContextKeys,
                                     List<String> concreteTargetFields,
                                     List<String> rawExecutionFields,
                                     List<String> targetKindFields,
                                     List<String> filterProtocolFields,
                                     Map<String, String> filterFieldAliases,
                                     List<String> logicalFilterFields,
                                     String identityField,
                                     String semanticField,
                                     Map<String, String> executionProtocolBindings,
                                     Map<String, List<String>> executionValidationFields,
                                     Map<String, List<String>> requiredParametersByTemplateSuffix,
                                     List<String> assetIdentityForbiddenParameterFields,
                                     Map<String, List<String>> requiredExecutionContextFields) {

    public static McpArgumentBindingFieldPolicy from(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) return null;
        Map<String, Object> extra = metadata.getMetadata();
        Object value = extra.get("argumentBindingPolicy");
        if (value == null && extra.get("mcpToolMeta") instanceof Map<?, ?> mcpMeta) {
            value = mcpMeta.get("argumentBindingPolicy");
        }
        if (!(value instanceof Map<?, ?> policy)) return null;
        List<String> logical = fields(policy, "logicalContextKeys");
        List<String> concrete = fields(policy, "concreteTargetFields");
        List<String> raw = fields(policy, "rawExecutionFields");
        List<String> target = fields(policy, "targetKindFields");
        List<String> protocol = fields(policy, "filterProtocolFields");
        List<String> filterFields = fields(policy, "logicalFilterFields");
        Object aliasValue = policy.get("filterFieldAliases");
        Object executionValue = policy.get("executionProtocolBindings");
        Object validationValue = policy.get("executionValidationFields");
        Object requiredParametersValue = policy.get("requiredParametersByTemplateSuffix");
        Object contextValue = policy.get("requiredExecutionContextFields");
        if (logical == null || concrete == null || raw == null || target == null || protocol == null
            || filterFields == null || !(aliasValue instanceof Map<?, ?> aliases)
            || !(executionValue instanceof Map<?, ?> bindings)
            || !(validationValue instanceof Map<?, ?> validation)
            || !(requiredParametersValue instanceof Map<?, ?> requiredParameters)
            || !(contextValue instanceof Map<?, ?> contextRequirements)) return null;
        List<String> forbiddenIdentityBindings = fields(policy, "assetIdentityForbiddenParameterFields");
        if (forbiddenIdentityBindings == null) return null;
        Map<String, String> normalizedAliases = new LinkedHashMap<>();
        aliases.forEach((key, item) -> {
            if (key instanceof String name && item instanceof String canonical && !canonical.isBlank()) {
                normalizedAliases.put(normalizeKey(name), canonical.trim());
            }
        });
        String identity = text(policy.get("identityField"));
        String semantic = text(policy.get("semanticField"));
        if (identity == null || semantic == null) return null;
        Map<String, String> executionBindings = new LinkedHashMap<>();
        bindings.forEach((key, item) -> {
            if (key instanceof String name && item instanceof String mode && !mode.isBlank()) {
                executionBindings.put(name.trim().toLowerCase(Locale.ROOT), mode.trim());
            }
        });
        Map<String, List<String>> validationFields = new LinkedHashMap<>();
        validation.forEach((key, item) -> {
            if (key instanceof String mode && item instanceof List<?> rawFields && !rawFields.isEmpty()
                && rawFields.stream().allMatch(field -> field instanceof String text && !text.isBlank())) {
                validationFields.put(mode.trim(), rawFields.stream()
                    .map(field -> String.valueOf(field).trim().toLowerCase(Locale.ROOT)).distinct().toList());
            }
        });
        if (validationFields.isEmpty()
            || executionBindings.values().stream().anyMatch(mode -> !validationFields.containsKey(mode))) return null;
        Map<String, List<String>> requiredBySuffix = new LinkedHashMap<>();
        requiredParameters.forEach((key, item) -> {
            if (key instanceof String suffix && item instanceof List<?> rawFields && !rawFields.isEmpty()
                && rawFields.stream().allMatch(field -> field instanceof String text && !text.isBlank())) {
                requiredBySuffix.put(suffix.toUpperCase(Locale.ROOT), rawFields.stream()
                    .map(String::valueOf).toList());
            }
        });
        Map<String, List<String>> requiredContext = new LinkedHashMap<>();
        contextRequirements.forEach((key, item) -> {
            if (key instanceof String mode && item instanceof List<?> rawFields && !rawFields.isEmpty()
                && rawFields.stream().allMatch(field -> field instanceof String text && !text.isBlank())) {
                requiredContext.put(mode.trim(), rawFields.stream().map(String::valueOf).toList());
            }
        });
        return new McpArgumentBindingFieldPolicy(logical, concrete, raw, target, protocol,
            Map.copyOf(normalizedAliases), filterFields, identity, semantic,
            Map.copyOf(executionBindings), Map.copyOf(validationFields),
            Map.copyOf(requiredBySuffix), forbiddenIdentityBindings, Map.copyOf(requiredContext));
    }

    private static List<String> fields(Map<?, ?> policy, String key) {
        if (!(policy.get(key) instanceof List<?> raw) || raw.isEmpty()) return null;
        if (raw.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) return null;
        return raw.stream().map(String::valueOf).distinct().toList();
    }

    public String canonicalFilterField(String field) {
        return filterFieldAliases.getOrDefault(normalizeKey(field), field == null ? "" : field.trim());
    }

    public Set<String> validationFields(String bindingMode) {
        return Set.copyOf(executionValidationFields.getOrDefault(bindingMode, List.of()));
    }

    public List<String> requiredParametersForTemplate(Object templateId) {
        if (templateId == null) return List.of();
        String normalized = String.valueOf(templateId).trim().toUpperCase(Locale.ROOT);
        return requiredParametersByTemplateSuffix.entrySet().stream()
            .filter(entry -> normalized.endsWith(entry.getKey()))
            .flatMap(entry -> entry.getValue().stream()).distinct().toList();
    }

    public List<String> requiredExecutionContextFields(String bindingMode) {
        return requiredExecutionContextFields.getOrDefault(bindingMode, List.of());
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
