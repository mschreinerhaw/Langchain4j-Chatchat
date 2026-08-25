package com.chatchat.mcpserver.api;

import com.chatchat.common.bridge.AbstractRuntimeBridge;
import com.chatchat.common.bridge.BridgeContract;
import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.mcpserver.templatepublication.TemplateQueryMcpToolPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only facade over the existing API template discovery protocol. */
@Component
@RequiredArgsConstructor
public class ApiServiceBridge extends AbstractRuntimeBridge<Map<String, Object>, Map<String, Object>> {
    public static final String BRIDGE_VERSION = "api_service_bridge.v1";
    public static final String QUERY_OPERATION = "api.service/query";
    private static final BridgeContract CONTRACT = new BridgeContract(
        "api-service", BRIDGE_VERSION, KernelProtocolCatalog.API_BRIDGE,
        Set.of(QUERY_OPERATION), KernelProtocolCatalog.API_BOUNDARY);

    private final ApiTemplateDiscoveryMcpToolPublisher templateDiscovery;
    private TemplateQueryMcpToolPublisher dynamicTemplateQueries;

    @Autowired
    void configureDynamicTemplateQueries(TemplateQueryMcpToolPublisher dynamicTemplateQueries) {
        this.dynamicTemplateQueries = dynamicTemplateQueries;
    }

    public Result query(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String requestId = text(arguments.get("requestId"));
        KernelDataScope scope = new KernelDataScope(
            firstText(text(arguments.get("tenantId")), "system"), text(arguments.get("userId")),
            firstText(requestId, UUID.randomUUID().toString()), text(arguments.get("conversationId")),
            text(arguments.get("runId")), firstText(text(arguments.get("environment")), text(arguments.get("env"))),
            Map.of("source", "api-service-bridge"));
        BridgeResponse<Map<String, Object>> response = exchange(BridgeRequest.of(CONTRACT, QUERY_OPERATION,
            scope, Set.of(KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE), arguments));
        if (response.successful()) return new Result(response.data(), false);
        return new Result(Map.of(
            "schemaVersion", "api_service_query_result.v1",
            "status", "BRIDGE_FAILED",
            "errorCode", response.errorCode(),
            "errorMessage", response.errorMessage(),
            "requestId", response.requestId()), true);
    }

    @Override
    public BridgeContract bridgeContract() {
        return CONTRACT;
    }

    @Override
    protected Map<String, Object> exchangePayload(BridgeRequest<Map<String, Object>> request) {
        return queryPayload(request.payload());
    }

    private Map<String, Object> queryPayload(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String childToolName = TemplateQueryMcpToolPublisher.childToolName(arguments);
        Map<String, Object> discovery = childToolName.isBlank()
            ? templateDiscovery.query(discoveryArguments(arguments))
            : requireDynamicTemplateQueries().queryFromParent(
                childToolName, ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME, arguments);
        Map<String, Object> body = new LinkedHashMap<>(discovery);
        List<Map<String, Object>> candidates = maps(discovery.get("templates"));
        body.put("schemaVersion", "api_service_query_result.v1");
        body.put("status", candidates.isEmpty() ? "NO_CANDIDATE" : "CANDIDATES_FOUND");
        body.put("requiresModelReview", !candidates.isEmpty());
        body.put("bridgeManaged", true);
        body.put("bridgeTool", ApiMcpToolPublisher.BRIDGE_TOOL_NAME);
        body.put("executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        return Map.copyOf(body);
    }

    private TemplateQueryMcpToolPublisher requireDynamicTemplateQueries() {
        if (dynamicTemplateQueries == null) {
            throw new IllegalStateException("Dynamic template query routing is unavailable");
        }
        return dynamicTemplateQueries;
    }

    private Map<String, Object> discoveryArguments(Map<String, Object> arguments) {
        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> filters = map(arguments.get("filters"));
        putIfPresent(filters, "query", firstText(text(arguments.get("query")), text(arguments.get("intent"))));
        if (!filters.isEmpty()) query.put("filters", filters);
        putIfPresent(query, "templateIds", arguments.get("templateIds"));
        putIfPresent(query, "excludeTemplateIds", arguments.get("excludeTemplateIds"));
        putIfPresent(query, "bilingualIntent", arguments.get("bilingualIntent"));
        putIfPresent(query, "bilingualQuery", arguments.get("bilingualQuery"));
        putIfPresent(query, "intentZh", arguments.get("intentZh"));
        putIfPresent(query, "intentEn", arguments.get("intentEn"));
        putIfPresent(query, "limit", arguments.get("limit"));
        return query;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) target.put(key, value);
    }

    private String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public record Result(Map<String, Object> body, boolean error) { }
}
