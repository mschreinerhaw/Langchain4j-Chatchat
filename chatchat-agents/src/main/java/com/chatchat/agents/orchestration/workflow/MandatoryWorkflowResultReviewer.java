package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.evidence.AgentToolResultFactExtractor;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.tool.ToolObservationBuilder;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Performs deterministic local review at mandatory workflow boundaries. */
public final class MandatoryWorkflowResultReviewer {

    private final AgentToolNameResolver toolNames;
    private final AgentToolResultFactExtractor factExtractor;
    private final ObjectMapper objectMapper;
    private ToolObservationBuilder observationBuilder;

    public MandatoryWorkflowResultReviewer(AgentToolNameResolver toolNames,
                                           AgentToolResultFactExtractor factExtractor,
                                           ToolObservationBuilder observationBuilder,
                                           ObjectMapper objectMapper) {
        this.toolNames = toolNames;
        this.factExtractor = factExtractor;
        this.observationBuilder = observationBuilder;
        this.objectMapper = objectMapper;
    }

    public void setObservationBuilder(ToolObservationBuilder observationBuilder) {
        if (observationBuilder != null) this.observationBuilder = observationBuilder;
    }

    public Map<String, Object> review(String toolName, ToolOutput output) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("schemaVersion", "mandatory_workflow_result_review.v1");
        review.put("toolName", toolName);
        review.put("reviewType", "LOCAL_CONTRACT_REVIEW");
        if (output == null || !output.isSuccess()) {
            review.put("satisfied", false);
            review.put("reason", "Mandatory workflow tool did not return a successful ToolOutput.");
            return review;
        }
        Integer assetCount = assetDiscoveryResultCount(toolName, output.getData());
        if (assetCount != null) return discoveryReview(review, assetCount, "ASSET");
        Integer templateCount = templateDiscoveryResultCount(toolName, output.getData());
        if (templateCount != null) return discoveryReview(review, templateCount, "TEMPLATE");

        Map<String, Object> root = factExtractor.enterpriseMetadataResultRoot(output.getData());
        if (factExtractor.isEnterpriseMetadataResult(root)) {
            Map<String, Object> coverage = asMap(root.get("coverage"));
            Map<String, Object> sourceSchema = asMap(root.get("sourceSchema"));
            int sourceCount = intValue(firstNonNull(
                root.get("sourceFieldCount"), sourceSchema.get("fieldCount")),
                collectionSize(sourceSchema.get("fields")));
            int matchedCount = intValue(root.get("matchedFieldCount"), collectionSize(root.get("fieldMatches")));
            int processedCount = intValue(coverage.get("processedFieldCount"), matchedCount);
            boolean allProcessed = booleanValue(coverage.get("allFieldsProcessed"))
                || (sourceCount > 0 && processedCount == sourceCount);
            boolean satisfied = sourceCount > 0 && matchedCount == sourceCount && allProcessed;
            review.put("satisfied", satisfied);
            review.put("reason", satisfied
                ? "Enterprise metadata contract returned and processed every source field."
                : "Enterprise metadata contract did not cover every source field.");
            review.put("sourceFieldCount", sourceCount);
            review.put("matchedFieldCount", matchedCount);
            review.put("processedFieldCount", processedCount);
            review.put("allFieldsProcessed", allProcessed);
            String evidence = observationBuilder.buildAuthoritativeExecutionEvidence(toolName, output);
            if (evidence != null && !evidence.isBlank()) review.put("authoritativeEvidence", evidence);
            return review;
        }
        review.put("satisfied", true);
        review.put("reason", "Mandatory workflow tool completed successfully and returned a terminal observation.");
        return review;
    }

    public Map<String, Object> reviewPredecessors(String dependentTool,
                                                   List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) return Map.of("satisfied", true);
        for (InteractionToolTrace trace : traces) {
            if (trace == null) continue;
            ToolOutput output = trace.isSuccess()
                ? ToolOutput.success(asMap(trace.getOutput()))
                : ToolOutput.failure(firstNonBlank(trace.getErrorMessage(), "predecessor failed"));
            Map<String, Object> review = review(trace.getToolName(), output);
            if (!Boolean.TRUE.equals(review.get("satisfied"))) {
                Map<String, Object> blocked = new LinkedHashMap<>(review);
                blocked.put("predecessorToolName", trace.getToolName());
                blocked.put("blockedDependentToolName", dependentTool);
                return blocked;
            }
        }
        return Map.of("satisfied", true);
    }

    private Map<String, Object> discoveryReview(Map<String, Object> review, int count, String type) {
        boolean satisfied = count > 0;
        review.put("satisfied", satisfied);
        review.put("resultCode", satisfied ? type + "_MATCHED" : "NO_MATCHING_" + type);
        review.put("returnedCount", count);
        review.put("reason", satisfied
            ? type.equals("ASSET")
                ? "Asset discovery returned at least one candidate for semantic model review."
                : "Template discovery returned at least one executable template."
            : type.equals("ASSET")
                ? "Asset discovery completed but returned no candidate; dependent workflow tools are blocked."
                : "Template discovery completed but returned no executable template; dependent execution is blocked.");
        return review;
    }

    private Integer assetDiscoveryResultCount(String toolName, Object data) {
        return toolNames.isAssetDiscoveryToolName(toolName) ? discoveryResultCount(data, "assets", 0) : null;
    }

    private Integer templateDiscoveryResultCount(String toolName, Object data) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("template_query") || normalized.contains("template_search")
            ? discoveryResultCount(data, "templates", 0) : null;
    }

    private Integer discoveryResultCount(Object value, String collectionKey, int depth) {
        if (value == null || depth > 5) return null;
        if (value instanceof String text) {
            Map<String, Object> parsed = asMap(text);
            return parsed.isEmpty() ? null : discoveryResultCount(parsed, collectionKey, depth + 1);
        }
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> map = asMap(raw);
        Integer explicit = integerValue(map.get("returnedCount"));
        if (explicit != null) return explicit;
        if (map.get(collectionKey) instanceof Collection<?> candidates) return candidates.size();
        for (String key : List.of("preview", "structuredContent", "data", "result", "payload", "body", "output")) {
            Integer nested = discoveryResultCount(map.get(key), collectionKey, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    private int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return value == null || String.valueOf(value).isBlank() ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (value instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }
}
