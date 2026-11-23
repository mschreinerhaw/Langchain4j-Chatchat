package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Limits Cline's proposal to the existing retrieval channels, preserving Runtime routing fields. */
public final class ClineSearchInput {
    public static final int MAX_TERMS = 4;
    private static final List<String> SEARCH_FIELDS = List.of("query", "queryTerms", "keywords", "keyword",
        "retrievalSignals", "intentCandidates", "intent", "goal", "modelSearchProfile", "queries",
        "expandedQueries", "expanded_queries", "intentAliases", "bilingualIntent", "intentZh", "intentEn");

    private ClineSearchInput() {}

    public static boolean assetSearch(String tool, ToolMetadata metadata, Map<String, Object> input) {
        return "asset".equals(input.get("stage"))
            || ToolWorkflowContract.resolveRole(tool, metadata) == ToolWorkflowRole.ASSET_DISCOVERY;
    }

    public static boolean applies(String tool, ToolMetadata metadata, Map<String, Object> input) {
        return assetSearch(tool, metadata, input) || !contract(metadata).isEmpty();
    }

    public static int maximumTerms(ToolMetadata metadata) {
        Object value = contract(metadata).get("maximumQueryTerms");
        return value instanceof Number limit && limit.intValue() > 0
            ? Math.min(MAX_TERMS, limit.intValue()) : MAX_TERMS;
    }

    public static boolean hasOnlyUnboundNumericTerms(Map<String, Object> proposal, Map<String, Object> constraints) {
        if (!(proposal.get("queryTerms") instanceof List<?> terms) || terms.isEmpty()) return false;
        return terms.stream().allMatch(term -> term instanceof String text
            && text.strip().matches("\\p{Nd}+")
            && !text.strip().equals(constraints.get("assetId"))
            && !text.strip().equals(constraints.get("assetName")));
    }

    public static Map<String, Object> apply(String tool, ToolMetadata metadata, Map<String, Object> input,
            Map<String, Object> proposal) {
        if (proposal.size() != 1 || !(proposal.get("queryTerms") instanceof List<?> terms)
            || terms.size() > maximumTerms(metadata) || terms.stream().anyMatch(term ->
                !(term instanceof String text) || text.isBlank() || text.length() > 160))
            throw new IllegalArgumentException("Invalid Cline search terms");
        List<String> keywords = terms.stream().map(String.class::cast).map(String::trim).distinct().toList();
        var result = new LinkedHashMap<>(input);
        SEARCH_FIELDS.forEach(result::remove);
        result.remove("__modelRetrievalQualityGate");
        var filters = new LinkedHashMap<String, Object>();
        if (input.get("filters") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> { if (key instanceof String name) filters.put(name, value); });
            SEARCH_FIELDS.forEach(filters::remove);
            result.put("filters", filters);
        }
        Object paths = contract(metadata).get("allowedArgumentPaths");
        if (assetSearch(tool, metadata, input) || paths instanceof List<?> allowed && allowed.contains("filters.queryTerms")) {
            filters.put("queryTerms", keywords);
            result.put("filters", filters);
        } else {
            result.put("queryTerms", keywords);
        }
        return result;
    }

    private static Map<?, ?> contract(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) return Map.of();
        Object declared = metadata.getMetadata().get("modelInputBridgeContract");
        if (declared == null && metadata.getMetadata().get("mcpToolMeta") instanceof Map<?, ?> publisher)
            declared = publisher.get("modelInputBridgeContract");
        return declared instanceof Map<?, ?> contract ? contract : Map.of();
    }
}
