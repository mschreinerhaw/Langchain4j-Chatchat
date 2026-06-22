package com.chatchat.agents.evidence;

import java.util.List;

public record SqlValidationResult(
    List<SqlState> states,
    boolean parsed,
    boolean schemaValidated,
    boolean semanticValidated,
    boolean executionVerified,
    boolean trusted,
    double validationScore,
    List<String> issues
) {

    public SqlValidationResult {
        states = states == null ? List.of() : List.copyOf(states);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
