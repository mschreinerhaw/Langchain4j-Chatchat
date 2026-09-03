package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLayerGovernanceContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Admits Reducer reports before the management-level Driver can consume them. */
final class AnalysisReducerSupervisor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReducerSupervisor.class);

    Review inspect(List<AnalysisSummaryResult> candidates) {
        List<AnalysisSummaryResult> admitted = new ArrayList<>();
        List<Map<String, Object>> decisions = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();
        for (AnalysisSummaryResult candidate : candidates == null ? List.<AnalysisSummaryResult>of() : candidates) {
            Inspection inspection = inspect(candidate);
            decisions.add(inspection.admission().toMap());
            repairs.addAll(inspection.repairs().stream()
                .map(DataAnalysisLayerGovernanceContract.RepairRequest::toMap).toList());
            // Governance grades the Reducer product; it does not decide on behalf of the
            // human whether a readable analysis may be reviewed by the Driver. Preserve
            // non-empty analysis products with their admission status and review notes.
            boolean reviewable = candidate != null
                && candidate.content() != null
                && !candidate.content().isBlank()
                && AnalysisOutputAdmissionPolicy.admitWorkerNarrative(
                    candidate.content()).admitted();
            if (reviewable) {
                List<Map<String, Object>> claimTransitions = inspection.admission()
                    .admittedClaimIds().stream()
                    .map(claimId -> new DataAnalysisLayerGovernanceContract.ClaimTransition(
                        "", claimId, DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
                        DataAnalysisLayerGovernanceContract.State.ADMITTED,
                        DataAnalysisLayerGovernanceContract.State.SYNTHESIZED,
                        inspection.admission().inputReportIds(),
                        "Claim was retained by an admitted Reducer report.").toMap())
                    .toList();
                admitted.add(candidate.withEvidence(Map.of(
                    "analysisReportAdmission", inspection.admission().toMap(),
                    "analysisEvidenceLineage", inspection.lineage().stream()
                        .map(DataAnalysisLayerGovernanceContract.LineageEdge::toMap).toList(),
                    "analysisClaimLifecycle", claimTransitions,
                    "analysisGovernanceAdvisoryOnly", true,
                    "analysisHumanReviewRequired", !inspection.admission().admitted(),
                    "analysisRepairRequests", inspection.repairs().stream()
                        .map(DataAnalysisLayerGovernanceContract.RepairRequest::toMap).toList())));
            }
            log.info("analysisReducerProductSupervision reportId={} admitted={} state={} reasons={} repairCount={}",
                candidate == null ? "missing" : candidate.resultId(), inspection.admission().admitted(),
                inspection.admission().state(), inspection.admission().reasons(), inspection.repairs().size());
        }
        long flagged = decisions.stream()
            .filter(decision -> !Boolean.TRUE.equals(decision.get("admitted"))).count();
        return new Review(List.copyOf(admitted), List.copyOf(decisions), List.copyOf(repairs),
            Math.toIntExact(flagged));
    }

    private Inspection inspect(AnalysisSummaryResult candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate == null) {
            reasons.add("MISSING_REDUCER_REPORT");
            return rejected("missing-reducer-report", reasons);
        }
        Map<String, Object> evidence = candidate.evidence();
        if (candidate.content() == null || candidate.content().isBlank()) {
            reasons.add("EMPTY_REDUCER_REPORT");
        }
        if (!DataAnalysisDecisionOperatingModel.SCHEMA_VERSION.equals(
            evidence.get("analysisDecisionOperatingModelVersion"))) {
            reasons.add("OPERATING_MODEL_VERSION_MISSING");
        }
        if (!DataAnalysisDecisionOperatingModel.ParticipantRole.REDUCER.name().equals(
            evidence.get("analysisParticipantRole"))) {
            reasons.add("REDUCER_ROLE_NOT_DECLARED");
        }
        if (!Boolean.TRUE.equals(evidence.get("managementReviewInput"))) {
            reasons.add("MANAGEMENT_REVIEW_INPUT_NOT_DECLARED");
        }
        if (candidate.inputSummaryResultIds().isEmpty()) {
            reasons.add("UPSTREAM_REPORT_LINEAGE_MISSING");
        }
        List<String> lineageInputs = strings(evidence.get("inputSummaryResultIds"));
        if (!lineageInputs.isEmpty()
            && !lineageInputs.containsAll(candidate.inputSummaryResultIds())) {
            reasons.add("UPSTREAM_REPORT_LINEAGE_INCOMPLETE");
        }

        List<String> claimIds = new ArrayList<>(claimIds(evidence.get("insights")));
        claimIds.addAll(claimIds(evidence.get("observedFactClaims")));
        claimIds = claimIds.stream().distinct().toList();
        List<DataAnalysisLayerGovernanceContract.LineageEdge> lineage = candidate.inputSummaryResultIds().stream()
            .map(input -> new DataAnalysisLayerGovernanceContract.LineageEdge(
                "", input, candidate.resultId(),
                DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM,
                DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT))
            .toList();
        // Evidence gaps belong to the report's advisory context. They inform the Driver and
        // human reviewer, but they are not proof that an otherwise valid Reducer report is
        // defective and must never be promoted into an automatic repair/publication barrier.
        List<DataAnalysisLayerGovernanceContract.RepairRequest> repairs = List.of();
        boolean accepted = reasons.isEmpty();
        DataAnalysisLayerGovernanceContract.Admission admission =
            new DataAnalysisLayerGovernanceContract.Admission(
                "", candidate.resultId(), DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
                accepted ? DataAnalysisLayerGovernanceContract.State.ADMITTED
                    : DataAnalysisLayerGovernanceContract.State.NEEDS_EVIDENCE,
                accepted, reasons, candidate.inputSummaryResultIds(), claimIds);
        if (!accepted) repairs = List.of(reducerRepair(candidate.resultId(), reasons));
        return new Inspection(admission, lineage, repairs);
    }

    private Inspection rejected(String reportId, List<String> reasons) {
        DataAnalysisLayerGovernanceContract.Admission admission =
            new DataAnalysisLayerGovernanceContract.Admission(
                "", reportId, DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
                DataAnalysisLayerGovernanceContract.State.NEEDS_EVIDENCE, false,
                reasons, List.of(), List.of());
        return new Inspection(admission, List.of(), List.of(reducerRepair(reportId, reasons)));
    }

    private DataAnalysisLayerGovernanceContract.RepairRequest reducerRepair(
        String reportId, List<String> reasons) {
        return new DataAnalysisLayerGovernanceContract.RepairRequest(
            "", reportId, DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT,
            DataAnalysisLayerGovernanceContract.RepairRoute.RERUN_REDUCER,
            "Rebuild the management input from admitted Worker reports with complete lineage.",
            reasons, List.of(), List.of(), "", "",
            DataAnalysisLayerGovernanceContract.Layer.REDUCER_REPORT);
    }

    private List<String> claimIds(Object value) {
        List<String> result = new ArrayList<>();
        collectClaimIds(value, result);
        return result.stream().distinct().toList();
    }

    private void collectClaimIds(Object value, List<String> target) {
        if (value instanceof Map<?, ?> map) {
            Object claimId = map.get("claimId");
            if (claimId != null && !String.valueOf(claimId).isBlank()) {
                target.add(String.valueOf(claimId));
            }
            map.values().forEach(item -> collectClaimIds(item, target));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectClaimIds(item, target));
        }
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        collectStrings(value, result);
        return result;
    }

    private void collectStrings(Object value, List<String> target) {
        if (value instanceof String text && !text.isBlank()) {
            target.add(text.trim());
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectStrings(item, target));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectStrings(item, target));
        }
    }

    record Review(List<AnalysisSummaryResult> admittedInputs,
                  List<Map<String, Object>> admissionDecisions,
                  List<Map<String, Object>> repairRequests,
                  int rejectedCount) {
    }

    private record Inspection(
        DataAnalysisLayerGovernanceContract.Admission admission,
        List<DataAnalysisLayerGovernanceContract.LineageEdge> lineage,
        List<DataAnalysisLayerGovernanceContract.RepairRequest> repairs) {
    }
}
