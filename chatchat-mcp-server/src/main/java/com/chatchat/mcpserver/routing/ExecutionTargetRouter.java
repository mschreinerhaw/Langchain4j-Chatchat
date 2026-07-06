package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionTargetRouter {

    private static final List<String> EXECUTION_CONTEXT_KEYS = List.of("mcpExecutionContext", "executionContext");
    private static final List<String> LOGICAL_CONTEXT_KEYS = List.of(
        "env",
        "environment",
        "cluster",
        "namespace",
        "target",
        "targetType",
        "target_type",
        "assetName",
        "asset_name",
        "name",
        "hostSelector",
        "host_selector",
        "database",
        "databaseType",
        "dbType",
        "dialect",
        "databaseRole",
        "database_role",
        "businessGroup",
        "business_group",
        "group",
        "groupName",
        "group_name",
        "service",
        "labels"
    );

    private final SshHostConfigService hostConfigService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final ExecutionTargetService executionTargetService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> routeLinuxCommand(Map<String, Object> arguments) {
        Map<String, Object> request = copyArguments(arguments);
        rejectConcreteTarget(request, "hostId", "host", "hostname", "ip", "ipAddress", "address");
        Map<String, Object> context = executionContext(request);
        requireExecutionContext(context, "linux_command_execute");
        SshHostConfig host = resolveSshHost(request);
        log.info("MCP execution routing selected SSH host: tool=linux_command_execute, context={}, hostId={}, assetName={}, hostTool={}, env={}",
            compactContext(context), host.getId(), host.getName(), host.getToolName(), host.getEnvironment());
        Map<String, Object> routingDecisionLog = routingTrace(
            "ssh_host",
            context,
            hostConfigService.listEnabled(),
            host.getId(),
            SshHostConfig::getId,
            hostConfig -> firstText(hostConfig.getName(), hostConfig.getToolName()),
            SshHostConfig::getEnvironment,
            this::sshLabels,
            "ssh",
            "linux_command_execute"
        );
        removeExecutionContext(request);
        request.put("hostId", host.getId());
        request.put("routedTarget", mapOf(
            "type", "ssh_host",
            "hostId", host.getId(),
            "hostName", firstText(host.getName(), host.getToolName()),
            "environment", host.getEnvironment()
        ));
        request.put("routingDecisionLog", routingDecisionLog);
        return request;
    }

    public Map<String, Object> routeSqlQuery(Map<String, Object> arguments) {
        Map<String, Object> request = copyArguments(arguments);
        rejectConcreteTarget(request, "datasourceId", "jdbcUrl", "url", "connectionString");
        Map<String, Object> context = executionContext(request, true);
        requireExecutionContext(context, "sql_query_execute");
        SqlDatasourceConfig datasource = resolveSqlDatasource(request, context);
        log.info("MCP execution routing selected SQL datasource: tool=sql_query_execute, context={}, datasourceId={}, assetName={}, datasourceTool={}, env={}",
            compactContext(context), datasource.getId(), firstText(datasource.getName(), datasource.getToolName()),
            datasource.getToolName(), datasource.getEnvironment());
        Map<String, Object> routingDecisionLog = routingTrace(
            "sql_datasource",
            context,
            datasourceConfigService.listEnabled(),
            datasource.getId(),
            SqlDatasourceConfig::getId,
            datasourceConfig -> firstText(datasourceConfig.getName(), datasourceConfig.getToolName()),
            SqlDatasourceConfig::getEnvironment,
            this::sqlLabels,
            "sql",
            "sql_query_execute"
        );
        removeExecutionContext(request);
        request.put("datasourceId", datasource.getId());
        request.put("routedTarget", mapOf(
            "type", "sql_datasource",
            "datasourceId", datasource.getId(),
            "datasourceName", firstText(datasource.getName(), datasource.getToolName()),
            "environment", datasource.getEnvironment()
        ));
        request.put("routingDecisionLog", routingDecisionLog);
        return request;
    }

    public RoutedHttpEndpoint routeHttpRequest(Map<String, Object> arguments) {
        Map<String, Object> request = copyArguments(arguments);
        rejectConcreteTarget(request, "endpointId", "url", "uri", "host", "hostname", "ip", "ipAddress", "address");
        Map<String, Object> context = executionContext(request);
        String template = firstText(text(request.get("template")), text(request.get("templateId")));
        template = firstText(template, text(request.get("template_id")));
        if (template != null) {
            context.putIfAbsent("assetName", template);
        }
        requireExecutionContext(context, "http_request_execute");
        HttpEndpointConfig endpoint = resolveHttpEndpoint(request);
        Map<String, Object> routingDecisionLog = routingTrace(
            "http_endpoint",
            context,
            httpEndpointConfigService.listEnabled(),
            endpoint.getId(),
            HttpEndpointConfig::getId,
            endpointConfig -> firstText(endpointConfig.getName(), endpointConfig.getToolName()),
            HttpEndpointConfig::getEnvironment,
            this::httpLabels,
            "http",
            "http_request_execute"
        );
        removeExecutionContext(request);
        request.put("routedTarget", mapOf(
            "type", "http_endpoint",
            "endpointId", endpoint.getId(),
            "endpointName", firstText(endpoint.getName(), endpoint.getToolName()),
            "environment", endpoint.getEnvironment()
        ));
        request.put("routingDecisionLog", routingDecisionLog);
        return new RoutedHttpEndpoint(endpoint, request);
    }

    public RoutedDatabaseQuery routeDatabaseQuery(Map<String, Object> arguments) {
        Map<String, Object> request = copyArguments(arguments);
        rejectConcreteTarget(request, "databaseQueryId", "queryId", "toolName");
        Map<String, Object> context = executionContext(request);
        requireExecutionContext(context, "database_query_execute");
        DatabaseQueryConfig query = resolveDatabaseQuery(request);
        Map<String, Object> routingDecisionLog = routingTrace(
            "database_query",
            context,
            databaseQueryConfigService.listEnabled(),
            query.getId(),
            DatabaseQueryConfig::getId,
            queryConfig -> firstText(queryConfig.getTitle(), queryConfig.getToolName()),
            ignored -> null,
            this::databaseQueryLabels,
            "database_query",
            "sql"
        );
        removeExecutionContext(request);
        request.put("routedTarget", mapOf(
            "type", "database_query",
            "databaseQueryId", query.getId(),
            "queryName", firstText(query.getTitle(), query.getToolName())
        ));
        request.put("routingDecisionLog", routingDecisionLog);
        return new RoutedDatabaseQuery(query, request);
    }

    public SshHostConfig resolveSshHost(Map<String, Object> arguments) {
        Map<String, Object> context = executionContext(arguments);
        List<SshHostConfig> candidates = hostConfigService.listEnabled();
        ExecutionTargetConfig target = resolveConfiguredTarget(context, ExecutionTargetService.ASSET_TYPE_SSH_HOST);
        if (target != null) {
            candidates = filterByConfiguredTarget(candidates, target, this::sshLabels, SshHostConfig::getEnvironment);
            return singleTarget(candidates, "SSH host", context);
        }
        candidates = filterByEnvironment(candidates, context, SshHostConfig::getEnvironment);
        candidates = filterByAssetName(candidates, context, this::sshLabels);
        if (hasAssetNameContext(context) && candidates.size() == 1) {
            log.info("MCP execution routing selected SSH host by unique assetName/env before optional logical labels: context={}, hostId={}, assetName={}, env={}",
                compactContext(context), candidates.get(0).getId(), firstText(candidates.get(0).getName(), candidates.get(0).getToolName()),
                candidates.get(0).getEnvironment());
            return candidates.get(0);
        }
        candidates = filterBySelector(candidates, context, this::sshLabels);
        candidates = filterByLogicalTokens(candidates, context, this::sshLabels,
            "cluster", "namespace", "target", "targetType", "target_type", "service");
        return singleTarget(candidates, "SSH host", context);
    }

    public SqlDatasourceConfig resolveSqlDatasource(Map<String, Object> arguments) {
        Map<String, Object> context = executionContext(arguments);
        return resolveSqlDatasource(arguments, context);
    }

    private SqlDatasourceConfig resolveSqlDatasource(Map<String, Object> arguments, Map<String, Object> context) {
        List<SqlDatasourceConfig> candidates = datasourceConfigService.listEnabled();
        ExecutionTargetConfig target = resolveConfiguredTarget(context, ExecutionTargetService.ASSET_TYPE_SQL_DATASOURCE);
        if (target != null) {
            candidates = filterByConfiguredTarget(candidates, target, this::sqlLabels, SqlDatasourceConfig::getEnvironment);
            return singleTarget(candidates, "SQL datasource", context);
        }
        candidates = filterByEnvironment(candidates, context, SqlDatasourceConfig::getEnvironment);
        candidates = filterByAssetName(candidates, context, this::sqlLabels);
        if (hasAssetNameContext(context) && candidates.size() == 1) {
            log.info("MCP execution routing selected SQL datasource by unique assetName/env before optional logical labels: context={}, datasourceId={}, assetName={}, env={}",
                compactContext(context), candidates.get(0).getId(), firstText(candidates.get(0).getName(), candidates.get(0).getToolName()),
                candidates.get(0).getEnvironment());
            return candidates.get(0);
        }
        candidates = filterByLogicalTokens(candidates, context, this::sqlLabels,
            "cluster", "namespace", "target", "targetType", "target_type", "database", "databaseRole",
            "database_role", "databaseType", "dbType", "dialect", "service");
        return singleTarget(candidates, "SQL datasource", context);
    }

    public HttpEndpointConfig resolveHttpEndpoint(Map<String, Object> arguments) {
        Map<String, Object> context = executionContext(arguments);
        List<HttpEndpointConfig> candidates = httpEndpointConfigService.listEnabled();
        ExecutionTargetConfig target = resolveConfiguredTarget(context, ExecutionTargetService.ASSET_TYPE_HTTP_ENDPOINT);
        if (target != null) {
            candidates = filterByConfiguredTarget(candidates, target, this::httpLabels, HttpEndpointConfig::getEnvironment);
            return singleTarget(candidates, "HTTP endpoint", context);
        }
        candidates = filterByEnvironment(candidates, context, HttpEndpointConfig::getEnvironment);
        candidates = filterByAssetName(candidates, context, this::httpLabels);
        candidates = filterByLogicalTokens(candidates, context, this::httpLabels,
            "cluster", "namespace", "target", "targetType", "target_type", "service");
        return singleTarget(candidates, "HTTP endpoint", context);
    }

    public DatabaseQueryConfig resolveDatabaseQuery(Map<String, Object> arguments) {
        Map<String, Object> context = executionContext(arguments);
        List<DatabaseQueryConfig> candidates = databaseQueryConfigService.listEnabled();
        ExecutionTargetConfig target = resolveConfiguredTarget(context, ExecutionTargetService.ASSET_TYPE_DATABASE_QUERY);
        if (target != null) {
            candidates = filterByConfiguredTarget(candidates, target, this::databaseQueryLabels, ignored -> null);
            return singleTarget(candidates, "database query", context);
        }
        candidates = filterByAssetName(candidates, context, this::databaseQueryLabels);
        candidates = filterByLogicalTokens(candidates, context, this::databaseQueryLabels,
            "cluster", "namespace", "target", "targetType", "target_type", "database", "databaseRole",
            "database_role", "businessGroup", "business_group", "group", "groupName", "group_name", "service");
        return singleTarget(candidates, "database query", context);
    }

    private ExecutionTargetConfig resolveConfiguredTarget(Map<String, Object> context, String assetType) {
        List<String> tokens = contextTokens(context,
            "target",
            "targetType",
            "target_type",
            "database",
            "databaseRole",
            "database_role",
            "businessGroup",
            "business_group",
            "group",
            "groupName",
            "group_name",
            "service"
        );
        if (tokens.isEmpty()) {
            return null;
        }
        List<ExecutionTargetConfig> targets = executionTargetService.listEnabledByAssetType(assetType).stream()
            .filter(target -> targetMatchesContext(target, tokens, context))
            .toList();
        if (targets.isEmpty()) {
            return null;
        }
        int bestPriority = targets.get(0).getPriority();
        List<ExecutionTargetConfig> best = targets.stream()
            .filter(target -> target.getPriority() == bestPriority)
            .toList();
        if (best.size() > 1) {
            throw new IllegalArgumentException("Ambiguous execution target model for context: "
                + compactContext(context) + ", matched=" + best.size());
        }
        return best.get(0);
    }

    private boolean targetMatchesContext(ExecutionTargetConfig target,
                                         List<String> tokens,
                                         Map<String, Object> context) {
        String env = firstContextText(context, "env", "environment");
        if (env != null && target.getEnvironment() != null && !equalsNormalized(target.getEnvironment(), env)) {
            return false;
        }
        Set<String> labels = targetLabels(target);
        return tokens.stream().anyMatch(token -> labels.contains(normalize(token)));
    }

    private <T> List<T> filterByConfiguredTarget(List<T> candidates,
                                                 ExecutionTargetConfig target,
                                                 LabelsExtractor<T> labelsExtractor,
                                                 ValueExtractor<T> environmentExtractor) {
        List<T> filtered = candidates;
        if (target.getEnvironment() != null) {
            filtered = filtered.stream()
                .filter(candidate -> equalsNormalized(environmentExtractor.value(candidate), target.getEnvironment()))
                .toList();
        }
        String selectorType = target.getSelectorType();
        String selectorValue = target.getSelectorValue();
        return filtered.stream()
            .filter(candidate -> matchesSelector(labelsExtractor.labels(candidate), selectorType, selectorValue))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executionContext(Map<String, Object> arguments) {
        return executionContext(arguments, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executionContext(Map<String, Object> arguments, boolean includeParameterContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return context;
        }
        for (String key : EXECUTION_CONTEXT_KEYS) {
            Object value = arguments.get(key);
            if (value instanceof Map<?, ?> map) {
                context.putAll((Map<String, Object>) map);
            }
        }
        Object parameters = arguments.get("parameters");
        if (includeParameterContext && parameters instanceof Map<?, ?> parameterMap) {
            for (String key : LOGICAL_CONTEXT_KEYS) {
                Object value = parameterMap.get(key);
                if (value != null) {
                    context.putIfAbsent(key, value);
                }
            }
        }
        for (String key : LOGICAL_CONTEXT_KEYS) {
            Object value = arguments.get(key);
            if (value != null) {
                context.putIfAbsent(key, value);
            }
        }
        return context;
    }

    private void rejectConcreteTarget(Map<String, Object> arguments, String... keys) {
        if (arguments == null || keys == null) {
            return;
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException(
                    "Concrete execution target is not allowed in gateway calls: " + key);
            }
        }
    }

    private void requireExecutionContext(Map<String, Object> context, String toolName) {
        if (!hasExecutionScope(context)) {
            throw new IllegalArgumentException("INVALID_EXECUTION_CONTEXT: " + toolName
                + " requires non-empty logical executionContext with at least one of "
                + "assetName, env, cluster, service, target, database, databaseRole, labels, or hostSelector");
        }
    }

    private boolean hasExecutionScope(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        return firstContextObject(context,
            "assetName",
            "asset_name",
            "env",
            "environment",
            "cluster",
            "namespace",
            "target",
            "targetType",
            "target_type",
            "hostSelector",
            "host_selector",
            "database",
            "databaseType",
            "dbType",
            "dialect",
            "databaseRole",
            "database_role",
            "service",
            "labels"
        ) != null;
    }

    private boolean hasAssetNameContext(Map<String, Object> context) {
        return firstContextObject(context, "assetName", "asset_name", "name") != null;
    }

    private void removeExecutionContext(Map<String, Object> request) {
        EXECUTION_CONTEXT_KEYS.forEach(request::remove);
        LOGICAL_CONTEXT_KEYS.forEach(request::remove);
    }

    private <T> List<T> filterByEnvironment(List<T> candidates,
                                            Map<String, Object> context,
                                            ValueExtractor<T> extractor) {
        String env = firstContextText(context, "env", "environment");
        if (env == null) {
            return candidates;
        }
        return candidates.stream()
            .filter(candidate -> equalsNormalized(extractor.value(candidate), env))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> filterBySelector(List<T> candidates,
                                         Map<String, Object> context,
                                         LabelsExtractor<T> extractor) {
        Object rawSelector = firstContextObject(context, "hostSelector", "host_selector");
        if (!(rawSelector instanceof Map<?, ?> selector)) {
            return candidates;
        }
        Object rawValue = firstObject((Map<String, Object>) selector, "value", "label", "name", "toolName");
        String value = text(rawValue);
        if (value == null) {
            return candidates;
        }
        String type = text(firstObject((Map<String, Object>) selector, "type", "match"));
        return candidates.stream()
            .filter(candidate -> matchesSelector(extractor.labels(candidate), type, value))
            .toList();
    }

    private <T> List<T> filterByAssetName(List<T> candidates,
                                          Map<String, Object> context,
                                          LabelsExtractor<T> extractor) {
        String assetName = firstContextText(context, "assetName", "asset_name", "name");
        if (assetName == null) {
            return candidates;
        }
        String normalized = normalize(assetName);
        return candidates.stream()
            .filter(candidate -> {
                Set<String> labels = extractor.labels(candidate);
                return labels.contains(normalized)
                    || labels.contains("name:" + normalized)
                    || labels.contains("title:" + normalized)
                    || labels.contains("tool:" + normalized);
            })
            .toList();
    }

    private <T> List<T> filterByLogicalTokens(List<T> candidates,
                                              Map<String, Object> context,
                                              LabelsExtractor<T> extractor,
                                              String... keys) {
        List<String> tokens = contextTokens(context, keys);
        if (tokens.isEmpty()) {
            return candidates;
        }
        List<T> filtered = candidates;
        for (String token : tokens) {
            filtered = filtered.stream()
                .filter(candidate -> extractor.labels(candidate).contains(normalize(token)))
                .toList();
        }
        return filtered;
    }

    private boolean matchesSelector(Set<String> labels, String type, String value) {
        String normalizedValue = normalize(value);
        String normalizedType = normalize(type);
        if (normalizedType == null || normalizedType.equals("label")) {
            return labels.contains(normalizedValue);
        }
        if (normalizedType.equals("name")) {
            return labels.contains("name:" + normalizedValue) || labels.contains(normalizedValue);
        }
        if (normalizedType.equals("toolname") || normalizedType.equals("tool_name")) {
            return labels.contains("tool:" + normalizedValue) || labels.contains(normalizedValue);
        }
        return labels.contains(normalizedValue);
    }

    private <T> T singleTarget(List<T> candidates, String targetType, Map<String, Object> context) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("No " + targetType + " matched execution context: " + compactContext(context));
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("Ambiguous " + targetType + " execution context: "
                + compactContext(context) + ", matched=" + candidates.size());
        }
        return candidates.get(0);
    }

    private <T> Map<String, Object> routingTrace(String assetType,
                                                 Map<String, Object> context,
                                                 List<T> candidates,
                                                 String winnerId,
                                                 ValueExtractor<T> idExtractor,
                                                 ValueExtractor<T> nameExtractor,
                                                 ValueExtractor<T> environmentExtractor,
                                                 LabelsExtractor<T> labelsExtractor,
                                                 String... capabilityTokens) {
        List<Map<String, Object>> candidateTraces = new ArrayList<>();
        if (candidates != null) {
            for (T candidate : candidates) {
                String assetId = idExtractor.value(candidate);
                Set<String> labels = labelsExtractor.labels(candidate);
                Map<String, Object> score = scoreBreakdown(
                    context,
                    environmentExtractor.value(candidate),
                    labels,
                    capabilityTokens
                );
                candidateTraces.add(mapOf(
                    "assetId", assetId,
                    "assetName", nameExtractor.value(candidate),
                    "environment", environmentExtractor.value(candidate),
                    "labels", new ArrayList<>(labels),
                    "scoreBreakdown", score,
                    "finalScore", score.get("finalScore"),
                    "selected", equalsNormalized(assetId, winnerId)
                ));
            }
        }
        return mapOf(
            "schemaVersion", AssetMetadataFactory.ROUTING_TRACE_SCHEMA,
            "routingPolicyVersion", AssetMetadataFactory.ROUTING_POLICY_VERSION,
            "assetType", assetType,
            "context", compactContextMap(context),
            "decisionMode", "clear",
            "winner", winnerId,
            "reason", "unique candidate selected after policy filters",
            "candidates", candidateTraces
        );
    }

    private Map<String, Object> scoreBreakdown(Map<String, Object> context,
                                               String candidateEnvironment,
                                               Set<String> labels,
                                               String... capabilityTokens) {
        double envMatch = environmentScore(context, candidateEnvironment);
        double labelMatch = tokenScore(labels, contextTokens(context,
            "cluster", "namespace", "target", "targetType", "target_type", "database", "databaseRole",
            "database_role", "databaseType", "dbType", "dialect", "businessGroup", "business_group", "group",
            "groupName", "group_name", "service"
        ));
        double serviceAffinity = tokenScore(labels, contextTokens(context,
            "service", "target", "targetType", "target_type", "database", "databaseRole", "database_role",
            "databaseType", "dbType", "dialect"
        ));
        double capabilityMatch = capabilityScore(labels, capabilityTokens);
        double finalScore = roundScore(
            envMatch * 0.50
                + labelMatch * 0.30
                + serviceAffinity * 0.15
                + capabilityMatch * 0.05
        );
        return mapOf(
            "envMatch", envMatch,
            "labelMatch", labelMatch,
            "serviceAffinity", serviceAffinity,
            "capabilityMatch", capabilityMatch,
            "finalScore", finalScore
        );
    }

    private double environmentScore(Map<String, Object> context, String candidateEnvironment) {
        String env = firstContextText(context, "env", "environment");
        if (env == null) {
            return 0.0;
        }
        return equalsNormalized(candidateEnvironment, env) ? 1.0 : 0.0;
    }

    private double tokenScore(Set<String> labels, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0.0;
        }
        long matched = tokens.stream()
            .filter(token -> labels.contains(normalize(token)))
            .count();
        return roundScore((double) matched / tokens.size());
    }

    private double capabilityScore(Set<String> labels, String... capabilityTokens) {
        if (capabilityTokens == null || capabilityTokens.length == 0) {
            return 0.0;
        }
        for (String token : capabilityTokens) {
            if (labels.contains(normalize(token))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private double roundScore(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private Set<String> sshLabels(SshHostConfig host) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, host.getEnvironment());
        addLabel(labels, host.getName());
        addLabel(labels, "name:" + host.getName());
        addLabel(labels, host.getToolName());
        addLabel(labels, "tool:" + host.getToolName());
        addLabel(labels, host.getTitle());
        addLabel(labels, "title:" + host.getTitle());
        addJsonLabels(labels, host.getRoutingLabelsJson());
        addJsonLabels(labels, host.getCapabilitiesJson());
        addDelimited(labels, host.getTags());
        addGovernanceLabels(labels, host.getGovernanceJson());
        return labels;
    }

    private Set<String> sqlLabels(SqlDatasourceConfig datasource) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, datasource.getEnvironment());
        addLabel(labels, databaseType(datasource));
        addLabel(labels, datasource.getName());
        addLabel(labels, "name:" + datasource.getName());
        addLabel(labels, datasource.getToolName());
        addLabel(labels, "tool:" + datasource.getToolName());
        addLabel(labels, datasource.getTitle());
        addLabel(labels, "title:" + datasource.getTitle());
        addJsonLabels(labels, datasource.getRoutingLabelsJson());
        addJsonLabels(labels, datasource.getCapabilitiesJson());
        addGovernanceLabels(labels, datasource.getGovernanceJson());
        return labels;
    }

    private Set<String> httpLabels(HttpEndpointConfig endpoint) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, endpoint.getEnvironment());
        addLabel(labels, endpoint.getName());
        addLabel(labels, "name:" + endpoint.getName());
        addLabel(labels, endpoint.getToolName());
        addLabel(labels, "tool:" + endpoint.getToolName());
        addLabel(labels, endpoint.getTitle());
        addLabel(labels, "title:" + endpoint.getTitle());
        addLabel(labels, endpoint.getCategory());
        addJsonLabels(labels, endpoint.getRoutingLabelsJson());
        addJsonLabels(labels, endpoint.getCapabilitiesJson());
        addDelimited(labels, endpoint.getTags());
        addGovernanceLabels(labels, endpoint.getGovernanceJson());
        return labels;
    }

    private Set<String> databaseQueryLabels(DatabaseQueryConfig query) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, query.getTitle());
        addLabel(labels, "title:" + query.getTitle());
        addLabel(labels, query.getToolName());
        addLabel(labels, "tool:" + query.getToolName());
        addLabel(labels, query.getBusinessGroup());
        addLabel(labels, "group:" + query.getBusinessGroup());
        addLabel(labels, query.getBusinessGroupName());
        addLabel(labels, "group_name:" + query.getBusinessGroupName());
        addDelimited(labels, query.getBusinessGroupDescription());
        addLabel(labels, query.getDatasourceId());
        addJsonLabels(labels, query.getRoutingLabelsJson());
        addJsonLabels(labels, query.getCapabilitiesJson());
        addGovernanceLabels(labels, query.getGovernanceJson());
        return labels;
    }

    private Set<String> targetLabels(ExecutionTargetConfig target) {
        Set<String> labels = new LinkedHashSet<>();
        addLabel(labels, target.getTargetKey());
        addLabel(labels, target.getName());
        addLabel(labels, target.getSelectorValue());
        addLabel(labels, target.getEnvironment());
        if (target.getLabelsJson() != null && !target.getLabelsJson().isBlank()) {
            try {
                List<String> configured = objectMapper.readValue(target.getLabelsJson(), new TypeReference<>() {});
                configured.forEach(item -> addLabel(labels, item));
            } catch (Exception ignored) {
                // ExecutionTargetService validates labelsJson; routing ignores invalid stale values defensively.
            }
        }
        return labels;
    }

    private String databaseType(SqlDatasourceConfig datasource) {
        String value = datasource == null ? null : datasource.getDatabaseType();
        return value == null || value.isBlank() ? "generic" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void addDelimited(Set<String> labels, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String item : value.split("[,;\\s]+")) {
            addLabel(labels, item);
            if (item.contains(":")) {
                addLabel(labels, item.substring(item.indexOf(':') + 1));
            }
        }
    }

    private void addJsonLabels(Set<String> labels, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            List<String> configured = objectMapper.readValue(json, new TypeReference<>() {});
            configured.forEach(item -> addLabel(labels, item));
        } catch (Exception ignored) {
            // Asset services validate protocol JSON; routing ignores invalid stale values defensively.
        }
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
            // Invalid governance JSON is handled by config validation paths; routing treats it as no extra labels.
        }
    }

    private List<String> contextTokens(Map<String, Object> context, String... keys) {
        List<String> tokens = new ArrayList<>();
        if (context == null || keys == null) {
            return tokens;
        }
        for (String key : keys) {
            Object value = context.get(key);
            if (value instanceof List<?> list) {
                list.forEach(item -> addToken(tokens, item));
            } else {
                addToken(tokens, value);
            }
        }
        Object labels = context.get("labels");
        if (labels instanceof List<?> list) {
            list.forEach(item -> addToken(tokens, item));
        }
        return tokens.stream().distinct().toList();
    }

    private void addLabels(Set<String> labels, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addLabel(labels, item));
            return;
        }
        addLabel(labels, value);
    }

    private void addLabel(Set<String> labels, Object value) {
        String normalized = normalize(value == null ? null : String.valueOf(value));
        if (normalized != null) {
            labels.add(normalized);
        }
    }

    private void addToken(List<String> tokens, Object value) {
        String token = normalize(value == null ? null : String.valueOf(value));
        if (token != null) {
            tokens.add(token);
        }
    }

    private Map<String, Object> copyArguments(Map<String, Object> arguments) {
        return new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
    }

    private String firstContextText(Map<String, Object> context, String... keys) {
        return text(firstContextObject(context, keys));
    }

    private Object firstContextObject(Map<String, Object> context, String... keys) {
        if (context == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
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

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean equalsNormalized(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        return left != null && left.equals(right);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String compactContext(Map<String, Object> context) {
        return compactContextMap(context).toString();
    }

    private Map<String, Object> compactContextMap(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : LOGICAL_CONTEXT_KEYS) {
            Object value = context.get(key);
            if (value != null) {
                compact.put(key, value);
            }
        }
        return compact;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    @FunctionalInterface
    private interface ValueExtractor<T> {
        String value(T target);
    }

    @FunctionalInterface
    private interface LabelsExtractor<T> {
        Set<String> labels(T target);
    }

    public record RoutedHttpEndpoint(HttpEndpointConfig endpoint, Map<String, Object> arguments) {
    }

    public record RoutedDatabaseQuery(DatabaseQueryConfig query, Map<String, Object> arguments) {
    }
}
