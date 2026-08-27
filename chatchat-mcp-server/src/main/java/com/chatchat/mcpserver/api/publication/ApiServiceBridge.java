package com.chatchat.mcpserver.api.publication;

import com.chatchat.common.bridge.AbstractRuntimeBridge;
import com.chatchat.common.bridge.BridgeContract;
import com.chatchat.common.bridge.BridgeException;
import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.bridge.BridgeStatus;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.knowledge.StandardSearchResult;
import com.chatchat.common.knowledge.template.StandardTemplateKnowledge;
import com.chatchat.common.knowledge.template.TemplateServiceCall;
import com.chatchat.common.knowledge.template.TemplateServiceOperation;
import com.chatchat.common.knowledge.template.TemplateServicePort;
import com.chatchat.common.knowledge.template.TemplateServiceResult;
import com.chatchat.common.knowledge.template.TemplateServiceResultStatus;
import com.chatchat.common.knowledge.template.TemplateKnowledgeProtocol;
import com.chatchat.common.knowledge.template.TemplateResolutionEvent;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** API-template adapter implementing the transport-neutral template discovery port. */
@Component
@RequiredArgsConstructor
public class ApiServiceBridge extends AbstractRuntimeBridge<TemplateServiceCall, TemplateServiceResult>
    implements TemplateServicePort {
    public static final String BRIDGE_VERSION = "template_service_search.v1";
    public static final String QUERY_OPERATION = TemplateServiceOperation.SEARCH.operationCode();
    private static final BridgeContract CONTRACT = new BridgeContract(
        "template-service-search", BRIDGE_VERSION, KernelProtocolCatalog.TEMPLATE_SERVICE,
        Set.of(QUERY_OPERATION), KernelProtocolCatalog.SERVICE_BOUNDARY);

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
        TemplateServiceCall call = TemplateServiceCall.search(
            firstText(text(arguments.get("query")), text(arguments.get("intent"))),
            map(arguments.get("filters")), scope.attributes(), arguments);
        BridgeResponse<TemplateServiceResult> response = invoke(call, scope);
        if (response.successful()) return new Result(TemplateServicePayloadMapper.payload(response.data()), false);
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
    protected TemplateServiceResult exchangePayload(BridgeRequest<TemplateServiceCall> request) {
        TemplateServiceCall call = request.payload();
        if (call == null) {
            throw new BridgeException(BridgeStatus.REJECTED, "TEMPLATE_SERVICE_CALL_MISSING",
                "Template service call is required");
        }
        if (!request.operation().equals(call.operation().operationCode())) {
            throw new BridgeException(BridgeStatus.REJECTED, "TEMPLATE_SERVICE_OPERATION_MISMATCH",
                "Bridge operation does not match template service payload operation");
        }
        if (call.expired(System.currentTimeMillis())) {
            throw new BridgeException(BridgeStatus.REJECTED, "TEMPLATE_SERVICE_DEADLINE_EXCEEDED",
                "Template service call deadline has expired");
        }
        if (call.operation() != TemplateServiceOperation.SEARCH) {
            throw new BridgeException(BridgeStatus.REJECTED, "TEMPLATE_SERVICE_OPERATION_UNSUPPORTED",
                "Template service adapter does not implement operation: " + call.operation());
        }
        Map<String, Object> arguments = new LinkedHashMap<>(call.extensions());
        putIfPresent(arguments, "query", call.query());
        if (!call.filters().isEmpty()) arguments.put("filters", call.filters());
        return queryPayload(arguments, request.requestId());
    }

    private TemplateServiceResult queryPayload(Map<String, Object> rawArguments, String requestId) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String childToolName = TemplateQueryMcpToolPublisher.childToolName(arguments);
        Map<String, Object> discovery = childToolName.isBlank()
            ? templateDiscovery.query(discoveryArguments(arguments))
            : requireDynamicTemplateQueries().queryFromParent(
                childToolName, ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME, arguments);
        Map<String, Object> body = new LinkedHashMap<>(discovery);
        List<Map<String, Object>> candidates = maps(discovery.get("templates"));
        String query = firstText(text(arguments.get("query")), text(arguments.get("intent")),
            text(map(arguments.get("filters")).get("query")));
        int limit = number(discovery.get("limit"), candidates.size());
        boolean truncated = Boolean.TRUE.equals(discovery.get("possiblyTruncated"));
        StandardSearchResult<StandardTemplateKnowledge> searchResult = TemplateKnowledgeProtocol.searchResult(
            query, candidates, candidates.size(), limit, truncated,
            Map.of("source", ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME, "targetKind", "api_service"));
        boolean hasCandidates = !searchResult.hits().isEmpty();
        boolean malformedCandidates = searchResult.hits().size() < candidates.size();
        body.put("schemaVersion", "api_service_query_result.v1");
        body.put("searchSchemaVersion", StandardSearchResult.SCHEMA_VERSION);
        body.put("templateSchemaVersion", StandardTemplateKnowledge.SCHEMA_VERSION);
        body.put("searchResult", searchResult);
        List<TemplateResolutionEvent> events = malformedCandidates
            ? List.of(TemplateResolutionEvent.missingId(requestId,
                ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME))
            : hasCandidates ? List.of() : List.of(TemplateResolutionEvent.searchEmpty(requestId, query,
                ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME));
        body.put("status", hasCandidates ? "CANDIDATES_FOUND" : "NO_CANDIDATE");
        body.put("requiresModelReview", hasCandidates);
        body.put("bridgeManaged", true);
        body.put("bridgeTool", ApiMcpToolPublisher.BRIDGE_TOOL_NAME);
        body.put("executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        TemplateServiceResultStatus status = malformedCandidates ? TemplateServiceResultStatus.RESOLUTION_REQUIRED
            : hasCandidates ? TemplateServiceResultStatus.SUCCESS : TemplateServiceResultStatus.EMPTY;
        return new TemplateServiceResult(TemplateServiceResult.SCHEMA_VERSION, requestId,
            TemplateServiceOperation.SEARCH, status, body, events, false,
            Map.of("bridgeId", CONTRACT.bridgeId(), "bridgeVersion", CONTRACT.version()),
            System.currentTimeMillis());
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

    private int number(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return value == null ? fallback : Math.max(0, Integer.parseInt(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public record Result(Map<String, Object> body, boolean error) { }
}
