package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Checks the assembled report against shared evidence, before either renderer runs. */
final class ReportConsistencyGate {
    record Evidence(String confidence, List<String> caveats) {}
    record Statement(String section, String text, String confidence, List<String> basisIds) {}
    record Violation(String code, int statementIndex, List<String> basisIds) {}

    List<Violation> validate(List<Statement> statements, Map<String, Evidence> evidence) {
        List<Violation> violations = new ArrayList<>();
        var boundary = new ReportClaimBoundaryPolicy();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            int requested = confidenceRank(statement.confidence());
            for (String id : statement.basisIds()) {
                Evidence source = evidence.get(id);
                if (source == null) {
                    violations.add(new Violation("UNKNOWN_BASIS", i, List.of(id)));
                } else if (requested > 0 && (confidenceRank(source.confidence()) == 0
                    || requested > confidenceRank(source.confidence()))) {
                    violations.add(new Violation("CONFIDENCE_EXCEEDS_EVIDENCE", i, List.of(id)));
                }
                if (source != null && source.caveats().stream().anyMatch(caveat ->
                    boundary.contradicts(statement.text(), caveat))) {
                    violations.add(new Violation("CONTRADICTS_EVIDENCE_CAVEAT", i, List.of(id)));
                }
            }
            // A limitation on the same evidence applies across chapters, including the summary.
            for (Statement limitation : statements) {
                if (!"LIMITATION".equals(limitation.section()) || limitation == statement
                    || Collections.disjoint(statement.basisIds(), limitation.basisIds())) continue;
                if (boundary.contradicts(statement.text(), limitation.text())) {
                    violations.add(new Violation("CONTRADICTS_SHARED_EVIDENCE_LIMITATION", i, statement.basisIds()));
                }
            }
        }
        return List.copyOf(violations);
    }

    private int confidenceRank(String value) {
        if (value == null) return 0;
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "HIGH", "高" -> 3;
            case "MEDIUM", "中" -> 2;
            case "LOW", "低" -> 1;
            default -> 0;
        };
    }
}
