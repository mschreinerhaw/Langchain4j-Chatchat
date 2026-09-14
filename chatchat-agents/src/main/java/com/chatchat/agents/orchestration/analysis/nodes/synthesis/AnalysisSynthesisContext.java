package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.protocol.AnalysisArtifactProtocol;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;
import com.chatchat.agents.orchestration.analysis.context.SynthesisContextBudget;
import com.chatchat.common.runtime.summary.analysis.contract.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.knowledge.KnowledgeContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the source-neutral management context consumed by the Driver model. */
final class AnalysisSynthesisContext {

    static final String SCHEMA_VERSION = "analysis_driver_pipeline_context.v1";

    Map<String, Object> build(List<AnalysisSummaryResult> workerReports,
                              List<AnalysisSummaryResult> reducerReports,
                              Map<String, Object> runtimeAttributes,
                              Map<String, Object> metadata) {
        return build(workerReports, reducerReports, runtimeAttributes, metadata,
            SynthesisContextBudget.fromRuntime(metadata));
    }

    Map<String, Object> build(List<AnalysisSummaryResult> workerReports,
                              List<AnalysisSummaryResult> reducerReports,
                              Map<String, Object> runtimeAttributes,
                              Map<String, Object> metadata,
                              SynthesisContextBudget budget) {
        List<AnalysisSummaryResult> workers = workerReports == null ? List.of() : workerReports;
        List<AnalysisSummaryResult> reducers = reducerReports == null ? List.of() : reducerReports;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("operatingModelVersion", DataAnalysisDecisionOperatingModel.SCHEMA_VERSION);
        Map<String, Object> objective = analysisObjective(workers, reducers);
        result.put("analysisObjective", objective);
        result.put("analysisMethodology", objective.getOrDefault("analysisMethodologyContract", Map.of()));
        result.put("analysisTree", objective.getOrDefault("analysisTree", Map.of()));
        result.put("methodologyExecutionPolicy", Map.of(
            "source", "ACTIVE_AGENT_ANALYSIS_CONTRACT",
            "planningSelectsMethods", true,
            "analysisInterpretsVerifiedResults", true,
            "mergePreservesEvidence", true));
        result.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY,
            AgentRoleAnalysisContext.fromRuntimeAttributes(runtimeAttributes));
        result.put(KnowledgeContext.RUNTIME_ATTRIBUTE,
            value(runtimeAttributes, KnowledgeContext.RUNTIME_ATTRIBUTE, Map.of()));
        result.put("adaptiveAnalysisPrompt", adaptivePrompt(metadata));
        ModelInputProjection modelInputs = modelAnalysisInputs(
            reducers.isEmpty() ? workers : reducers, budget.pipelineTokens());
        result.put("modelAnalysisInputs", modelInputs.toMap());
        Map<String, Object> completion = map(value(metadata, "datasetCompletionSnapshot", Map.of()));
        Map<String, Object> coverage = new LinkedHashMap<>(completion);
        coverage.put("narrativeOmittedDatasetReferences", modelInputs.omittedSourceReferences());
        coverage.put("narrativeIncludedDatasetReferences", modelInputs.includedSourceReferences());
        coverage.put("narrativeInputEstimatedTokens", modelInputs.estimatedTokens());
        result.put("datasetCoverage", Map.copyOf(coverage));
        result.put("completedAnalysisJudgments", value(metadata,
            "unifiedAnalysisJudgments", collectFirst(reducers.isEmpty() ? workers : reducers,
                "analysisJudgments", Map.of())));
        result.put("methodologyCoverage", collectFirst(reducers.isEmpty() ? workers : reducers,
            "methodologyCoverage", List.of()));
        result.put("nodeInputs", Map.of(
            "analysisProducts", reducers.isEmpty() ? reports(workers) : workerReferences(workers),
            "validation", value(metadata, "analysisWorkerSupervision", Map.of()),
            "mergedFindings", reports(reducers),
            "mergeValidation", value(metadata, "analysisReducerAdmissionDecisions", List.of())));
        result.put("conflictSet", collect(reducers, "conflicts"));
        List<Object> advisoryGaps = gaps(reducers, metadata);
        result.put("evidenceGapCount", advisoryGaps.size());
        result.put("evidenceGaps", advisoryGaps.stream().limit(8).toList());
        result.put("evidenceGapPolicy", Map.of(
            "mode", "ADVISORY_ONLY",
            "publicationEffect", "NONE",
            "instruction", "Use gaps only after supported findings, group repeated gaps, do not enumerate the full gap inventory, and never treat their count as a publication veto."));
        result.put("claimLineage", value(metadata, "analysisLineageGraph", Map.of()));
        List<Object> activeRepairs = new ArrayList<>();
        activeRepairs.addAll(iterable(value(metadata, "analysisActiveRepairRequests", List.of())));
        activeRepairs.addAll(iterable(value(metadata, "analysisDriverRepairRequests", List.of())));
        result.put("activeRepairRequests", List.copyOf(activeRepairs));
        result.put("rawRecordAccess", "PROHIBITED");
        result.put("pipelineFingerprint", ModelProtocolJson.sha256Hex(result));
        return Map.copyOf(result);
    }

    private Map<String, Object> adaptivePrompt(Map<String, Object> metadata) {
        Map<String, Object> prompt = new LinkedHashMap<>(map(value(metadata,
            "adaptiveAnalysisPromptContract", Map.of())));
        // Legacy contracts may carry report-section hints. Analysis guidance is
        // retained, but final presentation is derived only after findings exist.
        prompt.remove("output");
        prompt.remove("sectionTitles");
        return Map.copyOf(prompt);
    }

    private Map<String, Object> analysisObjective(List<AnalysisSummaryResult> workers,
                                                   List<AnalysisSummaryResult> reducers) {
        for (AnalysisSummaryResult report : concat(reducers, workers)) {
            Map<String, Object> contract = map(report.evidence().get("analysisObjectiveContract"));
            if (!contract.isEmpty()) return contract;
        }
        return Map.of();
    }

    private ModelInputProjection modelAnalysisInputs(List<AnalysisSummaryResult> sources,
                                                      int tokenBudget) {
        ContextTokenEstimator estimator = new ContextTokenEstimator();
        long remaining = Math.max(0, tokenBudget);
        List<Map<String, Object>> reports = new ArrayList<>();
        List<String> included = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        List<AnalysisSummaryResult> prioritized = sources.stream().filter(java.util.Objects::nonNull)
            .sorted(java.util.Comparator.comparingLong(this::priorityScore).reversed()
                .thenComparing(this::sourceReference)).toList();
        for (int index = 0; index < prioritized.size(); index++) {
            AnalysisSummaryResult source = prioritized.get(index);
            String sourceReference = sourceReference(source);
            if (remaining <= 0) { omitted.add(sourceReference); continue; }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportId", source.resultId());
            item.put("sourceScope", sourceReference);
            // Carry declared definitions, not raw records or inferred domain semantics.
            Object semantics = source.evidence().get("analysisSemanticContract");
            if (semantics == null) semantics = new com.chatchat.agents.orchestration.analysis.contract.AnalysisSemanticContractCompiler()
                .compile(source.analysisContext());
            String declared = ModelProtocolJson.compact(semantics);
            long fairShare = Math.max(64, remaining / Math.max(1, prioritized.size() - index));
            int semanticChars = fittedPrefix(declared, Math.max(32, fairShare / 2), estimator);
            item.put("declaredSemantics", declared.substring(0, semanticChars));
            item.put("semanticsTruncated", semanticChars < declared.length());
            String narrative = source.content() == null ? "" : source.content();
            boolean eligible = com.chatchat.agents.orchestration.analysis.governance.AnalysisOutputAdmissionPolicy
                .admitWorkerNarrative(narrative).admitted();
            int narrativeChars = eligible ? fittedPrefix(narrative, Math.max(32, fairShare / 2), estimator) : 0;
            item.put("modelNarrative", narrative.substring(0, narrativeChars));
            item.put("narrativeTruncated", eligible && narrativeChars < narrative.length());
            item.put("narrativeEligible", eligible);
            Map<String, Object> immutable = Map.copyOf(item);
            long itemTokens = estimator.estimate(immutable).tokens();
            if (itemTokens > remaining) {
                omitted.add(sourceReference);
                continue;
            }
            remaining -= itemTokens;
            reports.add(immutable);
            included.add(sourceReference);
        }
        List<String> includedReferences = included.stream().distinct().toList();
        List<String> omittedReferences = omitted.stream().distinct()
            .filter(reference -> !includedReferences.contains(reference)).toList();
        return new ModelInputProjection(List.copyOf(reports), includedReferences,
            omittedReferences, Math.max(0, tokenBudget - remaining));
    }

    private long priorityScore(AnalysisSummaryResult source) {
        Map<String, Object> evidence = source.evidence() == null ? Map.of() : source.evidence();
        long conflicts = iterable(evidence.get("conflicts")).size();
        long claims = iterable(evidence.get("claimAdmissionDecisions")).size()
            + iterable(evidence.get("analysisItems")).size()
            + iterable(evidence.get("observedFactClaims")).size();
        Object records = source.position().get("recordCount");
        long recordCount = records instanceof Number number ? Math.max(0, number.longValue()) : 0;
        return conflicts * 1_000_000L + claims * 10_000L + Math.min(9_999, recordCount);
    }

    private String sourceReference(AnalysisSummaryResult source) {
        return String.valueOf(source.position().getOrDefault("datasetReference", source.scope()));
    }

    private int fittedPrefix(String value, long tokenBudget, ContextTokenEstimator estimator) {
        if (value == null || value.isEmpty() || tokenBudget <= 0) return 0;
        if (estimator.estimate(value).tokens() <= tokenBudget) return value.length();
        int low = 0, high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (estimator.estimate(value.substring(0, middle)).tokens() <= tokenBudget) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private record ModelInputProjection(List<Map<String, Object>> reports,
                                        List<String> includedSourceReferences,
                                        List<String> omittedSourceReferences,
                                        long estimatedTokens) {
        Map<String, Object> toMap() {
            return Map.of("reports", reports, "omittedReportCount", omittedSourceReferences.size(),
                "omittedSourceReferences", omittedSourceReferences,
                "includedSourceReferences", includedSourceReferences,
                "estimatedTokens", estimatedTokens,
                "purpose", "Model analysis and producer-declared semantics; verify conclusions against the evidence ledger. "
                    + "Truncated inputs are excerpts, not complete definitions. Missing semantics remain unknown.");
        }
    }

    // Consolidated reports already carry analytical content. Keep upstream identities for audit
    // instead of replaying all Worker contracts and artifacts into the same Driver prompt.
    private List<Map<String, Object>> workerReferences(List<AnalysisSummaryResult> workers) {
        return workers.stream().filter(java.util.Objects::nonNull)
            .map(report -> Map.<String, Object>of("reportId", report.resultId(), "scope", report.scope()))
            .toList();
    }

    private List<Map<String, Object>> reports(List<AnalysisSummaryResult> reports) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnalysisSummaryResult report : reports) {
            if (report == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportId", report.resultId());
            item.put("scope", report.scope());
            item.put("responsibility", report.evidence().getOrDefault(
                "analysisObjectiveContract", Map.of()));
            item.put("demandAnalysis", report.evidence().getOrDefault("demandAnalysis", Map.of()));
            item.put("analysisItems", report.evidence().getOrDefault("analysisItems", List.of()));
            item.put("analysisMethodExecution",
                report.evidence().getOrDefault("analysisMethodExecution", Map.of()));
            item.put(AnalysisArtifactProtocol.EVIDENCE_KEY,
                AnalysisArtifactProtocol.normalize(report));
            item.put("admission", report.governance());
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private List<Object> gaps(List<AnalysisSummaryResult> reports, Map<String, Object> metadata) {
        List<Object> result = new ArrayList<>();
        result.addAll(iterable(value(metadata, "analysisGapRequests", List.of())));
        result.addAll(iterable(value(metadata, "analysisActiveRepairRequests", List.of())));
        for (AnalysisSummaryResult report : reports) {
            result.addAll(iterable(report.evidence().get("semanticGapRequests")));
            result.addAll(iterable(report.evidence().get("missingEvidence")));
        }
        return List.copyOf(result);
    }

    private List<Object> collect(List<AnalysisSummaryResult> reports, String key) {
        List<Object> result = new ArrayList<>();
        for (AnalysisSummaryResult report : reports) {
            result.addAll(iterable(report.evidence().get(key)));
        }
        return List.copyOf(result);
    }

    private Object collectFirst(List<AnalysisSummaryResult> reports, String key, Object fallback) {
        for (AnalysisSummaryResult report : reports) {
            if (report != null && report.evidence().containsKey(key)) return report.evidence().get(key);
        }
        return fallback;
    }

    private List<AnalysisSummaryResult> concat(List<AnalysisSummaryResult> first,
                                                List<AnalysisSummaryResult> second) {
        List<AnalysisSummaryResult> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private Object value(Map<String, Object> source, String key, Object fallback) {
        return source == null ? fallback : source.getOrDefault(key, fallback);
    }

    private List<?> iterable(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Object> result = new ArrayList<>();
        iterable.forEach(result::add);
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return Map.copyOf(result);
    }
}
