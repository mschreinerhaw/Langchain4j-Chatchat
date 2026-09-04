package com.chatchat.agents.orchestration.analysis.driver;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.protocol.AnalysisArtifactProtocol;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.AnalysisMethodologyContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the source-neutral management context consumed by the Driver model. */
final class AnalysisDriverPipelineContext {

    static final String SCHEMA_VERSION = "analysis_driver_pipeline_context.v1";

    Map<String, Object> build(List<AnalysisSummaryResult> workerReports,
                              List<AnalysisSummaryResult> reducerReports,
                              Map<String, Object> runtimeAttributes,
                              Map<String, Object> metadata) {
        List<AnalysisSummaryResult> workers = workerReports == null ? List.of() : workerReports;
        List<AnalysisSummaryResult> reducers = reducerReports == null ? List.of() : reducerReports;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("operatingModelVersion", DataAnalysisDecisionOperatingModel.SCHEMA_VERSION);
        Map<String, Object> objective = analysisObjective(workers, reducers);
        result.put("analysisObjective", objective);
        result.put("analysisMethodology", objective.getOrDefault("analysisMethodologyContract",
            AnalysisMethodologyContract.enterpriseDefault().toMap()));
        result.put("analysisTree", objective.getOrDefault("analysisTree", Map.of()));
        result.put("methodologyExecutionPolicy", Map.of(
            "driverOwnsMethodSelection", true,
            "workerExecutesAssignedQuestions", true,
            "reducerReconcilesMethodResults", true,
            "requiredReasoningOrder", List.of(
                "QUESTION", "BASELINE", "OVERALL", "DECOMPOSITION", "CONTRIBUTION",
                "EXPLANATION", "VALIDATION", "IMPACT", "CONCLUSION", "ACTION"),
            "missingBaselineEffect", "QUALIFY_DEPENDENT_CLAIMS_DO_NOT_SUPPRESS_SUPPORTED_FINDINGS"));
        result.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY,
            AgentRoleAnalysisContext.fromRuntimeAttributes(runtimeAttributes));
        result.put("rolePipeline", Map.of(
            "worker", Map.of(
                "role", "ANALYST",
                "responsibility", "Analyze assigned governed evidence and produce traceable facts, insights and gaps.",
                "assignments", reports(workers)),
            "supervisor", Map.of(
                "role", "WORK_QUALITY_CONTROLLER",
                "responsibility", "Annotate Worker product quality and technical validity without replacing human analytical judgment.",
                "decisions", value(metadata, "analysisWorkerSupervision", Map.of())),
            "reducer", Map.of(
                "role", "ANALYSIS_MANAGER",
                "responsibility", "Reconcile Worker reports, conflicts, overlap and open evidence gaps.",
                "reports", reports(reducers),
                "admissionDecisions", value(metadata, "analysisReducerAdmissionDecisions", List.of())),
            "driver", Map.of(
                "role", "CHIEF_DECISION_MAKER",
                "responsibility", "Review, challenge, synthesize and decide without reading raw records."),
            "governance", Map.of(
                "role", "EVIDENCE_ANNOTATOR",
                "responsibility", "Attach lineage, evidence strength and uncertainty for human review; do not replace human judgment.")));
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

    private Map<String, Object> analysisObjective(List<AnalysisSummaryResult> workers,
                                                   List<AnalysisSummaryResult> reducers) {
        for (AnalysisSummaryResult report : concat(reducers, workers)) {
            Map<String, Object> contract = map(report.evidence().get("analysisObjectiveContract"));
            if (!contract.isEmpty()) return contract;
        }
        return Map.of();
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
