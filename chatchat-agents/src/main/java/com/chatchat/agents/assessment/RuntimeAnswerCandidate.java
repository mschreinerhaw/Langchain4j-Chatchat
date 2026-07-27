package com.chatchat.agents.assessment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user-facing business result with an explicit lifecycle independent of plan
 * parsing and tool execution.
 */
public record RuntimeAnswerCandidate(
    String contractVersion,
    String content,
    String type,
    String source,
    Status status,
    Map<String, Object> validation
) {
    public static final String CONTRACT_VERSION = "runtime_answer_candidate_v1";

    public RuntimeAnswerCandidate {
        contractVersion = CONTRACT_VERSION;
        content = content == null ? "" : content;
        type = type == null || type.isBlank() ? "answer" : type;
        source = source == null || source.isBlank() ? "planner" : source;
        status = status == null ? Status.GENERATED : status;
        validation = validation == null ? Map.of() : Map.copyOf(validation);
    }

    public RuntimeAnswerCandidate transition(Status next, Map<String, Object> details) {
        Map<String, Object> values = new LinkedHashMap<>(validation);
        if (details != null) {
            values.putAll(details);
        }
        return new RuntimeAnswerCandidate(
            CONTRACT_VERSION, content, type, source, next, values);
    }

    public enum Status {
        GENERATED,
        VALIDATED,
        SELECTED
    }
}
