package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.evidence.normalization.EvidenceChunk;

import com.chatchat.agents.protocol.AnswerContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic pre-generation gate based on evidence protocol signals, never business vocabulary.
 */
public class EvidenceSufficiencyGate {

    static final String VERSION = "evidence_sufficiency_gate_v1";
    static final String SUFFICIENT = "SUFFICIENT";
    public static final String PARTIAL = "PARTIAL";
    public static final String INSUFFICIENT = "INSUFFICIENT";
    static final String NOT_REQUIRED = "NOT_REQUIRED";

    public Decision evaluate(AnswerContract contract, List<String> observations) {
        List<String> safe = observations == null ? List.of() : observations;
        int usable = 0;
        int failed = 0;
        for (String observation : safe) {
            String value = observation == null ? "" : observation.toLowerCase(Locale.ROOT);
            if (isFailed(value)) failed++;
            if (isUsableEvidence(value) && !isFailed(value)) usable++;
        }
        boolean required = contract != null
            && AnswerContract.EVIDENCE_REQUIRED.equals(contract.evidencePolicy());
        boolean evidencePathActive = required || usable > 0 || failed > 0;
        String status;
        if (!evidencePathActive) status = NOT_REQUIRED;
        else if (usable == 0) status = INSUFFICIENT;
        else if (failed > 0) status = PARTIAL;
        else status = SUFFICIENT;

        List<String> reasons = new ArrayList<>();
        if (required) reasons.add("answer_contract_requires_evidence");
        if (usable == 0 && evidencePathActive) reasons.add("no_usable_evidence_observation");
        if (failed > 0) reasons.add("one_or_more_evidence_sources_failed");
        if (SUFFICIENT.equals(status)) reasons.add("usable_evidence_available");
        return new Decision(VERSION, status, usable, failed,
            INSUFFICIENT.equals(status) || PARTIAL.equals(status),
            !INSUFFICIENT.equals(status), List.copyOf(reasons));
    }

    private boolean isUsableEvidence(String value) {
        return value.contains("evidence_v")
            || value.contains("evidencechunk")
            || value.contains("document_evidence")
            || value.contains("doc://")
            || value.contains("web://")
            || value.contains("evidencerole")
            || value.contains("\"success\":true")
            || value.contains("status=success");
    }

    private boolean isFailed(String value) {
        return value.contains("\"success\":false")
            || value.contains("status=failed")
            || value.contains("evidence is unavailable")
            || value.contains("tool observation reports failure");
    }

    public record Decision(String contractVersion,
                    String status,
                    int usableEvidenceCount,
                    int failedEvidenceCount,
                    boolean retrieveMoreRecommended,
                    boolean strongClaimsAllowed,
                    List<String> reasons) {

        public Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("contractVersion", contractVersion);
            values.put("status", status);
            values.put("usableEvidenceCount", usableEvidenceCount);
            values.put("failedEvidenceCount", failedEvidenceCount);
            values.put("retrieveMoreRecommended", retrieveMoreRecommended);
            values.put("strongClaimsAllowed", strongClaimsAllowed);
            values.put("reasons", reasons);
            return Map.copyOf(values);
        }

        public String promptText() {
            return "status=" + status + ", usableEvidenceCount=" + usableEvidenceCount
                + ", failedEvidenceCount=" + failedEvidenceCount
                + ", strongClaimsAllowed=" + strongClaimsAllowed
                + ", reasons=" + reasons;
        }
    }
}
