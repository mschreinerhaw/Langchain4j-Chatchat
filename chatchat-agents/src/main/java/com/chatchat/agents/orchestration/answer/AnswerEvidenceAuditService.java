package com.chatchat.agents.orchestration.answer;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns claim binding, evidence posture and the backend audit envelope. */
final class AnswerEvidenceAuditService {

    static final String AUDIT_CONTRACT = "answer_evidence_audit_v1";
    static final String AUDIT_ENVELOPE = "answer_evidence_audit.v2";

    private final AnswerEvidenceLedgerCompiler ledgerCompiler;
    private final AnswerUserFacingPolicy userFacingPolicy;

    AnswerEvidenceAuditService(AnswerEvidenceLedgerCompiler ledgerCompiler,
                               AnswerUserFacingPolicy userFacingPolicy) {
        this.ledgerCompiler = ledgerCompiler;
        this.userFacingPolicy = userFacingPolicy;
    }

    String attachLedger(String answer, Map<String, Object> metadata,
                        List<String> observations, List<Map<String, Object>> toolEvidence) {
        if (metadata == null) return answer;
        AnswerEvidenceLedgerCompiler.Result result = ledgerCompiler.compile(
            answer, metadata, observations, toolEvidence);
        metadata.put("claimLedger", result.claimLedger());
        metadata.put("claimLedgerVersion", AnswerEvidenceLedgerCompiler.CLAIM_LEDGER_VERSION);
        metadata.put("evidenceManifest", result.evidenceManifest());
        metadata.put("evidenceManifestVersion", AnswerEvidenceLedgerCompiler.EVIDENCE_MANIFEST_VERSION);
        metadata.put("claimCoverage", result.coverage());
        metadata.put("claimCoverageStatus", result.status());
        metadata.put("answerClaimAuditPassed",
            "PASS".equals(result.status()) || "NOT_APPLICABLE".equals(result.status()));
        if (!"FAIL".equals(result.status())) return answer;
        metadata.putIfAbsent("groundingStatus", "needs_review");
        metadata.putIfAbsent("answerEvidenceStatus", "PARTIAL");
        metadata.putIfAbsent("answerEvidenceUserVisible", true);
        List<String> limitations = strings(metadata.get("answerEvidenceLimitations"));
        if (result.criticalUnboundClaims() > 0) limitations.add("CRITICAL_CLAIM_WITHOUT_EVIDENCE_BINDING");
        if (result.unknownReferences() > 0) limitations.add("UNKNOWN_EVIDENCE_REFERENCE");
        metadata.put("answerEvidenceLimitations", limitations.stream().distinct().toList());
        if (Boolean.TRUE.equals(metadata.get("deterministicMandatoryWorkflowFailure"))
            || Boolean.TRUE.equals(metadata.get("fatalExecutionBlocked"))) {
            metadata.put("answerEvidenceUserVisible", false);
            metadata.put("evidenceWarningSuppressedForExecutionFailure", true);
            return answer;
        }
        if (answer != null && !answer.contains("证据完整性提示")) {
            return answer + "\n\n> **证据完整性提示**：部分关键结论尚未与本次返回证据逐条绑定，"
                + "或引用无法核验。相关内容应视为待核验分析，不宜直接作为决策依据。";
        }
        return answer;
    }

    String bindReturnedEvidence(String answer, Map<String, Object> metadata,
                                List<String> observations, List<Map<String, Object>> toolEvidence,
                                String phase) {
        AnswerEvidenceLedgerCompiler.BindingResult binding = ledgerCompiler.bindReturnedEvidence(
            answer, metadata, observations, toolEvidence);
        if (metadata != null && binding.boundClaimCount() > 0) {
            metadata.put("answerEvidenceBindingApplied", true);
            metadata.put("answerEvidenceBindingContractVersion", AnswerEvidenceLedgerCompiler.CLAIM_LEDGER_VERSION);
            metadata.merge("answerEvidenceBoundClaimCount", binding.boundClaimCount(),
                (left, right) -> ((Number) left).intValue() + ((Number) right).intValue());
            metadata.put("answerEvidenceBindingPhase", firstNonBlank(phase, "unknown"));
        }
        return binding.answer();
    }

    void recordPosture(Map<String, Object> metadata, boolean citedObservation,
                       List<Map<String, Object>> toolEvidence, List<InteractionToolTrace> traces) {
        if (metadata == null) return;
        List<String> evidence = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        if (toolEvidence != null && toolEvidence.stream().anyMatch(item ->
            Boolean.TRUE.equals(item.get("success")) && nonBlank(item.get("evidenceType")))) {
            evidence.add("SUCCESSFUL_TOOL_EVIDENCE");
        }
        if (citedObservation) evidence.add("CITED_OBSERVATION");
        boolean failedTool = toolEvidence != null && toolEvidence.stream()
            .anyMatch(item -> !Boolean.TRUE.equals(item.get("success")));
        boolean failedTrace = traces != null && traces.stream()
            .anyMatch(trace -> trace != null && !trace.isSuccess());
        if (failedTool || failedTrace) limitations.add("TOOL_EXECUTION_FAILURE");
        if (Boolean.TRUE.equals(metadata.get("fatalExecutionBlocked"))
            || Boolean.TRUE.equals(metadata.get("mandatoryWorkflowBlocked"))) {
            limitations.add("RUNTIME_EXECUTION_BLOCKED");
        }
        String status = !evidence.isEmpty()
            ? (limitations.isEmpty() ? "GROUNDED" : "PARTIAL")
            : (limitations.isEmpty() ? "INSUFFICIENT" : "BLOCKED");
        metadata.put("answerEvidenceAuditVersion", AUDIT_CONTRACT);
        metadata.put("answerEvidenceStatus", status);
        metadata.put("answerEvidenceSignals", List.copyOf(evidence));
        metadata.put("answerEvidenceLimitations", List.copyOf(limitations));
        metadata.put("answerEvidenceUserVisible", false);
    }

    void attachEnvelope(Map<String, Object> metadata, String query) {
        if (metadata == null) return;
        boolean requested = userFacingPolicy.shouldExposeEvidenceReferences(query, metadata);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("schemaVersion", AUDIT_ENVELOPE);
        audit.put("visibility", requested ? "USER_REQUESTED" : "BACKEND_AUDIT_ONLY");
        audit.put("status", firstNonBlank(text(metadata.get("answerEvidenceStatus")), "INSUFFICIENT"));
        audit.put("signals", objects(metadata.get("answerEvidenceSignals")));
        audit.put("limitations", objects(metadata.get("answerEvidenceLimitations")));
        audit.put("selectedEvidenceRefs", objects(firstPresent(
            metadata.get("usefulEvidenceRefs"), metadata.get("evidenceForcedCitations"))));
        audit.put("rejectedEvidenceRefs", objects(metadata.get("rejectedEvidenceRefs")));
        audit.put("availableCitations", objects(metadata.get("availableEvidenceCitations")));
        audit.put("claimCoverage", firstPresent(metadata.get("claimCoverage"), 0.0));
        audit.put("claimCoverageStatus", firstNonBlank(
            text(metadata.get("claimCoverageStatus")), "NOT_APPLICABLE"));
        audit.put("claimLedger", map(metadata.get("claimLedger")));
        audit.put("evidenceManifest", map(metadata.get("evidenceManifest")));
        audit.put("toolEvidence", objects(metadata.get("toolResultEvidence")));
        audit.put("presentationPolicy", Map.of(
            "default", "METADATA_ONLY", "userVisible", requested, "explicitRequestRequired", true));
        metadata.put("answerEvidenceAudit", Map.copyOf(audit));
        metadata.put("answerEvidenceAuditSchemaVersion", AUDIT_ENVELOPE);
        metadata.put("answerEvidenceUserVisible", requested);
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> { if (item != null) result.add(String.valueOf(item)); });
        }
        return result;
    }

    private List<Object> objects(Object value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) iterable.forEach(result::add);
        return List.copyOf(result);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private boolean nonBlank(Object value) { return value != null && !String.valueOf(value).isBlank(); }
    private Object firstPresent(Object first, Object second) { return first == null ? second : first; }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
