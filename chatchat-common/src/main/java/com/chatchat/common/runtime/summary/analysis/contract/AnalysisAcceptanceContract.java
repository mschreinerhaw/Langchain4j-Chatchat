package com.chatchat.common.runtime.summary.analysis.contract;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

/** Bounded claim-level publication policy; not a score for an entire report. */
public record AnalysisAcceptanceContract(int maxRepairRounds, int maxClaimsPerRound,
                                         long maxRepairDurationMs) {
    public AnalysisAcceptanceContract {
        if (maxRepairRounds < 0 || maxRepairRounds > 1 || maxClaimsPerRound < 0 || maxRepairDurationMs < 0)
            throw new IllegalArgumentException("Invalid bounded repair policy");
    }
    public static AnalysisAcceptanceContract standard() {
        return new AnalysisAcceptanceContract(1, 10, 15000);
    }
    public enum Status { VALID, NEEDS_RECALCULATION, OVER_GENERALIZED, INSUFFICIENT_EVIDENCE,
        CONFLICTING, LIMITED, UNRESOLVED, REJECTED }
    public enum RepairAction { RETAIN, NARROW_SCOPE, REMOVE_CLAIM }
    public record QuestionCoverageRule(String basis, String missingQuestionDisposition) {}
    public record EvidenceIntegrityRule(String requiredLineage, String unknownReferenceDisposition) {}
    public record NumericConsistencyRule(String authority, String unavailableDerivationDisposition) {}
    public record ScopeConsistencyRule(List<String> dimensions, String expansionDisposition) {}
    public record ClaimStrengthRule(String ceiling, String unsupportedInterpretationDisposition) {}
    public record CrossReportConsistencyRule(String identity, String conflictDisposition) {}
    public record RepairPolicy(int maxRounds, int maxClaimsPerRound, long maxDurationMs,
                               int additionalModelCalls, List<RepairAction> executableActions) {}

    public QuestionCoverageRule questionCoverage() {
        return new QuestionCoverageRule("CURRENT_QUESTION_AND_RETURNED_EVIDENCE", "PUBLISH_AS_UNRESOLVED");
    }
    public EvidenceIntegrityRule evidenceIntegrity() {
        return new EvidenceIntegrityRule("ADMITTED_CLAIM_AND_SOURCE_REFERENCES", "ISOLATE_AFFECTED_CLAIM");
    }
    public NumericConsistencyRule numericConsistency() {
        return new NumericConsistencyRule("RUNTIME_CALCULATION_WITH_LINEAGE", "DO_NOT_ASSERT_RECALCULATED");
    }
    public ScopeConsistencyRule scopeConsistency() {
        return new ScopeConsistencyRule(List.of("TIME", "POPULATION", "SAMPLE", "FILTER", "UNIT"), "NARROW_OR_UNRESOLVED");
    }
    public ClaimStrengthRule claimStrength() {
        return new ClaimStrengthRule("EVIDENCE_CONFIDENCE_AND_DECLARED_MEANING", "NARROW_OR_UNRESOLVED");
    }
    public CrossReportConsistencyRule reportConsistency() {
        return new CrossReportConsistencyRule("SHARED_EVIDENCE_REFERENCES", "REPAIR_LOCAL_CLAIM_ONLY");
    }
    public RepairPolicy repairPolicy() {
        return new RepairPolicy(maxRepairRounds, maxClaimsPerRound, maxRepairDurationMs, 0,
            List.of(RepairAction.RETAIN, RepairAction.NARROW_SCOPE, RepairAction.REMOVE_CLAIM));
    }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("schemaVersion", "analysis_acceptance.v2", "unit", "CLAIM",
            "delivery", "VALID_LIMITED_AND_UNRESOLVED",
            "repair", Map.of("maxRounds", maxRepairRounds, "maxClaimsPerRound", maxClaimsPerRound,
                "maxDurationMs", maxRepairDurationMs, "additionalModelCalls", 0),
            "numericRule", "Number provenance is not formula verification; do not claim recalculation without executable derivation.",
            "patchRule", "Retain valid blocks; narrow only to verified evidence; unresolved claims do not block other findings."));
        result.put("questionCoverage", Map.of("basis", questionCoverage().basis(),
            "missingQuestionDisposition", questionCoverage().missingQuestionDisposition()));
        result.put("evidenceIntegrity", Map.of("requiredLineage", evidenceIntegrity().requiredLineage(),
            "unknownReferenceDisposition", evidenceIntegrity().unknownReferenceDisposition()));
        result.put("numericConsistency", Map.of("authority", numericConsistency().authority(),
            "unavailableDerivationDisposition", numericConsistency().unavailableDerivationDisposition()));
        result.put("scopeConsistency", Map.of("dimensions", scopeConsistency().dimensions(),
            "expansionDisposition", scopeConsistency().expansionDisposition()));
        result.put("claimStrength", Map.of("ceiling", claimStrength().ceiling(),
            "unsupportedInterpretationDisposition", claimStrength().unsupportedInterpretationDisposition()));
        result.put("reportConsistency", Map.of("identity", reportConsistency().identity(),
            "conflictDisposition", reportConsistency().conflictDisposition()));
        result.put("repairPolicy", Map.of("maxRounds", maxRepairRounds, "maxClaimsPerRound", maxClaimsPerRound,
            "maxDurationMs", maxRepairDurationMs, "additionalModelCalls", 0,
            "executableActions", repairPolicy().executableActions().stream().map(Enum::name).toList()));
        result.put("semanticReviewPolicy", Map.of("maxCalls", 1, "maxDurationMs", 10000,
            "maxInputBytes", 48000, "failureDisposition", "NARROW_TO_EVIDENCE_OR_UNRESOLVED",
            "proposalAuthority", "NOT_VERIFIED_FACTS"));
        return Map.copyOf(result);
    }
}
