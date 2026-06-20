package com.chatchat.agents.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AnswerAssemblyPolicy(
    String contractVersion,
    AnswerAssemblyMode mode,
    boolean partialAnswerAllowed,
    String citationPlacement,
    String conflictHandling,
    String minEvidenceGrade,
    int minCitations,
    List<String> requiredSections,
    List<String> missingInfo
) {
    public static final String CONTRACT_VERSION = "answer_assembly_policy_v1";

    public AnswerAssemblyPolicy {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        mode = mode == null ? AnswerAssemblyMode.REVIEW_REQUIRED : mode;
        citationPlacement = citationPlacement == null || citationPlacement.isBlank()
            ? "place citation immediately after each evidence-backed claim"
            : citationPlacement;
        conflictHandling = conflictHandling == null || conflictHandling.isBlank()
            ? "explain conflicts and avoid choosing a winner without stronger evidence"
            : conflictHandling;
        minEvidenceGrade = minEvidenceGrade == null || minEvidenceGrade.isBlank() ? "A" : minEvidenceGrade;
        minCitations = Math.max(0, minCitations);
        requiredSections = requiredSections == null ? List.of() : List.copyOf(requiredSections);
        missingInfo = missingInfo == null ? List.of() : List.copyOf(missingInfo);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contractVersion", contractVersion);
        values.put("mode", mode.name());
        values.put("partialAnswerAllowed", partialAnswerAllowed);
        values.put("citationPlacement", citationPlacement);
        values.put("conflictHandling", conflictHandling);
        values.put("minEvidenceGrade", minEvidenceGrade);
        values.put("minCitations", minCitations);
        values.put("requiredSections", requiredSections);
        values.put("missingInfo", missingInfo);
        return values;
    }
}
