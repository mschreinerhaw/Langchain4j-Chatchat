package com.chatchat.knowledgebase.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvidenceDecisionEngine {

    private final EvidenceDecisionPolicy defaultPolicy;

    public EvidenceDecisionEngine() {
        this(EvidenceDecisionPolicy.defaults());
    }

    public EvidenceDecisionEngine(EvidenceDecisionPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy == null ? EvidenceDecisionPolicy.defaults() : defaultPolicy;
    }

    public EvidenceDecisionResult decide(DocumentSearchResult result, EvidenceReasoningResult reasoning) {
        EvidenceDecisionPolicy policy = defaultPolicy;
        List<EvidenceDecisionTraceStep> traceSteps = new ArrayList<>();
        if (result == null) {
            String reason = "No document_search result available";
            traceSteps.add(traceStep(
                10,
                "null_result_refuse",
                EvidenceDecisionAction.REFUSE,
                true,
                reason,
                Map.of("resultPresent", false)
            ));
            return decision(EvidenceDecisionAction.REFUSE, 0.0D, List.of(reason), policy, "null_result_refuse", traceSteps);
        }
        EvidenceReasoningResult safeReasoning = reasoning == null ? result.reasoning() : reasoning;
        if (safeReasoning == null) {
            safeReasoning = EvidenceReasoningResult.empty(result.query());
        }
        List<String> reasons = new ArrayList<>();
        int evidenceNodes = evidenceNodeCount(safeReasoning);
        boolean hasEvidence = result.results() != null && !result.results().isEmpty();
        boolean hasDocumentHits = result.documents() != null && !result.documents().isEmpty();
        boolean hasConflict = safeReasoning.conflictDetected();
        boolean hasAgrade = hasGrade(result.results(), DocumentEvidenceGrade.A);
        boolean hasMissingInfo = safeReasoning.missingInfo() != null && !safeReasoning.missingInfo().isEmpty();
        double confidence = safeReasoning.confidence();

        if (record(traceSteps, 20, "title_only_requires_expansion", EvidenceDecisionAction.EXPAND,
            !hasEvidence && hasDocumentHits && policy.expandOnTitleOnly(),
            "Title or metadata hit requires document expansion before answering",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("Title or metadata hit requires document expansion before answering");
            return decision(EvidenceDecisionAction.EXPAND, confidence, reasons, policy, "title_only_requires_expansion", traceSteps);
        }
        if (record(traceSteps, 30, "no_evidence_refuse", EvidenceDecisionAction.REFUSE,
            !hasEvidence,
            "No evidence body is available",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("No evidence body is available");
            return decision(EvidenceDecisionAction.REFUSE, confidence, reasons, policy, "no_evidence_refuse", traceSteps);
        }
        if (record(traceSteps, 40, "conflict_requires_review", EvidenceDecisionAction.REVIEW_REQUIRED,
            policy.reviewOnConflict() && hasConflict,
            "Evidence conflict detected",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("Evidence conflict detected");
            return decision(EvidenceDecisionAction.REVIEW_REQUIRED, confidence, reasons, policy, "conflict_requires_review", traceSteps);
        }
        if (record(traceSteps, 50, "insufficient_evidence_nodes_expand", EvidenceDecisionAction.EXPAND,
            evidenceNodes < policy.minAnswerEvidenceNodes(),
            "Evidence node count below minimum",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("Evidence node count below minimum");
            return decision(EvidenceDecisionAction.EXPAND, confidence, reasons, policy, "insufficient_evidence_nodes_expand", traceSteps);
        }
        if (record(traceSteps, 60, "missing_a_grade_evidence", EvidenceDecisionAction.EXPAND,
            policy.requireAgradeEvidence() && !hasAgrade && confidence < policy.minPartialConfidence(),
            "No A-grade evidence available and confidence is below partial threshold",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("No A-grade evidence available");
            return decision(EvidenceDecisionAction.EXPAND, confidence, reasons, policy, "missing_a_grade_evidence", traceSteps);
        }
        if (record(traceSteps, 70, "missing_a_grade_clarify", EvidenceDecisionAction.CLARIFY,
            policy.requireAgradeEvidence() && !hasAgrade,
            "No A-grade evidence available",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("No A-grade evidence available");
            return decision(EvidenceDecisionAction.CLARIFY, confidence, reasons, policy, "missing_a_grade_clarify", traceSteps);
        }
        if (record(traceSteps, 80, "missing_info_clarify", EvidenceDecisionAction.CLARIFY,
            policy.clarifyOnMissingInfo() && hasMissingInfo,
            "Reasoning reports missing information",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.addAll(safeReasoning.missingInfo());
            return decision(EvidenceDecisionAction.CLARIFY, confidence, reasons, policy, "missing_info_clarify", traceSteps);
        }
        if (record(traceSteps, 90, "low_confidence_expand", EvidenceDecisionAction.EXPAND,
            confidence < policy.minPartialConfidence(),
            "Reasoning confidence below partial-answer threshold",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("Reasoning confidence below partial-answer threshold");
            return decision(EvidenceDecisionAction.EXPAND, confidence, reasons, policy, "low_confidence_expand", traceSteps);
        }
        if (record(traceSteps, 100, "partial_confidence_clarify", EvidenceDecisionAction.CLARIFY,
            confidence < policy.minAnswerConfidence(),
            "Reasoning confidence supports only partial answer",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade))) {
            reasons.add("Reasoning confidence supports only partial answer");
            return decision(EvidenceDecisionAction.CLARIFY, confidence, reasons, policy, "partial_confidence_clarify", traceSteps);
        }
        record(traceSteps, 110, "answer_policy_satisfied", EvidenceDecisionAction.ANSWER,
            true,
            "Evidence satisfies answer policy",
            facts(result, safeReasoning, evidenceNodes, hasEvidence, hasDocumentHits, hasAgrade));
        reasons.add("Evidence satisfies answer policy");
        return decision(EvidenceDecisionAction.ANSWER, confidence, reasons, policy, "answer_policy_satisfied", traceSteps);
    }

    private EvidenceDecisionResult decision(EvidenceDecisionAction action,
                                            double confidence,
                                            List<String> reasons,
                                            EvidenceDecisionPolicy policy,
                                            String selectedRuleId,
                                            List<EvidenceDecisionTraceStep> traceSteps) {
        return new EvidenceDecisionResult(
            EvidenceDecisionResult.CONTRACT_VERSION,
            action,
            action == EvidenceDecisionAction.ANSWER || action == EvidenceDecisionAction.CLARIFY,
            action == EvidenceDecisionAction.EXPAND,
            action == EvidenceDecisionAction.CLARIFY,
            action == EvidenceDecisionAction.REVIEW_REQUIRED,
            confidence,
            reasons,
            policy,
            new EvidenceDecisionTrace(
                EvidenceDecisionTrace.CONTRACT_VERSION,
                "default_evidence_decision_policy",
                selectedRuleId,
                traceSteps,
                true
            )
        );
    }

    private boolean record(List<EvidenceDecisionTraceStep> steps,
                           int priority,
                           String ruleId,
                           EvidenceDecisionAction candidateAction,
                           boolean matched,
                           String reason,
                           Map<String, Object> facts) {
        steps.add(traceStep(priority, ruleId, candidateAction, matched, reason, facts));
        return matched;
    }

    private EvidenceDecisionTraceStep traceStep(int priority,
                                                String ruleId,
                                                EvidenceDecisionAction candidateAction,
                                                boolean matched,
                                                String reason,
                                                Map<String, Object> facts) {
        return new EvidenceDecisionTraceStep(priority, ruleId, candidateAction, matched, reason, facts);
    }

    private Map<String, Object> facts(DocumentSearchResult result,
                                      EvidenceReasoningResult reasoning,
                                      int evidenceNodes,
                                      boolean hasEvidence,
                                      boolean hasDocumentHits,
                                      boolean hasAgrade) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("matchType", result.matchType() == null ? null : result.matchType().name());
        values.put("hasEvidence", hasEvidence);
        values.put("hasDocumentHits", hasDocumentHits);
        values.put("evidenceNodes", evidenceNodes);
        values.put("hasAgradeEvidence", hasAgrade);
        values.put("confidence", reasoning == null ? 0.0D : reasoning.confidence());
        values.put("conflictDetected", reasoning != null && reasoning.conflictDetected());
        values.put("missingInfoCount", reasoning == null || reasoning.missingInfo() == null ? 0 : reasoning.missingInfo().size());
        return values;
    }

    private int evidenceNodeCount(EvidenceReasoningResult reasoning) {
        if (reasoning == null || reasoning.graph() == null || reasoning.graph().nodes() == null) {
            return 0;
        }
        return (int) reasoning.graph().nodes().stream()
            .filter(node -> node != null && node.type() != EvidenceNodeType.BACKGROUND)
            .count();
    }

    private boolean hasGrade(List<DocumentEvidenceChunk> chunks, DocumentEvidenceGrade grade) {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        return chunks.stream().anyMatch(chunk -> grade(chunk.score()) == grade);
    }

    private DocumentEvidenceGrade grade(Double score) {
        double value = score == null ? 0.0D : score;
        if (value >= 80.0D) {
            return DocumentEvidenceGrade.A;
        }
        if (value >= 50.0D) {
            return DocumentEvidenceGrade.B;
        }
        return DocumentEvidenceGrade.C;
    }
}
