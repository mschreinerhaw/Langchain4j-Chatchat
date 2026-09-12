package com.chatchat.mcpserver.ops.discovery;

import com.chatchat.common.tool.ToolProtocolDriverContract;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.routing.asset.AssetDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.routing.asset.AssetDiscoveryService;
import com.chatchat.mcpserver.search.query.SearchQueryTokenizer;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes domain-specific read-only discovery bridges. The Java implementation is shared, but
 * each MCP contract retains its own business meaning, authorization scope and execution tool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsCapabilityBridgePublisher implements com.chatchat.mcpserver.tool.McpToolContributor {
    private static final double AUTO_ASSET_MIN_SCORE = 0.60D;
    private static final double AUTO_ASSET_MIN_MARGIN = 0.15D;
    private static final double AUTO_ASSET_SCORE_RATIO = 1.50D;
    public static final String LEGACY_TOOL_NAME = "ops_capability_query";
    public static final String SERVER_QUERY_TOOL = "server_capability_query";
    public static final String HTTP_QUERY_TOOL = "http_capability_query";
    public static final String JMX_QUERY_TOOL = "jmx_capability_query";
    public static final String DATABASE_QUERY_TOOL = "database_capability_query";

    private static final Domain SERVER = new Domain(SERVER_QUERY_TOOL, "Server operations capability query",
        "host", "ssh_host", "linux_command_execute", true,
        "mcp.server-capability-query.v1", "mcp.ssh-template.v1");
    private static final Domain HTTP = new Domain(HTTP_QUERY_TOOL, "HTTP capability query",
        "http", "http_endpoint", "http_request_execute", true,
        "mcp.http-capability-query.v1", "mcp.http-template.v1");
    private static final Domain JMX = new Domain(JMX_QUERY_TOOL, "Java/JMX monitoring capability query",
        "java", "jmx_endpoint", "jmx_monitor_execute", false,
        "mcp.jmx-capability-query.v1", "mcp.jmx-template.v1");
    private static final Domain DATABASE = new Domain(DATABASE_QUERY_TOOL, "Database operations capability query",
        "database", "sql_datasource", "sql_query_execute", true,
        "mcp.database-capability-query.v1", "mcp.sql-template.v1");
    private static final List<Domain> DOMAINS = List.of(SERVER, HTTP, JMX, DATABASE);

    private static final List<String> INTERNAL_DISCOVERY_TOOLS = List.of(
        AssetDiscoveryMcpToolPublisher.SSH_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.SQL_DATASOURCE_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.LEGACY_SQL_DATASOURCE_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.HTTP_ENDPOINT_ASSET_TOOL_NAME,
        AssetDiscoveryMcpToolPublisher.MICROSERVICE_ASSET_TOOL_NAME,
        TemplateDiscoveryMcpToolPublisher.LEGACY_SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
        TemplateDiscoveryMcpToolPublisher.JMX_TEMPLATE_TOOL_NAME);

    private final McpSyncServer server;
    private final AssetDiscoveryService assetDiscovery;
    private final CommandTemplateDiscoveryService templateDiscovery;
    private TemplateQueryMcpToolPublisher dynamicTemplateQueries;

    @Autowired
    void configureDynamicTemplateQueries(TemplateQueryMcpToolPublisher dynamicTemplateQueries) {
        this.dynamicTemplateQueries = dynamicTemplateQueries;
    }
    public synchronized void refresh() {
        refreshPublication();
        log.info("Domain capability queries published: {}; generic operations bridge removed",
            DOMAINS.stream().map(Domain::toolName).toList());
    }

    @Override public String contributorId() { return "operations_discovery"; }
    @Override public McpSyncServer publicationServer() { return server; }
    @Override public List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        return DOMAINS.stream().map(this::specification)
            .map(com.chatchat.mcpserver.tool.ToolPublication::from).toList();
    }
    @Override public Set<String> retiredToolNames() {
        java.util.LinkedHashSet<String> retired = new java.util.LinkedHashSet<>(INTERNAL_DISCOVERY_TOOLS);
        retired.add(LEGACY_TOOL_NAME);
        return Set.copyOf(retired);
    }

    private McpServerFeatures.SyncToolSpecification specification(Domain domain) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Complete " + domain.title() + " request"));
        properties.put("stage", Map.of("type", "string",
            "enum", domain.assetDiscoverySupported() ? List.of("template", "asset") : List.of("template"),
            "description", "template by default" + (domain.assetDiscoverySupported()
                ? "; use asset only to disambiguate a logical target" : "")));
        properties.put("filters", Map.of("type", "object", "additionalProperties", true,
            "description", "Logical domain filters only; concrete endpoints and credentials are forbidden"));
        properties.put("templateIds", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(domain.toolName())
            .title(domain.title())
            .description(description(domain))
            .inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
            .meta(meta(domain)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                Map<String, Object> result = query(domain.toolName(), request.arguments());
                return McpSchema.CallToolResult.builder().addTextContent(domain.title() + " completed")
                    .structuredContent(result).isError(false).build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage())
                    .structuredContent(Map.of("success", false, "status", "INVALID_REQUEST", "error", ex.getMessage()))
                    .isError(true).build();
            }
        }).build();
    }

    Map<String, Object> query(String toolName, Map<String, Object> rawArguments) {
        Domain domain = domain(toolName);
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        boolean assetStage = "asset".equalsIgnoreCase(text(arguments.get("stage")));
        if (assetStage && !domain.assetDiscoverySupported()) {
            throw new IllegalArgumentException(domain.toolName() + " supports template discovery only");
        }
        String childToolName = TemplateQueryMcpToolPublisher.childToolName(arguments);
        if (!childToolName.isBlank() && assetStage) {
            throw new IllegalArgumentException("Custom template queries support template discovery only");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(arguments);
        // query is the bridge's natural-language envelope, not a target filter field. Keeping it
        // at the top level makes the typed asset service reject an otherwise valid asset-stage
        // request because concrete discovery filters are deliberately schema constrained.
        normalized.remove("query");
        normalized.remove("targetKind");
        normalized.remove("target_kind");
        normalized.remove("assetType");
        Map<String, Object> filters = map(arguments.get("filters"));
        String query = text(arguments.get("query"));
        if (query != null) filters.putIfAbsent("intent", query);
        normalized.put("filters", filters);
        normalized.put("assetType", domain.assetType());
        normalized.put("finalDecision", domain.targetKind());
        normalized.put("confidence", 1.0D);
        normalized.put("candidates", List.of(Map.of("targetKind", domain.targetKind(), "confidence", 1.0D)));
        normalized.put("trace", Map.of("source", domain.toolName(), "bridgeManaged", true));
        // A domain bridge feeds Runtime-owned candidate review, not a user-facing search page.
        // Always expose the complete bounded review window so a small model-authored limit cannot
        // hide a second required capability and collapse a multi-check request into templates[0].
        // Exact templateIds remain a registry-governed filter; this only widens candidates up to
        // the discovery service's published hard maximum.
        if (!assetStage && !normalized.containsKey("templateIds")) {
            normalized.put("limit", CommandTemplateDiscoveryService.MAX_LIMIT);
        }
        Map<String, Object> assetResolution = Map.of();
        if (!assetStage && domain.assetDiscoverySupported() && !hasExplicitAssetIdentity(filters)
            && hasAssetDiscoveryIntent(filters, query)) {
            AssetPreResolution preResolution = preResolveAsset(domain, normalized, filters, query);
            assetResolution = preResolution.audit();
            if (preResolution.selectedAssetName() != null) {
                filters.put("assetName", preResolution.selectedAssetName());
                normalized.put("filters", filters);
            } else {
                Map<String, Object> result = new LinkedHashMap<>(preResolution.assetResult());
                result.put("bridgeManaged", true);
                result.put("bridgeTool", domain.toolName());
                result.put("businessDomain", domain.targetKind());
                result.put("assetType", domain.assetType());
                result.put("stage", "asset_selection");
                if (preResolution.candidateCount() > 0) {
                    result.put("nextStage", "template");
                    result.put("assetSelectionRequired", true);
                } else {
                    result.put("assetNotFound", true);
                }
                result.put("assetResolution", assetResolution);
                result.put("executionTool", domain.executionTool());
                result.put("assetReturnedCount", preResolution.candidateCount());
                result.put("templates", List.of());
                result.put("returnedCount", 0);
                return result;
            }
        }
        Map<String, Object> discovered;
        if (!childToolName.isBlank()) {
            discovered = requireDynamicTemplateQueries().queryFromParent(
                childToolName, domain.toolName(), normalized);
        } else {
            discovered = assetStage ? assetDiscovery.query(normalized) : templateDiscovery.query(normalized);
        }
        Map<String, Object> result = new LinkedHashMap<>(discovered == null ? Map.of() : discovered);
        result.put("bridgeManaged", true);
        result.put("bridgeTool", domain.toolName());
        result.put("businessDomain", domain.targetKind());
        result.put("assetType", domain.assetType());
        result.put("stage", assetStage ? "asset" : "template");
        result.put("executionTool", domain.executionTool());
        if (!assetResolution.isEmpty()) {
            result.put("assetResolution", assetResolution);
        }
        if (!assetStage && !normalized.containsKey("templateIds")) {
            result.put("candidateWindowPolicy", Map.of(
                "mode", "FULL_BOUNDED_REVIEW_WINDOW",
                "limit", CommandTemplateDiscoveryService.MAX_LIMIT,
                "runtimeOwned", true
            ));
        }
        return result;
    }

    private AssetPreResolution preResolveAsset(Domain domain,
                                                Map<String, Object> normalized,
                                                Map<String, Object> filters,
                                                String query) {
        Map<String, Object> assetArguments = new LinkedHashMap<>(normalized);
        Map<String, Object> assetFilters = new LinkedHashMap<>(filters == null ? Map.of() : filters);
        List<String> retrievalTerms = assetRetrievalTerms(assetFilters, query);
        if (!retrievalTerms.isEmpty()) {
            assetFilters.put("queryTerms", retrievalTerms);
            // Asset identity and requested diagnostics are separate semantic channels. Passing the
            // complete task as asset intent makes the asset service tokenize metric/action nouns
            // again and rank unrelated hosts. Template discovery still receives the original intent.
            assetFilters.put("intent", String.join(" ", retrievalTerms));
        }
        assetArguments.put("filters", assetFilters);
        assetArguments.put("assetType", domain.assetType());
        assetArguments.put("finalDecision", domain.targetKind());
        assetArguments.put("limit", 10);
        Map<String, Object> discoveredAssets = assetDiscovery.query(assetArguments);
        Map<String, Object> assetResult = discoveredAssets == null ? Map.of() : discoveredAssets;
        List<Map<String, Object>> candidates = mapList(assetResult.get("assets"));
        Map<String, Object> selected = decisiveAsset(candidates, retrievalTerms);
        String selectedName = assetName(selected);
        String status = selectedName != null ? "RESOLVED"
            : candidates.isEmpty() ? "NOT_FOUND" : "AMBIGUOUS";
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("schemaVersion", "capability_asset_resolution.v1");
        audit.put("status", status);
        audit.put("strategy", "typed_asset_discovery_then_decisive_margin");
        audit.put("candidateCount", candidates.size());
        if (selectedName != null) {
            audit.put("selected", assetSummary(selected));
        }
        audit.put("candidates", candidates.stream().limit(10).map(this::assetSummary).toList());
        audit.put("minimumScore", AUTO_ASSET_MIN_SCORE);
        audit.put("minimumMargin", AUTO_ASSET_MIN_MARGIN);
        return new AssetPreResolution(assetResult, selectedName, candidates.size(), Map.copyOf(audit));
    }

    private List<String> assetRetrievalTerms(Map<String, Object> filters, String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        LinkedHashSet<String> exactFilterValues = new LinkedHashSet<>();
        addNormalizedValue(exactFilterValues, filters == null ? null : filters.get("env"));
        addNormalizedValue(exactFilterValues, filters == null ? null : filters.get("environment"));
        addTerms(terms, filters == null ? null : filters.get("queryTerms"));
        addTerms(terms, filters == null ? null : filters.get("retrievalSignals"));
        addTerms(terms, filters == null ? null : filters.get("keywords"));
        if (query != null && !query.isBlank()) {
            SearchQueryTokenizer.terms(query).stream()
                .filter(term -> term != null && term.length() >= 2 && term.length() <= 64)
                .filter(term -> !term.trim().equalsIgnoreCase(query.trim()))
                // Exact context (for example DEV/PROD) is already a hard filter. Reusing it as an
                // independent semantic retrieval unit gives every asset in that environment the
                // same perfect score and destroys the identity margin.
                .filter(term -> !exactFilterValues.contains(term.trim().toLowerCase(java.util.Locale.ROOT)))
                .sorted(java.util.Comparator
                    .comparing((String term) -> !term.matches(".*[a-zA-Z0-9_].*") )
                    .thenComparingInt(String::length))
                .limit(24)
                .forEach(terms::add);
            // The complete task remains in filters.intent. Do not also publish it as an
            // independent asset query term: operational nouns such as status/session/wait then
            // match many hosts and can flatten a precise identity token into an environment-wide tie.
        }
        return terms.stream().limit(32).toList();
    }

    private void addNormalizedValue(Set<String> target, Object value) {
        String normalized = text(value);
        if (normalized != null) target.add(normalized.toLowerCase(java.util.Locale.ROOT));
    }

    private void addTerms(Set<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) addTerms(target, item);
            return;
        }
        String term = text(value);
        if (term != null) target.add(term);
    }

    private boolean hasExplicitAssetIdentity(Map<String, Object> filters) {
        if (filters == null) return false;
        return text(first(filters, "assetName", "asset_name", "assetId", "asset_id", "name")) != null;
    }

    private boolean hasAssetDiscoveryIntent(Map<String, Object> filters, String query) {
        if (text(query) != null) return true;
        return filters != null && first(filters, "queryTerms", "retrievalSignals", "keywords", "intent") != null;
    }

    private Map<String, Object> decisiveAsset(List<Map<String, Object>> candidates,
                                               List<String> retrievalTerms) {
        if (candidates == null || candidates.isEmpty()) return Map.of();
        if (candidates.size() == 1) return candidates.get(0);
        Map<String, Object> identityMatch = uniqueCanonicalIdentityMatch(candidates, retrievalTerms);
        if (!identityMatch.isEmpty()) return identityMatch;
        double first = assetScore(candidates.get(0));
        double second = assetScore(candidates.get(1));
        if (first >= AUTO_ASSET_MIN_SCORE
            && (first - second >= AUTO_ASSET_MIN_MARGIN
                || (second > 0.0D && first / second >= AUTO_ASSET_SCORE_RATIO))) {
            return candidates.get(0);
        }
        return Map.of();
    }

    private Map<String, Object> uniqueCanonicalIdentityMatch(List<Map<String, Object>> candidates,
                                                              List<String> retrievalTerms) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        java.util.regex.Pattern technicalToken = java.util.regex.Pattern.compile("[\\p{L}\\p{N}_-]{3,}");
        for (String term : retrievalTerms == null ? List.<String>of() : retrievalTerms) {
            if (term == null) continue;
            java.util.regex.Matcher matcher = technicalToken.matcher(term.toLowerCase(java.util.Locale.ROOT));
            while (matcher.find()) signals.add(matcher.group());
        }
        return signals.stream()
            .sorted(java.util.Comparator.comparingInt(String::length).reversed())
            .map(signal -> candidates.stream()
                .filter(candidate -> canonicalIdentity(candidate).contains(signal))
                .toList())
            // Lexical identity may confirm the semantic leader, but it must never use a generic
            // domain word to reorder candidates (for example selecting a lower-ranked asset only
            // because its display name literally contains "database").
            .filter(matches -> matches.size() == 1 && matches.get(0) == candidates.get(0))
            .map(matches -> matches.get(0))
            .findFirst()
            .orElse(Map.of());
    }

    private String canonicalIdentity(Map<String, Object> candidate) {
        Map<String, Object> asset = map(candidate == null ? null : candidate.get("asset"));
        return (String.valueOf(asset.getOrDefault("name", "")) + " "
            + String.valueOf(asset.getOrDefault("toolName", "")) + " "
            + String.valueOf(asset.getOrDefault("logicalName", "")))
            .toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        java.util.ArrayList<Map<String, Object>> values = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) values.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return List.copyOf(values);
    }

    private Map<String, Object> assetSummary(Map<String, Object> candidate) {
        Map<String, Object> asset = map(candidate == null ? null : candidate.get("asset"));
        return compactMap(
            "id", asset.get("id"),
            "name", asset.get("name"),
            "toolName", asset.get("toolName"),
            "environment", asset.get("environment"),
            "score", assetScore(candidate)
        );
    }

    private String assetName(Map<String, Object> candidate) {
        return text(map(candidate == null ? null : candidate.get("asset")).get("name"));
    }

    private double assetScore(Map<String, Object> candidate) {
        Map<String, Object> routing = map(candidate == null ? null : candidate.get("routingHints"));
        Map<String, Object> selection = map(routing.get("assetSelection"));
        Object value = first(selection, "finalScore", "score", "normalizedScore");
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0.0D : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0.0D; }
    }

    private Object first(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    private Map<String, Object> compactMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return Map.copyOf(result);
    }

    private TemplateQueryMcpToolPublisher requireDynamicTemplateQueries() {
        if (dynamicTemplateQueries == null) {
            throw new IllegalStateException("Dynamic template query routing is unavailable");
        }
        return dynamicTemplateQueries;
    }

    private Domain domain(String toolName) {
        return DOMAINS.stream().filter(item -> item.toolName().equals(toolName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported capability query tool: " + toolName));
    }

    private String description(Domain domain) {
        return "Read-only discovery for the " + domain.targetKind() + " domain only. It returns governed assets or "
            + "template candidates and never executes them. Accepted templates execute only through "
            + domain.executionTool() + ". Other business domains require their own capability query tool.";
    }

    private Map<String, Object> meta(Domain domain) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", domain.toolName() + ".v1");
        meta.put("assetType", domain.assetType());
        meta.put("businessDomain", domain.targetKind());
        meta.put("runtime_action", "read_only");
        meta.put("runtimeAction", "read_only");
        meta.put("readOnly", true);
        meta.put("bridgeManaged", true);
        meta.put("executionTool", domain.executionTool());
        meta.put(ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, domain.workflowFamily(), "intent+filters"));
        meta.put(ToolProtocolDriverContract.METADATA_KEY, ToolProtocolDriverContract.of(
            domain.protocolId(),
            List.of(
                "Call " + domain.toolName() + " only for " + domain.targetKind() + " requests.",
                "Review all returned candidates; retrieval rank is not semantic acceptance.",
                "Execute accepted templates only through " + domain.executionTool() + " under Agent Runtime governance."),
            List.of(
                "Never route a different business domain through this tool.",
                "Never invent an asset id, template id or parameter value.",
                "Never pass concrete endpoints, credentials or connection strings.")));
        return Map.copyOf(meta);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private record Domain(String toolName, String title, String targetKind, String assetType,
                          String executionTool, boolean assetDiscoverySupported, String protocolId,
                          String workflowFamily) {
    }

    private record AssetPreResolution(Map<String, Object> assetResult,
                                      String selectedAssetName,
                                      int candidateCount,
                                      Map<String, Object> audit) {
    }
}
