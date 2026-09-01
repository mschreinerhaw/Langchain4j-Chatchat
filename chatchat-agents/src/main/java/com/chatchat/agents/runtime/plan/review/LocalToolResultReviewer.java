package com.chatchat.agents.runtime.plan.review;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies deterministic, domain-neutral admission rules to returned tool data. */
public final class LocalToolResultReviewer {
    private final ToolResultFactInspector facts;

    public LocalToolResultReviewer(ToolResultFactInspector facts) {
        this.facts = facts;
    }

    public Review review(InterpretationPlan.Step step,
                         InterpretationPlanRuntime.StepExecution execution,
                         ToolRoles roles) {
        if (execution == null || !execution.success()) return null;
        Integer stepId = step == null ? null : step.id();
        if (roles.isWebSearch(execution.toolName())) {
            int count = facts.structuredObservationCount(execution.output());
            if (count > 0) return accepted("Unified web_search returned " + count
                + " governed structured observation row(s); model review is unnecessary.", Map.of(
                "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", "structured_data_observations",
                "localFactCheckReason", "tool returned declared structured observation rows, not only discovery metadata",
                "structuredObservationCount", count, "structuredObservationStepId", nullable(stepId)));
        }
        if (roles.isAssetDiscovery(execution.toolName())) {
            int count = facts.discoveredCount(execution.output(), "assets");
            if (count <= 0) return null;
            return accepted("Asset discovery returned " + count + " candidate asset(s); continue to dependent execution step.", Map.of(
                "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", "asset_discovery",
                "localFactCheckReason", "typed asset discovery returned non-empty asset metadata",
                "assetDiscoveryReturnedCount", count, "assetDiscoveryStepId", nullable(stepId)));
        }
        if (roles.isTemplateDiscovery(execution.toolName())) {
            String resultCode = facts.discoveryResultCode(execution.output());
            if ("QUERY_CLAUSE_LIMIT_EXCEEDED".equalsIgnoreCase(resultCode)) {
                return rejected("QUERY_CLAUSE_LIMIT_EXCEEDED: template retrieval exceeded the search clause limit; model review must rewrite a compact, intent-focused keyword set and retry template discovery.", map(
                    "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", "template_discovery_retrieval_limit",
                    "localFactCheckReason", "template search returned a retryable clause-limit diagnostic",
                    "transportSuccess", true, "operationSuccess", false, "businessSatisfied", false,
                    "resultCode", "QUERY_CLAUSE_LIMIT_EXCEEDED", "retryable", true,
                    "nextAction", "REWRITE_TEMPLATE_SEARCH_KEYWORDS_AND_RETRY",
                    "templateDiscoveryReturnedCount", 0, "templateDiscoveryStepId", stepId));
            }
            int count = Math.max(facts.discoveredCount(execution.output(), "templates"),
                facts.discoveredCount(execution.output(), "candidates"));
            if (count <= 0) return rejected("NO_MATCHING_TEMPLATE: template discovery completed without an executable template; dependent execution must not continue.", map(
                "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", "template_discovery",
                "localFactCheckReason", "typed template discovery returned no template metadata",
                "transportSuccess", true, "operationSuccess", true, "businessSatisfied", false,
                "resultCode", "NO_MATCHING_TEMPLATE", "templateDiscoveryReturnedCount", 0,
                "templateDiscoveryStepId", stepId));
            return accepted("Template discovery returned " + count + " candidate template(s); continue to dependent execution step.", map(
                "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", "template_discovery",
                "localFactCheckReason", "typed template discovery returned non-empty template metadata",
                "templateDiscoveryReturnedCount", count, "templateDiscoveryStepId", stepId));
        }
        if (roles.isEnterpriseMetadataSearch(execution.toolName())) {
            Map<String, Object> result = facts.enterpriseMetadataResult(execution.output());
            Map<?, ?> sourceSchema = result.get("sourceSchema") instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> coverage = result.get("coverage") instanceof Map<?, ?> map
                ? stringMap(map) : Map.of();
            int sourceCount = firstPositive(result.get("sourceFieldCount"), sourceSchema.get("fieldCount"),
                size(sourceSchema.get("fields")));
            int processedCount = firstPositive(coverage.get("processedFieldCount"), result.get("matchedFieldCount"),
                size(result.get("fieldMatches")));
            boolean allFieldsProcessed = Boolean.TRUE.equals(bool(coverage.get("allFieldsProcessed")))
                || sourceCount > 0 && processedCount == sourceCount;
            if (sourceCount > 0 && processedCount > 0) return accepted(
                "Enterprise metadata search processed " + processedCount + " of " + sourceCount
                    + " source field(s); preserve the field evidence for review.", map(
                    "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", "enterprise_metadata_fields",
                    "enterpriseMetadataSourceFieldCount", sourceCount,
                    "enterpriseMetadataProcessedFieldCount", processedCount,
                    "enterpriseMetadataAllFieldsProcessed", allFieldsProcessed,
                    "enterpriseMetadataStepId", stepId));
        }
        if (roles.isSqlMetadataSearch(execution.toolName()) || roles.isSqlQueryExecute(execution.toolName())) {
            int count = facts.sqlColumnMetadataCount(execution.output());
            if (count <= 0) return null;
            String evidenceType = roles.isSqlMetadataSearch(execution.toolName())
                ? "sql_metadata_search_columns" : "sql_column_metadata";
            return accepted("SQL metadata result returned " + count
                + " column metadata item(s); structure evidence is valid and must be preserved.", map(
                "localFactCheckHasEvidence", true, "localFactCheckEvidenceType", evidenceType,
                "localFactCheckReason", "tool returned non-empty column metadata",
                "sqlMetadataFactChecked", true, "sqlMetadataColumnCount", count,
                "sqlMetadataStepId", stepId));
        }
        return null;
    }

    private Review accepted(String reason, Map<String, Object> metadata) { return new Review(true, reason, metadata); }
    private Review rejected(String reason, Map<String, Object> metadata) { return new Review(false, reason, metadata); }
    private Object nullable(Object value) { return value == null ? "" : value; }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) if (values[i + 1] != null) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private int firstPositive(Object... values) {
        for (Object value : values) {
            Integer parsed = integer(value);
            if (parsed != null && parsed > 0) return parsed;
        }
        return 0;
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private int size(Object value) {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return 0;
    }

    public interface ToolRoles {
        boolean isWebSearch(String toolName);
        boolean isAssetDiscovery(String toolName);
        boolean isTemplateDiscovery(String toolName);
        boolean isEnterpriseMetadataSearch(String toolName);
        boolean isSqlMetadataSearch(String toolName);
        boolean isSqlQueryExecute(String toolName);
    }

    public record Review(boolean satisfied, String reason, Map<String, Object> metadata) { }
}
