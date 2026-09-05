package com.chatchat.common.runtime.summary.analysis.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-neutral governance protocol shared by every analysis layer.
 *
 * <p>It models report admission, cross-layer evidence lineage and executable repair intent without
 * prescribing a business domain, a storage engine or a concrete retrieval tool.</p>
 */
public final class DataAnalysisLayerGovernanceContract {

    public static final String SCHEMA_VERSION = "data_analysis_layer_governance.v1";
    public static final String LINEAGE_SCHEMA_VERSION = "analysis_evidence_lineage.v1";
    public static final String REPAIR_SCHEMA_VERSION = "analysis_repair_request.v1";

    private DataAnalysisLayerGovernanceContract() {
    }

    public enum Layer { EVIDENCE, WORKER_REPORT, REDUCER_REPORT, DRIVER_DECISION }

    public enum State {
        PROPOSED,
        EVIDENCE_VALIDATED,
        ADMITTED,
        SYNTHESIZED,
        PUBLISHED,
        REJECTED,
        SUPERSEDED,
        CONFLICTED,
        NEEDS_EVIDENCE
    }

    public enum Relation { DERIVED_FROM, SUPPORTS, CONTRADICTS, SUPERSEDES }

    public enum RepairRoute { REANALYZE_WORKER, RERUN_REDUCER, REPLAN_EVIDENCE, PUBLISH_WITH_LIMITATIONS }

    public record Admission(
        String admissionId,
        String reportId,
        Layer layer,
        State state,
        boolean admitted,
        List<String> reasons,
        List<String> inputReportIds,
        List<String> admittedClaimIds
    ) {
        public Admission {
            reportId = text(reportId);
            layer = layer == null ? Layer.EVIDENCE : layer;
            state = state == null ? State.REJECTED : state;
            reasons = values(reasons);
            inputReportIds = values(inputReportIds);
            admittedClaimIds = values(admittedClaimIds);
            admissionId = text(admissionId);
            if (admissionId.isBlank()) {
                admissionId = "analysis-admission:" + fingerprint(List.of(
                    reportId, layer.name(), state.name(), String.join("|", reasons),
                    String.join("|", inputReportIds), String.join("|", admittedClaimIds)));
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("admissionId", admissionId);
            value.put("reportId", reportId);
            value.put("layer", layer.name());
            value.put("state", state.name());
            value.put("admitted", admitted);
            value.put("reasons", reasons);
            value.put("inputReportIds", inputReportIds);
            value.put("admittedClaimIds", admittedClaimIds);
            return Map.copyOf(value);
        }
    }

    public record LineageEdge(
        String edgeId,
        String fromId,
        String toId,
        Relation relation,
        Layer producerLayer
    ) {
        public LineageEdge {
            fromId = text(fromId);
            toId = text(toId);
            relation = relation == null ? Relation.DERIVED_FROM : relation;
            producerLayer = producerLayer == null ? Layer.EVIDENCE : producerLayer;
            edgeId = text(edgeId);
            if (edgeId.isBlank()) {
                edgeId = "analysis-lineage:" + fingerprint(List.of(
                    fromId, toId, relation.name(), producerLayer.name()));
            }
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "schemaVersion", LINEAGE_SCHEMA_VERSION,
                "edgeId", edgeId,
                "fromId", fromId,
                "toId", toId,
                "relation", relation.name(),
                "producerLayer", producerLayer.name());
        }
    }

    public record ClaimTransition(
        String transitionId,
        String claimId,
        Layer layer,
        State fromState,
        State toState,
        List<String> sourceReportIds,
        String reason
    ) {
        public ClaimTransition {
            claimId = text(claimId);
            layer = layer == null ? Layer.EVIDENCE : layer;
            fromState = fromState == null ? State.PROPOSED : fromState;
            toState = toState == null ? State.REJECTED : toState;
            sourceReportIds = values(sourceReportIds);
            reason = text(reason);
            transitionId = text(transitionId);
            if (transitionId.isBlank()) {
                transitionId = "analysis-claim-transition:" + fingerprint(List.of(
                    claimId, layer.name(), fromState.name(), toState.name(),
                    String.join("|", sourceReportIds), reason));
            }
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "transitionId", transitionId,
                "claimId", claimId,
                "layer", layer.name(),
                "fromState", fromState.name(),
                "toState", toState.name(),
                "sourceReportIds", sourceReportIds,
                "reason", reason);
        }
    }

    public record ClaimRevision(String revisionId, String claimId, int revision,
                                String parentRevisionId, String evidenceVersion,
                                Layer layer, State state) {
        public ClaimRevision {
            claimId = text(claimId);
            revision = Math.max(1, revision);
            parentRevisionId = text(parentRevisionId);
            evidenceVersion = text(evidenceVersion);
            layer = layer == null ? Layer.EVIDENCE : layer;
            state = state == null ? State.REJECTED : state;
            revisionId = text(revisionId);
            if (revisionId.isBlank()) {
                revisionId = "analysis-claim-revision:" + fingerprint(List.of(
                    claimId, String.valueOf(revision), parentRevisionId,
                    evidenceVersion, layer.name(), state.name()));
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION);
            value.put("revisionId", revisionId);
            value.put("claimId", claimId);
            value.put("revision", revision);
            if (!parentRevisionId.isBlank()) value.put("parentRevisionId", parentRevisionId);
            value.put("evidenceVersion", evidenceVersion);
            value.put("layer", layer.name());
            value.put("state", state.name());
            return Map.copyOf(value);
        }
    }

    public record RepairRequest(
        String requestId,
        String rejectedReportId,
        Layer targetLayer,
        RepairRoute route,
        String goal,
        List<String> missingEvidence,
        List<String> requiredCapabilities,
        List<String> requiredFields,
        String requiredTimeScope,
        String requiredGrain,
        Layer resumeAt
    ) {
        public RepairRequest {
            rejectedReportId = text(rejectedReportId);
            targetLayer = targetLayer == null ? Layer.WORKER_REPORT : targetLayer;
            route = route == null ? RepairRoute.REANALYZE_WORKER : route;
            goal = text(goal);
            missingEvidence = values(missingEvidence);
            requiredCapabilities = values(requiredCapabilities);
            requiredFields = values(requiredFields);
            requiredTimeScope = text(requiredTimeScope);
            requiredGrain = text(requiredGrain);
            resumeAt = resumeAt == null ? targetLayer : resumeAt;
            requestId = text(requestId);
            if (requestId.isBlank()) {
                requestId = "analysis-repair:" + fingerprint(List.of(
                    rejectedReportId, targetLayer.name(), route.name(), goal,
                    String.join("|", missingEvidence), String.join("|", requiredFields),
                    requiredTimeScope, requiredGrain, resumeAt.name()));
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", REPAIR_SCHEMA_VERSION);
            value.put("requestId", requestId);
            value.put("rejectedReportId", rejectedReportId);
            value.put("targetLayer", targetLayer.name());
            value.put("route", route.name());
            value.put("goal", goal);
            value.put("missingEvidence", missingEvidence);
            value.put("requiredCapabilities", requiredCapabilities);
            value.put("requiredFields", requiredFields);
            if (!requiredTimeScope.isBlank()) value.put("requiredTimeScope", requiredTimeScope);
            if (!requiredGrain.isBlank()) value.put("requiredGrain", requiredGrain);
            value.put("resumeAt", resumeAt.name());
            return Map.copyOf(value);
        }
    }

    public static String fingerprint(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static List<String> values(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
