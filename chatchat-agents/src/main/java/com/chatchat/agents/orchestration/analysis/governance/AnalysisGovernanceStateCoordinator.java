package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLayerGovernanceContract;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLineageGraph;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisRepairExecutionPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reconciles repair budgets, claim revisions and the queryable cross-layer lineage graph per round. */
final class AnalysisGovernanceStateCoordinator {

    private final DataAnalysisRepairExecutionPolicy repairPolicy =
        new DataAnalysisRepairExecutionPolicy();

    State reconcile(List<AnalysisSummaryResult> admittedReports,
                    List<Map<String, Object>> repairRequests,
                    Map<String, Object> metadata) {
        Map<String, Map<String, Object>> previousRepairs = indexMaps(
            metadata == null ? null : metadata.get("analysisRepairExecutionStates"), "requestId");
        String evidenceVersion = DataAnalysisLayerGovernanceContract.fingerprint(
            admittedReports == null ? List.of() : admittedReports.stream()
                .map(report -> List.of(report.resultId(), report.inputSummaryResultIds(),
                    report.evidence().getOrDefault("inputEvidenceIds", List.of())))
                .toList());
        List<Map<String, Object>> repairStates = new ArrayList<>();
        List<Map<String, Object>> activeRepairs = new ArrayList<>();
        List<String> observedRepairIds = new ArrayList<>();
        DataAnalysisRepairExecutionPolicy.Budget budget = repairBudget(
            metadata == null ? null : metadata.get("analysisRepairBudget"));
        for (Map<String, Object> repair : repairRequests == null
            ? List.<Map<String, Object>>of() : repairRequests) {
            String requestId = text(repair.get("requestId"));
            observedRepairIds.add(requestId);
            String gapFingerprint = DataAnalysisLayerGovernanceContract.fingerprint(List.of(
                repair.getOrDefault("missingEvidence", List.of()),
                repair.getOrDefault("requiredFields", List.of()),
                repair.getOrDefault("requiredTimeScope", ""),
                repair.getOrDefault("requiredGrain", "")));
            DataAnalysisRepairExecutionPolicy.State previous = repairState(previousRepairs.get(requestId));
            DataAnalysisRepairExecutionPolicy.State current = repairPolicy.evaluate(
                requestId, gapFingerprint, evidenceVersion,
                integer(repair.get("modelCallCount")), integer(repair.get("toolCallCount")),
                longValue(repair.get("elapsedMs")), Boolean.TRUE.equals(repair.get("resolved")),
                previous, budget);
            repairStates.add(current.toMap());
            if (current.executable()) activeRepairs.add(repair);
        }
        previousRepairs.forEach((requestId, value) -> {
            if (observedRepairIds.contains(requestId)) return;
            DataAnalysisRepairExecutionPolicy.State previous = repairState(value);
            DataAnalysisRepairExecutionPolicy.State current = repairPolicy.evaluate(
                requestId, previous.gapFingerprint(), evidenceVersion, 0, 0, 0L,
                true, previous, budget);
            repairStates.add(current.toMap());
        });

        List<Map<String, Object>> claimRevisions = claimRevisions(admittedReports, evidenceVersion,
            metadata == null ? null : metadata.get("analysisClaimRevisions"));
        DataAnalysisLineageGraph graph = lineageGraph(admittedReports, claimRevisions);
        if (metadata != null) {
            metadata.put("analysisRepairExecutionStates", List.copyOf(repairStates));
            metadata.put("analysisRepairExecutionHistory", appendHistory(
                metadata.get("analysisRepairExecutionHistory"), repairStates, "requestId", "round"));
            metadata.put("analysisActiveRepairRequests", List.copyOf(activeRepairs));
            metadata.put("analysisRepairTerminalCount", repairStates.size() - activeRepairs.size());
            metadata.put("analysisClaimRevisions", claimRevisions);
            metadata.put("analysisClaimRevisionHistory", appendHistory(
                metadata.get("analysisClaimRevisionHistory"), claimRevisions, "claimId", "revision"));
            metadata.put("analysisLineageGraph", graph.toMap());
            metadata.put("analysisLineageGraphSchemaVersion", DataAnalysisLineageGraph.SCHEMA_VERSION);
        }
        return new State(List.copyOf(activeRepairs), List.copyOf(repairStates),
            claimRevisions, graph);
    }

    private List<Map<String, Object>> claimRevisions(List<AnalysisSummaryResult> reports,
                                                      String evidenceVersion,
                                                      Object previousValue) {
        Map<String, Map<String, Object>> previous = indexMaps(previousValue, "claimId");
        List<Map<String, Object>> current = new ArrayList<>();
        for (AnalysisSummaryResult report : reports == null ? List.<AnalysisSummaryResult>of() : reports) {
            Map<String, Object> admission = map(report.evidence().get("analysisReportAdmission"));
            for (String claimId : strings(admission.get("admittedClaimIds"))) {
                Map<String, Object> prior = previous.get(claimId);
                int revision = integer(prior == null ? null : prior.get("revision")) + 1;
                DataAnalysisLayerGovernanceContract.ClaimRevision value =
                    new DataAnalysisLayerGovernanceContract.ClaimRevision(
                        "", claimId, revision,
                        prior == null ? "" : text(prior.get("revisionId")), evidenceVersion,
                        DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
                        DataAnalysisLayerGovernanceContract.State.SYNTHESIZED);
                current.add(value.toMap());
            }
        }
        return List.copyOf(current);
    }

    private DataAnalysisLineageGraph lineageGraph(List<AnalysisSummaryResult> reports,
                                                   List<Map<String, Object>> revisions) {
        Map<String, DataAnalysisLineageGraph.Node> nodes = new LinkedHashMap<>();
        List<DataAnalysisLayerGovernanceContract.LineageEdge> edges = new ArrayList<>();
        for (AnalysisSummaryResult report : reports == null ? List.<AnalysisSummaryResult>of() : reports) {
            nodes.put(report.resultId(), new DataAnalysisLineageGraph.Node(
                report.resultId(), "REPORT", Map.of("scope", report.scope(), "outcome", report.outcome())));
            for (String input : report.inputSummaryResultIds()) {
                nodes.putIfAbsent(input, new DataAnalysisLineageGraph.Node(input, "REPORT", Map.of()));
                edges.add(new DataAnalysisLayerGovernanceContract.LineageEdge(
                    "", input, report.resultId(),
                    DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM,
                    DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT));
            }
            for (String evidenceId : strings(report.evidence().get("inputEvidenceIds"))) {
                nodes.putIfAbsent(evidenceId,
                    new DataAnalysisLineageGraph.Node(evidenceId, "EVIDENCE", Map.of()));
                edges.add(new DataAnalysisLayerGovernanceContract.LineageEdge(
                    "", evidenceId, report.resultId(),
                    DataAnalysisLayerGovernanceContract.Relation.SUPPORTS,
                    DataAnalysisLayerGovernanceContract.Layer.EVIDENCE));
            }
        }
        for (Map<String, Object> revision : revisions) {
            String claimId = text(revision.get("claimId"));
            nodes.putIfAbsent(claimId, new DataAnalysisLineageGraph.Node(
                claimId, "CLAIM", Map.of("revisionId", revision.get("revisionId"),
                    "revision", revision.get("revision"))));
            for (AnalysisSummaryResult report : reports == null ? List.<AnalysisSummaryResult>of() : reports) {
                Map<String, Object> admission = map(report.evidence().get("analysisReportAdmission"));
                if (strings(admission.get("admittedClaimIds")).contains(claimId)) {
                    edges.add(new DataAnalysisLayerGovernanceContract.LineageEdge(
                        "", claimId, report.resultId(),
                        DataAnalysisLayerGovernanceContract.Relation.SUPPORTS,
                        DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT));
                }
            }
        }
        return new DataAnalysisLineageGraph(List.copyOf(nodes.values()), edges);
    }

    private DataAnalysisRepairExecutionPolicy.State repairState(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return null;
        return new DataAnalysisRepairExecutionPolicy.State(
            text(value.get("requestId")), integer(value.get("round")),
            integer(value.get("attemptCount")), integer(value.get("modelCallCount")),
            integer(value.get("toolCallCount")), longValue(value.get("elapsedMs")),
            text(value.get("gapFingerprint")), text(value.get("evidenceVersion")),
            enumValue(DataAnalysisRepairExecutionPolicy.Status.class, value.get("status"),
                DataAnalysisRepairExecutionPolicy.Status.TERMINAL),
            enumValue(DataAnalysisRepairExecutionPolicy.TerminalReason.class,
                value.get("terminalReason"), DataAnalysisRepairExecutionPolicy.TerminalReason.NONE),
            DataAnalysisRepairExecutionPolicy.Budget.DEFAULT);
    }

    private DataAnalysisRepairExecutionPolicy.Budget repairBudget(Object value) {
        Map<String, Object> map = map(value);
        if (map.isEmpty()) return DataAnalysisRepairExecutionPolicy.Budget.DEFAULT;
        return new DataAnalysisRepairExecutionPolicy.Budget(
            positive(map.get("maximumAttempts"), 2), nonNegative(map.get("maximumModelCalls"), 2),
            nonNegative(map.get("maximumToolCalls"), 2), positiveLong(map.get("maximumElapsedMs"), 300_000L));
    }

    private List<Map<String, Object>> appendHistory(Object previousValue,
                                                     List<Map<String, Object>> current,
                                                     String identityKey,
                                                     String versionKey) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        if (previousValue instanceof Iterable<?> iterable) iterable.forEach(item -> {
            Map<String, Object> value = map(item);
            indexed.put(text(value.get(identityKey)) + ":" + text(value.get(versionKey)), value);
        });
        current.forEach(value -> indexed.put(
            text(value.get(identityKey)) + ":" + text(value.get(versionKey)), value));
        return List.copyOf(indexed.values());
    }

    private Map<String, Map<String, Object>> indexMaps(Object value, String key) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (value instanceof Iterable<?> iterable) iterable.forEach(item -> {
            Map<String, Object> map = map(item);
            String id = text(map.get(key));
            if (!id.isBlank()) result.put(id, map);
        });
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(item -> { if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item)); });
        return result.stream().distinct().toList();
    }
    private int integer(Object value) { return value instanceof Number n ? n.intValue() : parseInt(value); }
    private int parseInt(Object value) { try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 0; } }
    private long longValue(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private int positive(Object value, int fallback) { int parsed = integer(value); return parsed > 0 ? parsed : fallback; }
    private int nonNegative(Object value, int fallback) { return value == null ? fallback : Math.max(0, integer(value)); }
    private long positiveLong(Object value, long fallback) { long parsed = longValue(value); return parsed > 0 ? parsed : fallback; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        try { return value == null ? fallback : Enum.valueOf(type, String.valueOf(value)); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    record State(List<Map<String, Object>> activeRepairRequests,
                 List<Map<String, Object>> repairExecutionStates,
                 List<Map<String, Object>> claimRevisions,
                 DataAnalysisLineageGraph lineageGraph) { }
}
