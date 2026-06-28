package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AssetMetadataFactory {

    public static final String SCHEMA_VERSION = "asset_metadata.v1";
    public static final String ROUTING_POLICY_VERSION = "routing_policy.v1.2";
    public static final String ROUTING_TRACE_SCHEMA = "routing_trace.v1";

    private static final List<String> CONTEXT_KEYS = List.of(
        "env",
        "environment",
        "cluster",
        "namespace",
        "target",
        "targetType",
        "database",
        "databaseType",
        "dbType",
        "dialect",
        "databaseRole",
        "service",
        "labels"
    );

    private final ObjectMapper objectMapper;

    public Map<String, Object> sshAsset(SshHostConfig host) {
        List<String> allowedCommandTemplateIds = readArray(host.getAllowedCommandsJson());
        return assetEnvelope(
            "ssh_host",
            host.getId(),
            host.getName(),
            host.getTitle(),
            host.getToolName(),
            host.getEnvironment(),
            host.isEnabled(),
            labels(host.getRoutingLabelsJson(), host.getCapabilitiesJson(), host.getTags(), host.getGovernanceJson(),
                host.getName(), host.getToolName(), host.getTitle(), host.getEnvironment()),
            mapOf(
                "protocols", readArray(host.getCapabilitiesJson()),
                "operations", List.of("ssh.command_steps"),
                "allowedCommandTemplateIds", allowedCommandTemplateIds,
                "allowedCommandTemplates", templateRefs(allowedCommandTemplateIds)
            ),
            mapOf(
                "runtimeAction", host.getRuntimeAction(),
                "requiresTemplateAllowlist", true,
                "templateSelectionPolicy", templateSelectionPolicy(),
                "forbiddenConcreteTargetFields", List.of("hostId", "host", "hostname", "ip", "ipAddress", "address")
            )
        );
    }

    public Map<String, Object> sqlDatasource(SqlDatasourceConfig datasource) {
        List<String> allowedQueryTemplateIds = readArray(datasource.getAllowedTemplatesJson());
        return assetEnvelope(
            "sql_datasource",
            datasource.getId(),
            datasource.getName(),
            datasource.getTitle(),
            datasource.getToolName(),
            datasource.getEnvironment(),
            datasource.isEnabled(),
            labels(datasource.getRoutingLabelsJson(), datasource.getCapabilitiesJson(), null, datasource.getGovernanceJson(),
                datasource.getName(), datasource.getToolName(), datasource.getTitle(), datasource.getEnvironment(), databaseType(datasource)),
            mapOf(
                "protocols", readArray(datasource.getCapabilitiesJson()),
                "operations", List.of("sql.query"),
                "databaseType", databaseType(datasource),
                "allowedQueryTemplateIds", allowedQueryTemplateIds,
                "allowedQueryTemplates", templateRefs(allowedQueryTemplateIds),
                "allowedStatements", List.of("SELECT", "SHOW", "DESCRIBE", "EXPLAIN"),
                "allowedTables", readArray(datasource.getAllowedTablesJson())
            ),
            mapOf(
                "runtimeAction", datasource.getRuntimeAction(),
                "defaultTimeoutSeconds", datasource.getDefaultTimeoutSeconds(),
                "defaultMaxRows", datasource.getDefaultMaxRows(),
                "modelReturnedRowsLimit", 50,
                "templateSelectionPolicy", sqlTemplateSelectionPolicy(),
                "forbiddenConcreteTargetFields", List.of("datasourceId", "jdbcUrl", "url", "connectionString")
            )
        );
    }

    public Map<String, Object> httpEndpoint(HttpEndpointConfig endpoint) {
        return assetEnvelope(
            "http_endpoint",
            endpoint.getId(),
            endpoint.getName(),
            endpoint.getTitle(),
            endpoint.getToolName(),
            endpoint.getEnvironment(),
            endpoint.isEnabled(),
            labels(endpoint.getRoutingLabelsJson(), endpoint.getCapabilitiesJson(), endpoint.getTags(), endpoint.getGovernanceJson(),
                endpoint.getName(), endpoint.getToolName(), endpoint.getTitle(), endpoint.getEnvironment(), endpoint.getCategory()),
            mapOf(
                "protocols", readArray(endpoint.getCapabilitiesJson()),
                "operations", List.of("http.request"),
                "method", endpoint.getMethod(),
                "category", endpoint.getCategory()
            ),
            mapOf(
                "runtimeAction", endpoint.getRuntimeAction(),
                "timeoutMs", endpoint.getTimeoutMs(),
                "forbiddenConcreteTargetFields", List.of("endpointId", "url", "uri", "host", "hostname", "ip", "ipAddress", "address")
            )
        );
    }

    public Map<String, Object> gateway(String assetType,
                                       List<Map<String, Object>> assets,
                                       List<String> forbiddenConcreteTargetFields) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("kind", "asset_selection");
        metadata.put("assetType", assetType);
        metadata.put("routingPolicyVersion", ROUTING_POLICY_VERSION);
        metadata.put("routingContract", mapOf(
            "routingPolicyVersion", ROUTING_POLICY_VERSION,
            "contextObject", "executionContext",
            "contextKeys", CONTEXT_KEYS,
            "labelMatchPolicy", "environment narrows candidates; other logical labels must match asset routing labels; ambiguity is rejected",
            "selectionPolicy", selectionPolicy(),
            "routingDecisionMode", routingDecisionMode(),
            "routingTraceSchema", ROUTING_TRACE_SCHEMA,
            "onAmbiguity", "ask_user",
            "forbiddenConcreteTargetFields", forbiddenConcreteTargetFields
        ));
        metadata.put("selectionGuidance", mapOf(
            "useEnvironment", "Always include env/environment when known.",
            "useLabels", "Prefer cluster, namespace, service, target, databaseRole, and labels over concrete hosts or URLs.",
            "scoring", "Prefer exact environment matches, then exact label matches, then service/database role affinity.",
            "ambiguity", "If several assets could match with the same score, ask for a narrower executionContext instead of guessing."
        ));
        metadata.put("assets", assets == null ? List.of() : assets);
        return metadata;
    }

    private Map<String, Object> assetEnvelope(String assetType,
                                              String id,
                                              String name,
                                              String title,
                                              String toolName,
                                              String environment,
                                              boolean enabled,
                                              List<String> labels,
                                              Map<String, Object> capabilities,
                                              Map<String, Object> constraints) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("kind", "asset");
        metadata.put("assetType", assetType);
        metadata.put("asset", mapOf(
            "type", assetType,
            "id", id,
            "name", name,
            "displayName", firstText(title, name),
            "toolName", toolName,
            "environment", environment,
            "enabled", enabled
        ));
        metadata.put("routingHints", mapOf(
            "labels", labels,
            "contextKeys", CONTEXT_KEYS,
            "selectionScoreHints", selectionScoreHints(environment, labels)
        ));
        metadata.put("capabilities", capabilities);
        metadata.put("constraints", constraints);
        return metadata;
    }

    private Map<String, Object> selectionPolicy() {
        return mapOf(
            "type", "rule_score",
            "weights", mapOf(
                "envMatch", 0.50,
                "labelMatch", 0.30,
                "serviceAffinity", 0.15,
                "capabilityMatch", 0.05
            ),
            "nearTieScoreDelta", 0.05,
            "tieBreaker", "reject_as_ambiguous",
            "minimumRequired", List.of("asset.enabled == true", "forbiddenConcreteTargetFields absent")
        );
    }

    private Map<String, Object> templateSelectionPolicy() {
        return mapOf(
            "source", "ssh_template_query.templates[].templateId",
            "allowedSetPath", "capabilities.allowedCommandTemplates",
            "mustUseAllowedTemplate", true,
            "doNotInventTemplateNames", true,
            "onNoMatchingTemplate", "report that no existing authorized command template matches the request; do not suggest a new template name unless the user asks to administer templates"
        );
    }

    private Map<String, Object> sqlTemplateSelectionPolicy() {
        return mapOf(
            "source", "sql_datasource_template_query.templates[].templateId",
            "allowedSetPath", "capabilities.allowedQueryTemplates",
            "mustUseAllowedTemplate", true,
            "doNotInventTemplateNames", true,
            "rawSqlTemplateReturned", false,
            "onNoMatchingTemplate", "use explicit read-only SQL only when policy permits; otherwise report that no existing authorized SQL template matches the request"
        );
    }

    private List<Map<String, Object>> templateRefs(List<String> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            return List.of();
        }
        return templateIds.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> {
                String templateId = value.trim();
                return mapOf(
                    "templateId", templateId,
                    "templateCode", templateId
                );
            })
            .toList();
    }

    private Map<String, Object> routingDecisionMode() {
        return mapOf(
            "clear", "auto_select",
            "near_tie", "llm_rerank",
            "true_ambiguity", "ask_user"
        );
    }

    private Map<String, Object> selectionScoreHints(String environment, List<String> labels) {
        return mapOf(
            "environment", environment,
            "matchableLabels", labels == null ? List.of() : labels,
            "strongKeys", List.of("env", "environment", "cluster", "service", "target", "databaseType", "dbType", "dialect", "databaseRole"),
            "weakKeys", List.of("labels")
        );
    }

    private String databaseType(SqlDatasourceConfig datasource) {
        String value = datasource == null ? null : datasource.getDatabaseType();
        return value == null || value.isBlank() ? "generic" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> labels(String routingLabelsJson,
                                String capabilitiesJson,
                                String tags,
                                String governanceJson,
                                String... values) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (String value : values) {
            addLabel(labels, value);
        }
        readArray(routingLabelsJson).forEach(value -> addLabel(labels, value));
        readArray(capabilitiesJson).forEach(value -> addLabel(labels, value));
        addDelimited(labels, tags);
        addGovernanceLabels(labels, governanceJson);
        return new ArrayList<>(labels);
    }

    private void addGovernanceLabels(Set<String> labels, String governanceJson) {
        if (governanceJson == null || governanceJson.isBlank()) {
            return;
        }
        try {
            Map<String, Object> governance = objectMapper.readValue(governanceJson, new TypeReference<>() {});
            addLabel(labels, governance.get("cluster"));
            addLabel(labels, governance.get("namespace"));
            addLabel(labels, governance.get("target"));
            addLabel(labels, firstObject(governance, "targetType", "target_type"));
            addLabel(labels, firstObject(governance, "databaseRole", "database_role", "role"));
            addLabels(labels, governance.get("labels"));
            addLabels(labels, governance.get("roles"));
        } catch (Exception ignored) {
            // Invalid governance JSON is ignored here; config services own validation.
        }
    }

    private void addLabels(Set<String> labels, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addLabel(labels, item));
            return;
        }
        addLabel(labels, value);
    }

    private void addDelimited(Set<String> labels, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String item : value.split("[,;\\s]+")) {
            addLabel(labels, item);
        }
    }

    private void addLabel(Set<String> labels, Object value) {
        String normalized = value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (normalized != null && !normalized.isBlank()) {
            labels.add(normalized);
        }
    }

    private List<String> readArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object firstObject(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
