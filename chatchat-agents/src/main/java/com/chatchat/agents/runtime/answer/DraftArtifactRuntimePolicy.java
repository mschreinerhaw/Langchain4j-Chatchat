package com.chatchat.agents.runtime.answer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces a planner-issued structured artifact contract. Runtime deliberately
 * does not infer artifact intent from user or answer text.
 */
public final class DraftArtifactRuntimePolicy {

    public static final String CONTRACT_VERSION = "draft_artifact_runtime_v2";
    public static final String CONTRACT_METADATA_KEY = "artifactContract";
    public static final String DRAFT_MODE = "DRAFT";
    public static final String NOT_EXECUTED = "NOT_EXECUTED";

    public Result enforce(String answer, Map<String, Object> metadata) {
        Map<String, Object> contract = contract(metadata);
        if (contract.isEmpty() || !DRAFT_MODE.equals(text(first(contract,
            "deliveryMode", "delivery_mode")))) {
            return new Result(answer == null ? "" : answer, false, null);
        }

        String artifactType = required(contract, "artifactType", "artifact_type");
        String executionStatus = required(contract, "executionStatus", "execution_status");
        String disclosure = required(contract, "disclosure");
        if (!NOT_EXECUTED.equals(executionStatus)) {
            throw new IllegalStateException(
                "Draft artifact contract must declare executionStatus=" + NOT_EXECUTED);
        }

        if (metadata != null) {
            metadata.put("draftArtifactContractVersion", CONTRACT_VERSION);
            metadata.put("artifactType", artifactType);
            metadata.put("artifactExecutionStatus", executionStatus);
            copy(contract, metadata, "authorizationStatus", "artifactAuthorizationStatus",
                "authorization_status");
            copy(contract, metadata, "humanReviewRequired", "artifactHumanReviewRequired",
                "human_review_required");
            copy(contract, metadata, "assumptions", "artifactAssumptions");
        }

        String safeAnswer = answer == null ? "" : answer.trim();
        boolean alreadyApplied = safeAnswer.startsWith(disclosure);
        if (metadata != null) {
            metadata.put("draftArtifactDisclosureApplied", !alreadyApplied);
        }
        return new Result(
            alreadyApplied ? safeAnswer : disclosure + "\n\n" + safeAnswer,
            true,
            artifactType
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contract(Map<String, Object> metadata) {
        if (metadata == null) return Map.of();
        Object value = metadata.get(CONTRACT_METADATA_KEY);
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target,
                      String sourceKey, String targetKey, String... aliases) {
        Object value = first(source, concat(sourceKey, aliases));
        if (value != null) target.put(targetKey, value);
    }

    private String[] concat(String first, String[] remaining) {
        String[] values = new String[remaining.length + 1];
        values[0] = first;
        System.arraycopy(remaining, 0, values, 1, remaining.length);
        return values;
    }

    private String required(Map<String, Object> values, String... keys) {
        String value = text(first(values, keys));
        if (value == null) {
            throw new IllegalStateException("Draft artifact contract is missing " + List.of(keys));
        }
        return value;
    }

    private Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) return values.get(key);
        }
        return null;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
            ? null : String.valueOf(value).trim();
    }

    public record Result(String answer, boolean draftArtifact, String artifactType) {
    }
}
