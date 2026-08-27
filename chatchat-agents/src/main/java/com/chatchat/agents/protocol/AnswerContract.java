package com.chatchat.agents.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business-neutral acceptance contract for one user-facing answer.
 */
public record AnswerContract(
    String contractVersion,
    String goal,
    List<String> deliverables,
    String outputFormat,
    String language,
    String evidencePolicy,
    List<String> constraints
) {

    public static final String VERSION = "answer_contract_v1";
    public static final String EVIDENCE_REQUIRED = "REQUIRED";
    public static final String EVIDENCE_OPTIONAL = "OPTIONAL";
    public static final String EVIDENCE_NOT_REQUIRED = "NOT_REQUIRED";

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("contractVersion", contractVersion);
        values.put("goal", goal);
        values.put("deliverables", deliverables);
        values.put("outputFormat", outputFormat);
        values.put("language", language);
        values.put("evidencePolicy", evidencePolicy);
        values.put("constraints", constraints);
        return Map.copyOf(values);
    }

    public String promptText() {
        return "contractVersion=" + contractVersion
            + "\ngoal=" + goal
            + "\ndeliverables=" + deliverables
            + "\noutputFormat=" + outputFormat
            + "\nlanguage=" + language
            + "\nevidencePolicy=" + evidencePolicy
            + "\nconstraints=" + constraints;
    }
}
