package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.List;

public class SqlValidationEngine {

    public SqlValidationResult validate(SqlEvidence sql) {
        if (sql == null || sql.normalizedSql() == null || sql.normalizedSql().isBlank()) {
            return new SqlValidationResult(List.of(), false, false, false, false, false, 0.0, List.of("SQL is empty"));
        }
        List<SqlState> states = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        states.add(SqlState.EXTRACTED);

        boolean parsed = sql.sqlType() != SqlType.UNKNOWN;
        if (parsed) {
            states.add(SqlState.PARSED);
        } else {
            issues.add("SQL type is unknown");
        }

        boolean schemaValidated = parsed && !sql.tableNames().isEmpty();
        if (schemaValidated) {
            states.add(SqlState.VALIDATED_SCHEMA);
        } else {
            issues.add("No table lineage detected");
        }

        boolean semanticValidated = schemaValidated && semanticColumnsResolvable(sql);
        if (semanticValidated) {
            states.add(SqlState.VALIDATED_SEMANTIC);
        } else if (schemaValidated) {
            issues.add("Column mapping is incomplete");
        }

        boolean executionVerified = semanticValidated && dryRunSafe(sql);
        if (executionVerified) {
            states.add(SqlState.EXECUTION_VERIFIED);
        } else if (semanticValidated) {
            issues.add("SQL dry-run simulation failed");
        }

        boolean trusted = executionVerified;
        if (trusted) {
            states.add(SqlState.TRUSTED);
        }
        double score = validationScore(states);
        return new SqlValidationResult(states, parsed, schemaValidated, semanticValidated, executionVerified, trusted, score, issues);
    }

    private boolean semanticColumnsResolvable(SqlEvidence sql) {
        if (sql.sqlType() == SqlType.DDL || sql.sqlType() == SqlType.DELETE || sql.sqlType() == SqlType.UPDATE) {
            return true;
        }
        return !sql.columns().isEmpty();
    }

    private boolean dryRunSafe(SqlEvidence sql) {
        String normalized = sql.normalizedSql().toLowerCase();
        return !normalized.contains(";;")
            && normalized.chars().filter(ch -> ch == '(').count() == normalized.chars().filter(ch -> ch == ')').count();
    }

    private double validationScore(List<SqlState> states) {
        if (states == null || states.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        if (states.contains(SqlState.EXTRACTED)) {
            score += 0.12;
        }
        if (states.contains(SqlState.PARSED)) {
            score += 0.2;
        }
        if (states.contains(SqlState.VALIDATED_SCHEMA)) {
            score += 0.2;
        }
        if (states.contains(SqlState.VALIDATED_SEMANTIC)) {
            score += 0.18;
        }
        if (states.contains(SqlState.EXECUTION_VERIFIED)) {
            score += 0.2;
        }
        if (states.contains(SqlState.TRUSTED)) {
            score += 0.1;
        }
        return Math.round(Math.min(1.0, score) * 1000.0) / 1000.0;
    }
}
