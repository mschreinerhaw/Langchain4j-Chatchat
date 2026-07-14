package com.chatchat.mcpserver.tool;

import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentRuntimeGovernanceFactory {

    private static final List<String> DEFAULT_MASK_FIELDS = List.of("phone", "id_card", "account_no");

    private final ObjectMapper objectMapper;

    /**
     * Performs the meta for tool metadata operation.
     *
     * @param source the source value
     * @param sourceId the source id value
     * @param metadata the metadata value
     * @return the operation result
     */
    public Map<String, Object> metaForToolMetadata(String source, String sourceId, ToolMetadata metadata) {
        Map<String, Object> governance = defaultGovernance(
            firstText(metadata == null ? null : metadata.getCategory(), source),
            firstText(metadata == null ? null : metadata.getOperationType(), "read"),
            firstText(metadata == null ? null : metadata.getRiskLevel(), "low"),
            stringValue(metadata == null || metadata.getMetadata() == null ? null : metadata.getMetadata().get("dataScope")),
            metadata == null || metadata.isUserVisible()
        );
        if (metadata != null) {
            mergeInto(governance, "confirmation", metadata.getConfirmation());
            mergeInto(governance, "permission", metadata.getPermissions());
            mergeInto(governance, "input_policy", metadata.getInputPolicy());
            mergeInto(governance, "output_policy", metadata.getOutputPolicy());
        }
        Map<String, Object> meta = toMeta(source, sourceId, governance);
        if (metadata != null && metadata.getTimeoutMillis() != null) {
            putAlias(meta, "timeoutMillis", metadata.getTimeoutMillis());
            putAlias(meta, "timeout_ms", metadata.getTimeoutMillis());
        }
        if (metadata != null && metadata.getMetadata() != null) {
            Object applicability = metadata.getMetadata().get("applicability");
            if (applicability instanceof Map<?, ?>) {
                meta.put("applicability", applicability);
            }
            Object tags = metadata.getMetadata().get("tags");
            if (tags instanceof Iterable<?>) {
                meta.put("tags", tags);
            }
        }
        return meta;
    }

    /**
     * Performs the meta for api operation.
     *
     * @param config the config value
     * @return the operation result
     */
    public Map<String, Object> metaForApi(ApiServiceConfig config) {
        String operationType = operationTypeForMethod(config == null ? null : config.getMethod());
        String riskLevel = "read".equals(operationType) ? "medium" : "high";
        Map<String, Object> governance = defaultGovernance(
            "external_api",
            operationType,
            riskLevel,
            "external_service",
            true
        );
        mergeGovernanceJson(governance, config == null ? null : config.getGovernanceJson());
        return toMeta("external_api", config == null ? null : config.getId(), governance);
    }

    /**
     * Performs the meta for database query operation.
     *
     * @param config the config value
     * @return the operation result
     */
    public Map<String, Object> metaForDatabaseQuery(DatabaseQueryConfig config) {
        Map<String, Object> governance = defaultGovernance(
            "database_query",
            "read",
            "medium",
            "database_query",
            true
        );
        Map<String, Object> outputPolicy = childMap(governance, "output_policy");
        outputPolicy.put("max_rows_without_confirm", 100);
        if (config != null && config.getMaxRows() > 100) {
            childMap(governance, "confirmation").put("default", "ask_before_execute");
        }
        mergeGovernanceJson(governance, config == null ? null : config.getGovernanceJson());
        return toMeta("database_query_config", config == null ? null : config.getId(), governance);
    }

    /**
     * Converts the value to meta.
     *
     * @param source the source value
     * @param sourceId the source id value
     * @param governance the governance value
     * @return the converted meta
     */
    public Map<String, Object> toMeta(String source, String sourceId, Map<String, Object> governance) {
        Map<String, Object> normalized = normalizeGovernance(governance);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", source);
        if (sourceId != null && !sourceId.isBlank()) {
            meta.put("sourceId", sourceId);
        }
        meta.put("governance", normalized);

        putAlias(meta, "category", normalized.get("category"));
        putAlias(meta, "risk_level", normalized.get("risk_level"));
        putAlias(meta, "riskLevel", normalized.get("risk_level"));
        putAlias(meta, "operation_type", normalized.get("operation_type"));
        putAlias(meta, "operationType", normalized.get("operation_type"));
        putAlias(meta, "data_scope", normalized.get("data_scope"));
        putAlias(meta, "dataScope", normalized.get("data_scope"));
        putAlias(meta, "user_visible", normalized.get("user_visible"));
        putAlias(meta, "userVisible", normalized.get("user_visible"));
        putAlias(meta, "confirmation", normalized.get("confirmation"));
        putAlias(meta, "permission", normalized.get("permission"));
        putAlias(meta, "permissions", normalized.get("permission"));
        putAlias(meta, "input_policy", normalized.get("input_policy"));
        putAlias(meta, "inputPolicy", normalized.get("input_policy"));
        putAlias(meta, "output_policy", normalized.get("output_policy"));
        putAlias(meta, "outputPolicy", normalized.get("output_policy"));
        putAlias(meta, "audit", normalized.get("audit"));
        return meta;
    }

    /**
     * Performs the default governance operation.
     *
     * @param category the category value
     * @param operationType the operation type value
     * @param riskLevel the risk level value
     * @param dataScope the data scope value
     * @param userVisible the user visible value
     * @return the operation result
     */
    private Map<String, Object> defaultGovernance(String category,
                                                  String operationType,
                                                  String riskLevel,
                                                  String dataScope,
                                                  boolean userVisible) {
        String normalizedRisk = normalizeRisk(riskLevel);
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("category", firstText(category, "mcp_tool"));
        governance.put("operation_type", firstText(operationType, "read"));
        governance.put("risk_level", normalizedRisk);
        governance.put("data_scope", firstText(dataScope, "unknown"));
        governance.put("user_visible", userVisible);
        governance.put("confirmation", new LinkedHashMap<>(Map.of(
            "default", defaultActionForRisk(normalizedRisk),
            "allow_user_override", true
        )));
        governance.put("permission", new LinkedHashMap<>(Map.of("roles", new ArrayList<>())));
        governance.put("input_policy", new LinkedHashMap<>(Map.of(
            "must_show_parameters", true,
            "sensitive_params", new ArrayList<>(),
            "parameter_rules", new LinkedHashMap<>()
        )));
        governance.put("output_policy", new LinkedHashMap<>(Map.of(
            "mask_fields", new ArrayList<>(DEFAULT_MASK_FIELDS)
        )));
        governance.put("audit", new LinkedHashMap<>(Map.of(
            "enabled", true,
            "log_params", true,
            "log_result_summary", true
        )));
        return governance;
    }

    /**
     * Normalizes the governance.
     *
     * @param governance the governance value
     * @return the operation result
     */
    private Map<String, Object> normalizeGovernance(Map<String, Object> governance) {
        Map<String, Object> normalized = new LinkedHashMap<>(governance == null ? Map.of() : governance);
        alias(normalized, "risk_level", "riskLevel");
        alias(normalized, "operation_type", "operationType");
        alias(normalized, "data_scope", "dataScope");
        alias(normalized, "user_visible", "userVisible");
        alias(normalized, "input_policy", "inputPolicy");
        alias(normalized, "output_policy", "outputPolicy");
        if (normalized.containsKey("permissions") && !normalized.containsKey("permission")) {
            normalized.put("permission", normalized.get("permissions"));
        }
        String risk = normalizeRisk(stringValue(normalized.get("risk_level")));
        normalized.put("risk_level", risk);
        childMap(normalized, "confirmation").putIfAbsent("default", defaultActionForRisk(risk));
        childMap(normalized, "confirmation").putIfAbsent("allow_user_override", true);
        childMap(normalized, "audit").putIfAbsent("enabled", true);
        return normalized;
    }

    /**
     * Performs the merge governance json operation.
     *
     * @param target the target value
     * @param json the json value
     */
    private void mergeGovernanceJson(Map<String, Object> target, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> custom = objectMapper.readValue(json, new TypeReference<>() {});
            deepMerge(target, custom);
        } catch (Exception ignored) {
            // Config service validates this before persistence; keep defaults if legacy data is invalid.
        }
    }

    public Map<String, Object> toMeta(String source, String sourceId, Map<String, Object> governance,
                                      String governanceJson) {
        Map<String, Object> merged = new LinkedHashMap<>(governance == null ? Map.of() : governance);
        mergeGovernanceJson(merged, governanceJson);
        return toMeta(source, sourceId, merged);
    }

    /**
     * Performs the deep merge operation.
     *
     * @param target the target value
     * @param custom the custom value
     */
    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> custom) {
        if (custom == null || custom.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : custom.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map<?, ?> existingMap && entry.getValue() instanceof Map<?, ?> customMap) {
                deepMerge((Map<String, Object>) existingMap, (Map<String, Object>) customMap);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Performs the child map operation.
     *
     * @param root the root value
     * @param key the key value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> childMap(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> child = new LinkedHashMap<>();
        root.put(key, child);
        return child;
    }

    /**
     * Performs the merge into operation.
     *
     * @param governance the governance value
     * @param key the key value
     * @param value the value value
     */
    private void mergeInto(Map<String, Object> governance, String key, Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        deepMerge(childMap(governance, key), value);
    }

    /**
     * Performs the alias operation.
     *
     * @param map the map value
     * @param canonical the canonical value
     * @param alias the alias value
     */
    private void alias(Map<String, Object> map, String canonical, String alias) {
        if (!map.containsKey(canonical) && map.containsKey(alias)) {
            map.put(canonical, map.get(alias));
        }
    }

    /**
     * Performs the operation type for method operation.
     *
     * @param method the method value
     * @return the operation result
     */
    private String operationTypeForMethod(String method) {
        String value = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "GET" -> "read";
            case "DELETE" -> "delete";
            case "POST" -> "write";
            case "PUT", "PATCH" -> "write";
            default -> "write";
        };
    }

    /**
     * Performs the default action for risk operation.
     *
     * @param riskLevel the risk level value
     * @return the operation result
     */
    private String defaultActionForRisk(String riskLevel) {
        return switch (normalizeRisk(riskLevel)) {
            case "forbidden" -> "deny";
            case "medium", "high" -> "ask_before_execute";
            default -> "auto_execute";
        };
    }

    /**
     * Normalizes the risk.
     *
     * @param riskLevel the risk level value
     * @return the operation result
     */
    private String normalizeRisk(String riskLevel) {
        String value = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "low", "medium", "high", "forbidden" -> value;
            default -> "medium";
        };
    }

    /**
     * Performs the first text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Performs the string value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Stores the alias.
     *
     * @param meta the meta value
     * @param key the key value
     * @param value the value value
     */
    private void putAlias(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, value);
        }
    }
}
