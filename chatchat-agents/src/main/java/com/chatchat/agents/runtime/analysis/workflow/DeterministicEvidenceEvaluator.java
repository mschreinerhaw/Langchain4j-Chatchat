package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.VerificationResult;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.recovery.EvidenceEvaluation;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGapReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Hard structural rules run before any model-provided semantic sufficiency score. */
final class DeterministicEvidenceEvaluator {

    EvidenceEvaluation evaluate(AnalysisContext context,
                                EvidenceBundle bundle,
                                VerificationResult verification,
                                Map<String, Object> outcomeMetadata) {
        EvidenceBundle safeBundle = bundle == null ? EvidenceBundle.empty("no evidence") : bundle;
        Map<String, Object> bundleMetadata = safeBundle.metadata();
        Map<String, Object> executionMetadata = outcomeMetadata == null ? Map.of() : outcomeMetadata;
        List<AnalysisEvidence> evidence = safeBundle.evidence();
        List<DocumentAnalysisEvidence> documents = evidence.stream()
            .filter(DocumentAnalysisEvidence.class::isInstance)
            .map(DocumentAnalysisEvidence.class::cast)
            .toList();
        List<EvidenceGap> gaps = new ArrayList<>();
        boolean sequenceSensitive = booleanValue(context.attributes(), "sequenceSensitive")
            || sequenceSensitive(context.query());
        double requiredCoverage = number(context.attributes().get("requiredEvidenceCoverage"), 0.8D);
        double coverage = number(first(bundleMetadata.get("evidenceCoverage"),
            first(executionMetadata.get("evidenceCoverage"), executionMetadata.get("semanticCoverage"))), evidence.isEmpty() ? 0D
                : verification != null && verification.accepted() ? 1D : 0.5D);
        boolean semanticInsufficient = explicitlyFalse(bundleMetadata, "semanticSufficient", executionMetadata);
        DocumentAnalysisEvidence anchor = documents.isEmpty() ? null : documents.get(0);

        if (evidence.isEmpty()) {
            gaps.add(gap(EvidenceGapReason.RETRIEVAL_EMPTY, anchor, coverage, requiredCoverage,
                sequenceSensitive, false, "No verified evidence was retrieved"));
        } else {
            boolean recoveryComplete = booleanValue(bundleMetadata, "recoveryComplete");
            boolean truncated = !recoveryComplete && (booleanValue(context.attributes(), "truncated")
                || booleanValue(bundleMetadata, "truncated")
                || booleanValue(bundleMetadata, "sourceTruncated")
                || booleanValue(executionMetadata, "truncated")
                || evidence.stream().anyMatch(item -> booleanValue(item.attributes(), "truncated")));
            if (truncated) {
                gaps.add(gap(EvidenceGapReason.SOURCE_TRUNCATED, anchor, coverage, requiredCoverage,
                    sequenceSensitive, true, "Source context was truncated"));
            }
            boolean sectionIncomplete = !recoveryComplete
                && explicitlyFalse(bundleMetadata, "sectionComplete", executionMetadata);
            if (sectionIncomplete) {
                gaps.add(gap(EvidenceGapReason.SECTION_INCOMPLETE, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Retrieved section is incomplete"));
            }
            boolean sequenceComplete = recoveryComplete
                || booleanValue(bundleMetadata, "sequenceComplete")
                || booleanValue(executionMetadata, "sequenceComplete");
            if (sequenceSensitive && !sequenceComplete && (documents.size() < 2
                || explicitlyFalse(bundleMetadata, "sequenceComplete", executionMetadata))) {
                gaps.add(gap(EvidenceGapReason.SEQUENCE_INCOMPLETE, anchor, coverage, requiredCoverage,
                    true, truncated, "Procedure or operation sequence is incomplete"));
            }
            int requiredClaims = integer(first(context.attributes().get("requiredClaimCount"),
                bundleMetadata.get("requiredClaimCount")), 0);
            int admittedClaims = integer(first(bundleMetadata.get("admittedClaimCount"),
                executionMetadata.get("admittedClaimCount")), requiredClaims);
            if (requiredClaims > admittedClaims) {
                gaps.add(gap(EvidenceGapReason.CLAIM_UNSUPPORTED, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, (requiredClaims - admittedClaims) + " required claims lack evidence"));
            }
            if (booleanValue(bundleMetadata, "conflictingEvidence")
                || booleanValue(executionMetadata, "conflictingEvidence")) {
                gaps.add(gap(EvidenceGapReason.CONFLICTING_EVIDENCE, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Evidence sources conflict"));
            }
            if (explicitlyFalse(bundleMetadata, "sourceAuthoritative", executionMetadata)) {
                gaps.add(gap(EvidenceGapReason.SOURCE_NOT_AUTHORITATIVE, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Authoritative source is required"));
            }
            if (booleanValue(bundleMetadata, "missingPrimarySource")
                || booleanValue(executionMetadata, "missingPrimarySource")) {
                gaps.add(gap(EvidenceGapReason.MISSING_PRIMARY_SOURCE, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Primary source document is missing"));
            }
            if (booleanValue(bundleMetadata, "missingContext")
                || booleanValue(executionMetadata, "missingContext")) {
                gaps.add(gap(EvidenceGapReason.MISSING_CONTEXT, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Supporting context is missing"));
            }
            if (booleanValue(bundleMetadata, "dataIncomplete")
                || booleanValue(executionMetadata, "dataIncomplete")) {
                gaps.add(gap(EvidenceGapReason.DATA_INCOMPLETE, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Structured data is incomplete"));
            }
            if (coverage < requiredCoverage || semanticInsufficient) {
                gaps.add(gap(EvidenceGapReason.LOW_COVERAGE, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, semanticInsufficient
                        ? "Semantic sufficiency check reported an evidence gap"
                        : "Evidence coverage is below the required threshold"));
            }
            if (verification != null && !verification.accepted() && gaps.isEmpty()) {
                gaps.add(gap(EvidenceGapReason.RETRIEVAL_WEAK, anchor, coverage, requiredCoverage,
                    sequenceSensitive, truncated, "Primary workflow did not admit the evidence"));
            }
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("hardRulesApplied", true);
        diagnostics.put("evidenceCount", evidence.size());
        diagnostics.put("documentEvidenceCount", documents.size());
        diagnostics.put("sequenceSensitive", sequenceSensitive);
        diagnostics.put("requiredCoverage", requiredCoverage);
        diagnostics.put("semanticSignalApplied", executionMetadata.containsKey("semanticCoverage")
            || executionMetadata.containsKey("semanticSufficient")
            || bundleMetadata.containsKey("semanticSufficient"));
        diagnostics.put("gapReasons", gaps.stream().map(gap -> gap.reason().name()).distinct().toList());
        return new EvidenceEvaluation(gaps.isEmpty()
            ? EvidenceEvaluation.Decision.SUFFICIENT : EvidenceEvaluation.Decision.GAP,
            gaps, coverage, diagnostics);
    }

    private EvidenceGap gap(EvidenceGapReason reason, DocumentAnalysisEvidence anchor,
                            double coverage, double requiredCoverage, boolean sequenceSensitive,
                            boolean truncated, String missing) {
        return new EvidenceGap(reason, null,
            anchor == null ? null : anchor.documentId(), anchor == null ? null : anchor.section(),
            coverage, requiredCoverage, sequenceSensitive, truncated, List.of(missing));
    }

    private boolean sequenceSensitive(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "install", "installation", "deploy", "deployment", "upgrade",
            "migration", "migrate", "procedure", "steps", "安装", "部署", "升级", "迁移", "步骤", "流程", "操作");
    }

    private boolean explicitlyFalse(Map<String, Object> primary, String key, Map<String, Object> secondary) {
        Object value = first(primary.get(key), secondary.get(key));
        return value != null && !booleanValue(value);
    }

    private boolean booleanValue(Map<String, Object> values, String key) {
        return values != null && booleanValue(values.get(key));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) return Math.max(0D, Math.min(1D, number.doubleValue()));
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return value == null ? fallback : Math.max(0, Integer.parseInt(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private Object first(Object first, Object second) { return first == null ? second : first; }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
