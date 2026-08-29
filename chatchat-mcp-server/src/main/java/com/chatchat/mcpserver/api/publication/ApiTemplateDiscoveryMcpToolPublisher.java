package com.chatchat.mcpserver.api.publication;

import com.chatchat.mcpserver.api.category.ApiServiceCategoryService;
import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;

import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.ops.discovery.CommandTemplateDiscoveryService;
import com.chatchat.mcpserver.routing.target.TargetKindRegistry;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.query.DiscoveryQueryPlan;
import com.chatchat.mcpserver.search.query.SearchQueryTokenizer;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiTemplateDiscoveryMcpToolPublisher {

    public static final String TOOL_NAME = "api_template_query";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final double RELATIVE_RETRIEVAL_FLOOR = 0.20;
    private static final double STRONG_SEMANTIC_FLOOR = 0.60;
    private static final double LOCAL_COVERAGE_FLOOR = 0.15;

    private final McpSyncServer mcpSyncServer;
    private final ApiServiceConfigService configService;
    private final ApiServiceCategoryService categoryService;
    private final LuceneMcpSearchService luceneSearchService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<TemplateQueryMcpToolPublisher> dynamicQueryPublisher;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        remove(TOOL_NAME);
        log.info("API template discovery is internal to {}", ApiMcpToolPublisher.BRIDGE_TOOL_NAME);
    }

    private McpServerFeatures.SyncToolSpecification apiTemplateQueryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(TOOL_NAME)
            .title("API template discovery")
            .description("Read-only MCP tool for retrieving API service templates. "
                + "Search all authorized API templates using the original business request. "
                + "Business categories are ranking signals and model-selection metadata, never hard candidate filters. "
                + "Use it to discover authorized API business capabilities by categoryId, businessGroup, title, description, intent, or bilingual Chinese and English retrieval terms. "
                + "It returns API template metadata and parameter schema, but never returns raw URL templates, headers, or body templates.")
            .inputSchema(inputSchema())
            .meta(meta())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                try {
                    String childToolName = TemplateQueryMcpToolPublisher.childToolName(request.arguments());
                    Map<String, Object> result = childToolName.isBlank()
                        ? query(request.arguments())
                        : dynamicQueryPublisher.getObject().queryFromParent(
                            childToolName, TOOL_NAME, request.arguments());
                    return McpSchema.CallToolResult.builder()
                        .addTextContent("API template query completed")
                        .structuredContent(result)
                        .isError(false)
                        .build();
                } catch (Exception ex) {
                    return McpSchema.CallToolResult.builder()
                        .addTextContent(ex.getMessage())
                        .structuredContent(errorResult(ex.getMessage()))
                        .isError(true)
                        .build();
                }
            })
            .build();
    }

    Map<String, Object> query(Map<String, Object> arguments) {
        Map<String, Object> filters = filters(arguments);
        int limit = limit(arguments);
        List<String> excludedTemplateIds = excludedTemplateIds(arguments);
        List<String> requestedTemplateIds = requestedTemplateIds(arguments);
        List<ApiServiceConfig> enabledConfigs = configService.listEnabled();
        ApiServiceCategoryService.CategoryResolution categoryResolution = categoryService.resolve(
            categoryFilters(filters), enabledConfigs);
        List<ApiServiceConfig> scopedConfigs = hasAssetScope(filters)
            ? scopedApiServices(enabledConfigs, filters)
            : enabledConfigs;
        if (!requestedTemplateIds.isEmpty()) {
            scopedConfigs = scopedConfigs.stream()
                .filter(config -> requestedTemplateIds.contains(text(config.getToolName())))
                .toList();
        }
        List<String> assetSignals = hasAssetScope(filters)
            ? apiServiceSignals(scopedConfigs)
            : List.of();
        Map<String, Object> retrievalFilters = assetSignals.isEmpty()
            ? filters
            : filtersWithApiAssetSignals(filters, assetSignals);
        List<String> terms = terms(retrievalFilters);
        DiscoveryQueryPlan retrievalPlan = DiscoveryQueryPlan.from(retrievalFilters);
        List<String> retrievalVariants = retrievalPlan.queries();
        List<LuceneMcpSearchService.SearchHit> hits = new ArrayList<>();
        if (luceneSearchService != null && luceneSearchService.enabled()) {
            List<String> queries = retrievalVariants.isEmpty()
                ? java.util.Collections.singletonList(null) : retrievalVariants;
            for (String query : queries) {
                hits.addAll(luceneSearchService.searchApiServiceTemplates(
                    new LuceneMcpSearchService.TemplateSearchRequest(
                        "api_service", null, query,
                        Math.max(Math.max(limit, DEFAULT_LIMIT), Math.min(200, enabledConfigs.size() * 4)))));
            }
        }
        Map<String, ApiServiceConfig> configsByToolName = scopedConfigs.stream()
            .filter(config -> !text(config.getToolName()).isBlank())
            .collect(Collectors.toMap(
                config -> text(config.getToolName()),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
            ));
        Map<String, RetrievalEvidence> hitEvidence = hits.stream()
            .map(hit -> apiTemplateHit(configsByToolName, hit, categoryResolution.category()))
            .filter(item -> item != null)
            .collect(Collectors.toMap(
                item -> item.config().getToolName(),
                item -> new RetrievalEvidence(item.score(), item.vector()),
                (left, right) -> new RetrievalEvidence(
                    Math.max(left.score(), right.score()), left.vector() || right.vector()),
                LinkedHashMap::new
            ));
        double bestHitScore = hitEvidence.values().stream()
            .mapToDouble(RetrievalEvidence::score).max().orElse(0.0D);
        boolean browseMode = retrievalVariants.isEmpty();
        boolean explicitSelection = !requestedTemplateIds.isEmpty();
        List<ScoredApiTemplate> matched = scopedConfigs.stream()
            .filter(config -> !text(config.getToolName()).isBlank())
            .filter(config -> !excludedTemplateIds.contains(config.getToolName()))
            .map(config -> scoredTemplate(config, retrievalVariants, hitEvidence, bestHitScore,
                categoryResolution.category(), browseMode, explicitSelection))
            .filter(ScoredApiTemplate::qualified)
            .sorted(java.util.Comparator.comparingDouble(ScoredApiTemplate::score).reversed()
                .thenComparing(item -> item.config().getToolName()))
            .toList();
        List<Map<String, Object>> templates = matched.stream()
            .limit(limit)
            .map(this::templateMetadata)
            .toList();
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", true,
            "targetKind", "api_service",
            "assetType", "api_service",
            "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION,
            "filters", filters,
            "limit", limit,
            "returnedCount", templates.size(),
            "categoryRequired", false,
            "selectedCategory", categoryMetadata(categoryResolution.category()),
            "categoryCandidates", categoryResolution.candidates().stream().map(this::categoryMetadata).toList(),
            "possiblyTruncated", matched.size() > limit,
            "requestedTemplateIds", requestedTemplateIds,
            "excludedTemplateIds", excludedTemplateIds,
            "selectionProtocol", mapOf(
                "schemaVersion", "template_selection_protocol.v1",
                "allowedDecisions", List.of("accept", "refine", "reject"),
                "selectedTemplateIdSource", "templates[].templateId",
                "refineWith", List.of("filters.categoryId", "filters.businessGroup", "filters.intent", "filters.goal", "filters.keywords", "excludeTemplateIds"),
                "mustNotRepeatIdenticalQuery", true
            ),
            "templateSelectionPolicy", mapOf(
                "templateIdSource", "templates[].templateId",
                "mustUseReturnedTemplateId", true,
                "doNotInventTemplateNames", true,
                "runtimeSemanticReviewRequiredWhenMultiple", true,
                "mcpRelevanceIsAdmissionFilter", true,
                "relativeRetrievalFloor", RELATIVE_RETRIEVAL_FLOOR,
                "localCoverageFloor", LOCAL_COVERAGE_FLOOR,
                "querySegmentation", "NFKC normalization with identifier preservation, script-aware tokenization and Chinese bigrams",
                "vectorRetrieval", "OpenSearch embedding/KNN evidence is admitted only when present and above the relative semantic floor; otherwise BM25/local coverage rules apply",
                "rawExecutionSpecReturned", false,
                "selectionFields", List.of("templateId", "toolName", "title", "description", "businessGroup", "capabilitySpec", "outputSchema", "dependencySpec", "parameterSchema", "requiredParameters", "parameterContract", "invocationExample"),
                "onEmptyResult", "No existing API template matched the request. Do not invent an API tool name."
            ),
            "queryIr", mapOf(
                "schemaVersion", "api_template_query_ir.v1",
                "assetType", "api_service",
                "targetKind", "api_service",
                "indexType", "api_service_template",
                "terms", terms
                , "retrievalVariants", retrievalVariants
            ),
            "retrievalPlan", retrievalPlan.metadata(),
            "diagnostics", mapOf(
                "source", "lucene_api_service_template_index",
                "hitCount", hitEvidence.size(),
                "hitIds", hitEvidence.keySet().stream().limit(limit).toList(),
                "candidateCount", matched.size(),
                "candidatePolicy", "authorized_relevance_qualified_candidates",
                "retrievedCandidateCount", hitEvidence.size(),
                "qualifiedCandidateCount", matched.size(),
                "categoryScoped", false,
                "categoryRequired", false,
                "categoryAmbiguous", categoryResolution.categoryRequired(),
                "categoryUsage", "ranking_signal_and_model_selection_metadata",
                "assetScoped", hasAssetScope(filters),
                "scopedAssetCount", scopedConfigs.size(),
                "retrievalSignals", assetSignals,
                "fallbackUsed", categoryResolution.fallbackUsed(),
                "fallbackCategory", categoryResolution.fallbackUsed()
                    ? BusinessCategoryService.DEFAULT_CODE : ""
            ),
            "retrievalFlow", mapOf(
                "schemaVersion", "classified_template_execution.v1",
                "steps", List.of("business_category_resolution", "global_template_search_with_category_ranking",
                    "api_template_execution", "evidence_analysis"),
                "crossCategoryResultsAllowed", true
            ),
            "templates", templates
        );
    }

    private McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object", mapOf(
            "schemaVersion", Map.of("type", "string", "description", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION),
            "filtersSchemaVersion", Map.of("type", "string", "description", TargetKindRegistry.FILTERS_SCHEMA_VERSION),
            "filters", Map.of(
                "type", "object",
                "description", "Logical filters for API templates. queryTerms[], keywords[], retrievalSignals[], and nested intentCandidates queries are independent query units: each item is searched separately and results are unioned with per-unit evidence. Do not concatenate analyzed keywords. Raw URL, headers, and body templates are not accepted.",
                "additionalProperties", true
            ),
            "bilingualIntent", Map.of(
                "type", "array",
                "description", "Model-generated bilingual retrieval terms. Include both Chinese and English phrases.",
                "items", Map.of("type", "string")
            ),
            "bilingualQuery", Map.of("type", "string", "description", "Alias for model-generated bilingual retrieval text."),
            "intentZh", Map.of("type", "string", "description", "Chinese retrieval phrase generated by the model for API template matching."),
            "intentEn", Map.of("type", "string", "description", "English retrieval phrase generated by the model for API template matching."),
            "executionContext", Map.of(
                "type", "object",
                "description", "Alias for logical filters. Raw URL, headers, and body templates are not accepted.",
                "additionalProperties", true
            ),
            "trace", Map.of(
                "type", "object",
                "description", "Replay trace such as plannerVersion, model, promptVersion, or taskId.",
                "additionalProperties", true
            ),
            "limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", MAX_LIMIT,
                "description", "Maximum number of templates returned; capped at 20."
            ),
            "excludeTemplateIds", Map.of(
                "type", "array",
                "description", "Template ids rejected by a prior semantic review. They are excluded from this refinement query.",
                "items", Map.of("type", "string")
            ),
            "templateIds", Map.of(
                "type", "array",
                "description", "Authorized template ids returned by prior asset discovery. When present, discovery is restricted to this exact candidate set.",
                "items", Map.of("type", "string")
            )
        ), List.of("filters"), false, null, null);
    }

    private Map<String, Object> meta() {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "kind", "api_template_discovery_tool",
            "runtime_action", "read_only",
            "runtimeAction", "read_only",
            "controlPlane", "discovery",
            "readOnly", true,
            "risk_level", "low",
            "riskLevel", "low",
            "targetKind", "api_service",
            "assetType", "api_service",
            ToolWorkflowContract.METADATA_KEY, ToolWorkflowContract.declaration(
                ToolWorkflowRole.TEMPLATE_DISCOVERY, "mcp.api-template-discovery.v1", "filters"),
            "confirmation", mapOf("default", "auto_execute", "allow_user_override", false),
            "resultShape", mapOf(
                "canonical", "templates[]",
                "templateIdPath", "templates[].templateId",
                "queryIrPath", "queryIr"
            ),
            "languageSupport", mapOf(
                "mode", "bilingual",
                "languages", List.of("zh", "en"),
                "modelMustGenerateBilingualRetrieval", true,
                "bilingualQueryFields", List.of("bilingualIntent", "bilingualQuery", "intentZh", "intentEn", "filters.bilingualIntent", "filters.intentZh", "filters.intentEn")
            ),
            "routingProtocol", mapOf(
                "forcedTargetKind", "api_service",
                "forcedAssetType", "api_service",
                "filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION,
                "categoryFirst", false,
                "categoryUsage", "ranking_signal_and_model_selection_metadata",
                "crossCategoryResultsAllowed", true
            ),
            "executionFlow", mapOf(
                "schemaVersion", "classified_template_execution.v1",
                "steps", List.of("business_category_resolution", "global_template_search_with_category_ranking",
                    "api_template_execution", "evidence_analysis"),
                "executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME,
                "crossCategoryResultsAllowed", true
            ),
            "indexPolicy", mapOf(
                "logicalIndex", "template:api_service",
                "filterField", "assetType",
                "isolatedByTool", true
            ),
            "forbiddenConcreteTargetFields", List.of("url", "urlTemplate", "headers", "headersJson", "body", "bodyTemplate"),
            "rawExecutionSpecReturned", false
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filters(Map<String, Object> arguments) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return filters;
        }
        Object rawFilters = arguments.get("filters");
        if (rawFilters instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        Object context = firstValue(arguments, "executionContext", "mcpExecutionContext");
        if (context instanceof Map<?, ?> map) {
            filters.putAll((Map<String, Object>) map);
        }
        for (String key : List.of(
            "toolName",
            "name",
            "businessGroup",
            "business_group",
            "group",
            "groupName",
            "group_name",
            "groupDescription",
            "group_description",
            "service",
            "target",
            "labels",
            "intent",
            "bilingualIntent",
            "bilingualQuery",
            "intentZh",
            "intentEn",
            "goal",
            "category",
            "language",
            "queryLanguage"
        )) {
            Object value = arguments.get(key);
            if (value != null) {
                filters.putIfAbsent(key, value);
            }
        }
        rejectRawApiFields(filters);
        return filters;
    }

    private void rejectRawApiFields(Map<String, Object> filters) {
        for (String field : List.of("url", "urlTemplate", "headers", "headersJson", "body", "bodyTemplate")) {
            Object value = filters.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Raw API execution field is not allowed in api_template_query: " + field);
            }
        }
    }

    public Map<String, Object> queryAuthorized(Map<String, Object> arguments, java.util.Set<String> templateIds) {
        Map<String, Object> restricted = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        restricted.put("templateIds", templateIds == null ? List.of() : List.copyOf(templateIds));
        return query(restricted);
    }

    @SuppressWarnings("unchecked")
    private List<String> requestedTemplateIds(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        Object value = firstValue(arguments, "templateIds", "template_ids", "candidateTemplateIds");
        if (value == null && arguments.get("filters") instanceof Map<?, ?> filters) {
            value = firstValue((Map<String, Object>) filters,
                "templateIds", "template_ids", "candidateTemplateIds");
        }
        List<String> ids = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> {
                String id = text(item);
                if (!id.isBlank() && !ids.contains(id)) {
                    ids.add(id);
                }
            });
        } else {
            String id = text(value);
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private List<String> terms(Map<String, Object> filters) {
        List<String> terms = new ArrayList<>();
        for (String key : List.of("toolName", "name", "businessGroup", "business_group", "group", "groupName",
            "group_name", "groupDescription", "group_description", "service", "target", "intent",
            "bilingualQuery", "intentZh", "intentEn", "goal", "category")) {
            addTerm(terms, filters.get(key));
        }
        addTerm(terms, filters.get("bilingualIntent"));
        addTerm(terms, filters.get("labels"));
        addTerm(terms, filters.get("retrievalSignals"));
        addTerm(terms, filters.get("queryTerms"));
        return terms.stream()
            .map(this::normalize)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private void addTerm(List<String> terms, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addTerm(terms, item);
            }
            return;
        }
        String text = text(value);
        if (!text.isBlank()) {
            terms.add(text);
        }
    }

    private ApiTemplateHit apiTemplateHit(Map<String, ApiServiceConfig> configsByToolName,
                                          LuceneMcpSearchService.SearchHit hit,
                                          BusinessCategory preferredCategory) {
        if (hit == null) {
            return null;
        }
        ApiServiceConfig config = configsByToolName.get(text(hit.id()));
        if (config == null) {
            config = configsByToolName.get(text(hit.documentId()));
        }
        if (config == null) {
            return null;
        }
        double categoryBoost = preferredCategory != null && belongsTo(config, preferredCategory) ? 1.0 : 0.0;
        boolean vector = hit.reasons() != null && hit.reasons().stream()
            .anyMatch(reason -> reason != null && reason.startsWith("opensearch_vector:"));
        return new ApiTemplateHit(config, hit.score() + categoryBoost, vector);
    }

    private ScoredApiTemplate scoredTemplate(ApiServiceConfig config,
                                             List<String> retrievalVariants,
                                             Map<String, RetrievalEvidence> hitEvidence,
                                             double bestHitScore,
                                             BusinessCategory preferredCategory,
                                             boolean browseMode,
                                             boolean explicitSelection) {
        TemplateLexicalQuality lexical = templateLexicalQuality(config, retrievalVariants);
        RetrievalEvidence evidence = hitEvidence.get(config.getToolName());
        Double retrievalScore = evidence == null ? null : evidence.score();
        double relativeScore = retrievalScore == null || bestHitScore <= 0.0D
            ? 0.0D
            : retrievalScore / bestHitScore;
        boolean indexQualified = retrievalScore != null
            && relativeScore >= RELATIVE_RETRIEVAL_FLOOR
            && (lexical.matchedTerms() > 0
                || evidence.vector() && relativeScore >= STRONG_SEMANTIC_FLOOR);
        boolean registryQualified = lexical.coverage() >= LOCAL_COVERAGE_FLOOR
            && lexical.matchedTerms() >= (lexical.queryTerms() >= 4 ? 2 : 1);
        boolean qualified = browseMode || explicitSelection || indexQualified || registryQualified;
        double categoryBoost = preferredCategory != null && belongsTo(config, preferredCategory) ? 1.0D : 0.0D;
        double score = (retrievalScore == null ? lexical.coverage() : retrievalScore) + categoryBoost;
        return new ScoredApiTemplate(config, score, qualified, lexical.matchedTerms(), lexical.coverage());
    }

    private TemplateLexicalQuality templateLexicalQuality(ApiServiceConfig config, List<String> retrievalVariants) {
        if (retrievalVariants == null || retrievalVariants.isEmpty()) {
            return new TemplateLexicalQuality(0, 0, 1.0D);
        }
        String haystack = normalize(String.join(" ", List.of(
            text(config.getToolName()),
            text(config.getTitle()),
            text(config.getDescription()),
            text(config.getBusinessGroup()),
            text(config.getBusinessGroupName()),
            text(config.getBusinessGroupDescription()),
            String.join(" ", configuredMetadataSignals(config))
        )));
        return retrievalVariants.stream()
            .map(variant -> lexicalTerms(variant).stream().filter(term -> !term.isBlank()).distinct().toList())
            .map(queryTerms -> {
                int matched = (int) queryTerms.stream().filter(haystack::contains).count();
                double coverage = queryTerms.isEmpty() ? 0.0D : matched / (double) queryTerms.size();
                return new TemplateLexicalQuality(queryTerms.size(), matched, coverage);
            })
            .max(java.util.Comparator.comparingDouble(TemplateLexicalQuality::coverage)
                .thenComparingInt(TemplateLexicalQuality::matchedTerms))
            .orElse(new TemplateLexicalQuality(0, 0, 0.0D));
    }

    private List<String> lexicalTerms(String value) {
        return SearchQueryTokenizer.terms(value);
    }

    private Map<String, Object> filtersWithApiAssetSignals(Map<String, Object> filters, List<String> signals) {
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        appendSignals(merged, "intent", signals);
        appendSignals(merged, "retrievalSignals", signals);
        appendSignals(merged, "queryTerms", signals);
        return merged;
    }

    private void appendSignals(Map<String, Object> filters, String key, List<String> signals) {
        if (filters == null || key == null || signals == null || signals.isEmpty()) {
            return;
        }
        List<Object> values = new ArrayList<>();
        Object existing = filters.get(key);
        if (existing instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                values.add(item);
            }
        } else if (existing != null) {
            values.add(existing);
        }
        values.addAll(signals);
        filters.put(key, values.stream()
            .filter(value -> value != null && !String.valueOf(value).isBlank())
            .map(value -> String.valueOf(value).trim())
            .distinct()
            .toList());
    }

    private List<ApiServiceConfig> scopedApiServices(List<ApiServiceConfig> configs, Map<String, Object> filters) {
        if (!hasAssetScope(filters)) {
            return List.of();
        }
        return (configs == null ? List.<ApiServiceConfig>of() : configs).stream()
            .filter(config -> matchesApiServiceScope(config, filters))
            .toList();
    }

    private boolean hasAssetScope(Map<String, Object> filters) {
        return firstValue(filters, "toolName", "templateId", "template_id") != null;
    }

    private boolean matchesApiServiceScope(ApiServiceConfig config, Map<String, Object> filters) {
        String requestedName = text(firstValue(filters, "toolName", "name", "service", "target"));
        if (!requestedName.isBlank()
            && !logicalNameMatches(requestedName, config.getToolName())
            && !logicalNameMatches(requestedName, config.getTitle())
            && !logicalNameMatches(requestedName, config.getBusinessGroup())
            && !logicalNameMatches(requestedName, config.getBusinessGroupName())) {
            return false;
        }
        String group = text(firstValue(filters, "businessGroup", "business_group", "group", "groupName", "group_name"));
        if (!group.isBlank()
            && !logicalNameMatches(group, config.getBusinessGroup())
            && !logicalNameMatches(group, config.getBusinessGroupName())) {
            return false;
        }
        String groupDescription = text(firstValue(filters, "groupDescription", "group_description"));
        if (!groupDescription.isBlank()
            && !logicalNameMatches(groupDescription, config.getBusinessGroupDescription())) {
            return false;
        }
        Object labels = filters.get("labels");
        if (labels != null) {
            List<String> requestedLabels = new ArrayList<>();
            addTerm(requestedLabels, labels);
            List<String> configLabels = apiServiceSignals(List.of(config)).stream()
                .map(this::normalize)
                .toList();
            if (!configLabels.containsAll(requestedLabels.stream().map(this::normalize).toList())) {
                return false;
            }
        }
        return true;
    }

    private ApiServiceCategoryService.MapLike categoryFilters(Map<String, Object> filters) {
        return new ApiServiceCategoryService.MapLike() {
            @Override
            public String first(String... keys) {
                return text(firstValue(filters, keys));
            }

            @Override
            public String joinedText() {
                return terms(filters).stream().collect(Collectors.joining(" "));
            }
        };
    }

    private boolean belongsTo(ApiServiceConfig config, BusinessCategory category) {
        return category.getId().equals(config.getCategoryId())
            || category.getCode().equalsIgnoreCase(text(config.getBusinessGroup()));
    }

    private Map<String, Object> categoryMetadata(BusinessCategory category) {
        if (category == null) return Map.of();
        return mapOf(
            "id", category.getId(),
            "code", category.getCode(),
            "name", category.getName(),
            "description", text(category.getDescription()),
            "keywords", categoryService.keywords(category)
        );
    }

    private List<String> apiServiceSignals(List<ApiServiceConfig> configs) {
        List<String> signals = new ArrayList<>();
        for (ApiServiceConfig config : configs == null ? List.<ApiServiceConfig>of() : configs) {
            addTerm(signals, config.getToolName());
            addTerm(signals, config.getTitle());
            addTerm(signals, config.getDescription());
            addTerm(signals, config.getBusinessGroup());
            addTerm(signals, config.getBusinessGroupName());
            addTerm(signals, config.getBusinessGroupDescription());
            addTerm(signals, configuredMetadataSignals(config));
        }
        return signals.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(24)
            .toList();
    }

    private List<String> governanceSignals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            List<String> signals = new ArrayList<>();
            collectScalarSignals(value, signals);
            return signals;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void collectScalarSignals(Object value, List<String> signals) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectScalarSignals(item, signals));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectScalarSignals(item, signals);
            }
            return;
        }
        addTerm(signals, value);
    }

    private boolean logicalNameMatches(String requested, String candidate) {
        String left = normalize(requested);
        String right = normalize(candidate);
        return !left.isBlank() && !right.isBlank() && (left.equals(right) || left.contains(right) || right.contains(left));
    }

    private Map<String, Object> templateMetadata(ScoredApiTemplate scored) {
        ApiServiceConfig config = scored.config();
        Map<String, Object> parameterSchema = parameterSchema(config.getInputSchemaJson());
        List<String> requiredParameters = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.TEMPLATE_SCHEMA_VERSION,
            "templateId", config.getToolName(),
            "id", config.getToolName(),
            "toolName", config.getToolName(),
            "title", firstText(config.getTitle(), config.getToolName()),
            "description", text(config.getDescription()),
            "businessGroup", businessGroupMetadata(config),
            "assetType", "api_service",
            "targetKind", "api_service",
            "method", config.getMethod(),
            "parameterSchema", parameterSchema,
            "outputSchema", jsonObject(config.getOutputSchemaJson()),
            "capabilitySpec", jsonObject(config.getCapabilitySpecJson()),
            "dependencySpec", jsonObject(config.getDependencySpecJson()),
            "requiredParameters", requiredParameters,
            "parameterContract", directParameterContract(config.getToolName(), parameterSchema),
            "invocationExample", directInvocationExample(config.getToolName(), parameterSchema),
            "riskLevel", "LOW",
            "enabled", config.isEnabled(),
            "relevanceScore", scored.score(),
            "relevanceCoverage", scored.coverage(),
            "matchedTermCount", scored.matchedTerms(),
            "relevanceStrategy", "api_template_hybrid_quality_gate_v2",
            "routing", mapOf(
                "callTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME,
                "templateId", config.getToolName(),
                "source", TOOL_NAME + ".templates[].templateId"
            )
        );
    }

    private Map<String, Object> businessGroupMetadata(ApiServiceConfig config) {
        String code = firstText(config.getBusinessGroup(), "default");
        return mapOf(
            "code", code,
            "name", firstText(config.getBusinessGroupName(), code),
            "description", firstText(config.getBusinessGroupDescription(), "")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directParameterContract(String toolName, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        List<String> required = requiredParameters(parameterSchema);
        return mapOf(
            "schemaVersion", "template_parameter_contract.v1",
            "templateId", toolName,
            "executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME,
            "argumentContainer", ApiMcpToolPublisher.EXECUTE_TOOL_NAME + ".parameters",
            "required", required,
            "optional", properties.keySet().stream()
                .filter(key -> !required.contains(key))
                .toList(),
            "mustPassUnderParameters", true,
            "topLevelTemplateParametersAllowed", false,
            "missingRequiredBehavior", "Do not call the API MCP tool until every required argument is present."
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directInvocationExample(String toolName, Map<String, Object> parameterSchema) {
        Map<String, Object> properties = parameterSchema == null || !(parameterSchema.get("properties") instanceof Map<?, ?> map)
            ? Map.of()
            : (Map<String, Object>) map;
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String required : requiredParameters(parameterSchema)) {
            arguments.put(required, exampleValue(required, properties.get(required)));
        }
        return mapOf(
            "tool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME,
            "templateId", toolName,
            "parameters", arguments
        );
    }

    private List<String> excludedTemplateIds(Map<String, Object> arguments) {
        Object value = arguments == null ? null : firstValue(arguments, "excludeTemplateIds", "excludedTemplateIds");
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            String id = text(item);
            if (!id.isBlank() && !values.contains(id)) {
                values.add(id);
            }
        }
        return List.copyOf(values);
    }

    private Map<String, Object> jsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> requiredParameters(Map<String, Object> parameterSchema) {
        Object required = parameterSchema == null ? null : parameterSchema.get("required");
        if (!(required instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private String exampleValue(String name, Object schema) {
        if (schema instanceof Map<?, ?> map && map.get("example") != null) {
            return String.valueOf(map.get("example"));
        }
        return "<" + name + ">";
    }

    private Map<String, Object> parameterSchema(String json) {
        if (json == null || json.isBlank()) {
            return Map.of("type", "object", "additionalProperties", true);
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("type", "object", "additionalProperties", true);
        }
    }

    private Map<String, Object> errorResult(String message) {
        return mapOf(
            "schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION,
            "querySchemaVersion", CommandTemplateDiscoveryService.QUERY_SCHEMA_VERSION,
            "success", false,
            "error", message,
            "errorDetail", mapOf(
                "code", "API_TEMPLATE_QUERY_REJECTED",
                "message", message
            )
        );
    }

    private int limit(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("limit");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException ex) {
            return DEFAULT_LIMIT;
        }
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        if (map == null) {
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

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalize(Object value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("API template discovery MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private record ScoredApiTemplate(ApiServiceConfig config,
                                     double score,
                                     boolean qualified,
                                     int matchedTerms,
                                     double coverage) {
    }

    private List<String> configuredMetadataSignals(ApiServiceConfig config) {
        List<String> signals = new ArrayList<>();
        for (String json : List.of(
            text(config.getInputSchemaJson()), text(config.getOutputSchemaJson()),
            text(config.getCapabilitySpecJson()), text(config.getDependencySpecJson()),
            text(config.getGovernanceJson()))) {
            signals.addAll(governanceSignals(json));
        }
        return signals.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private record ApiTemplateHit(ApiServiceConfig config, double score, boolean vector) {
    }

    private record RetrievalEvidence(double score, boolean vector) {
    }

    private record TemplateLexicalQuality(int queryTerms, int matchedTerms, double coverage) {
    }
}
