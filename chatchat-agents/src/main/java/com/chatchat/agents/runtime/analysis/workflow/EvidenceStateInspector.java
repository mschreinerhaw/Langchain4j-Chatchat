package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGapReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Observes acquisition facts only. Unknown boundaries do not imply missing evidence. */
final class EvidenceStateInspector {
    record State(int evidenceCount, List<EvidenceGap> issues) {
        Map<String, Object> projection() {
            return Map.of("evidenceCount", evidenceCount, "issues", issues.stream().map(issue -> {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("reason", issue.reason().name());
                if (issue.documentId() != null) value.put("documentId", issue.documentId());
                if (issue.sectionId() != null) value.put("sectionId", issue.sectionId());
                value.put("observations", issue.missingEvidence());
                return Map.copyOf(value);
            }).toList(),
                "transportState", evidenceCount == 0 ? "UNAVAILABLE"
                    : issues.isEmpty() ? "AVAILABLE" : "PARTIALLY_AVAILABLE");
        }
    }

    State inspect(EvidenceBundle bundle, Map<String, Object> executionMetadata) {
        List<EvidenceGap> issues = new ArrayList<>();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(executionMetadata);
        metadata.putAll(bundle.metadata());
        DocumentAnalysisEvidence anchor = bundle.evidence().stream()
            .filter(DocumentAnalysisEvidence.class::isInstance).map(DocumentAnalysisEvidence.class::cast)
            .findFirst().orElse(null);
        if (bundle.evidence().isEmpty()) {
            issues.add(issue(EvidenceGapReason.RETRIEVAL_EMPTY, null, "Retrieval returned no items"));
        }
        if (Boolean.TRUE.equals(metadata.get("truncated"))
            || Boolean.TRUE.equals(metadata.get("sourceTruncated"))) {
            issues.add(issue(EvidenceGapReason.SOURCE_TRUNCATED, anchor, "Source retrieval was truncated"));
        } else {
            bundle.evidence().stream().filter(item -> Boolean.TRUE.equals(item.attributes().get("truncated")))
                .forEach(item -> issues.add(issue(EvidenceGapReason.SOURCE_TRUNCATED,
                    item instanceof DocumentAnalysisEvidence document ? document : null,
                    "Source retrieval was truncated")));
        }
        if (Boolean.TRUE.equals(metadata.get("sectionBoundaryKnown"))
            && Boolean.FALSE.equals(metadata.get("sectionComplete"))) {
            issues.add(issue(EvidenceGapReason.SECTION_INCOMPLETE, anchor, "Known section was not fully fetched"));
        }
        if (Boolean.TRUE.equals(metadata.get("sequenceBoundaryKnown"))
            && Boolean.FALSE.equals(metadata.get("sequenceComplete"))) {
            issues.add(issue(EvidenceGapReason.SEQUENCE_INCOMPLETE, anchor, "Known sequence was not fully fetched"));
        }
        return new State(bundle.evidence().size(), List.copyOf(issues));
    }

    // Adapt to the existing recovery SPI; coverage and claim judgments are deliberately unused.
    private EvidenceGap issue(EvidenceGapReason reason, DocumentAnalysisEvidence anchor, String description) {
        return new EvidenceGap(reason, null, anchor == null ? null : anchor.documentId(),
            anchor == null ? null : anchor.section(), 0D, 0D,
            reason == EvidenceGapReason.SEQUENCE_INCOMPLETE,
            reason == EvidenceGapReason.SOURCE_TRUNCATED, List.of(description));
    }
}
