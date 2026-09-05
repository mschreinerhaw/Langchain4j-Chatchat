package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.evidence.answer.DeterministicAnswerCompiler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses committed document evidence without invoking a model or deciding publication. */
final class GroundedDocumentEvidenceReader {
    private static final String DOCUMENT_EVIDENCE_CONTRACT = "document_evidence_v1";
    private static final String UNIFIED_EVIDENCE_CONTRACT = "evidence_v1";

    List<AnswerDecisionEngine.GroundedDocumentEvidence> extract(List<String> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence = new ArrayList<>();
        for (String observation : observations) {
            evidence.addAll(extractGroundedDocumentEvidence(observation));
        }
        Map<String, AnswerDecisionEngine.GroundedDocumentEvidence> unique = new LinkedHashMap<>();
        for (AnswerDecisionEngine.GroundedDocumentEvidence item : evidence) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            unique.putIfAbsent(groundedEvidenceDedupKey(item), item);
            if (unique.size() >= 8) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private String groundedEvidenceDedupKey(AnswerDecisionEngine.GroundedDocumentEvidence item) {
        String documentId = documentIdFromCitation(item.citation());
        String source = firstNonBlank(item.source(), firstNonBlank(documentId, ""));
        String section = firstNonBlank(item.section(), "");
        String normalizedContent = item.content()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\p{S}\\s]+", "");
        return source.trim().toLowerCase(Locale.ROOT)
            + "|" + section.trim().toLowerCase(Locale.ROOT)
            + "|" + normalizedContent;
    }

    private String documentIdFromCitation(String citation) {
        if (citation == null || !citation.startsWith("doc://")) {
            return null;
        }
        int fragment = citation.indexOf('#');
        return fragment > 6 ? citation.substring(6, fragment) : citation.substring(6);
    }

    private List<AnswerDecisionEngine.GroundedDocumentEvidence> extractGroundedDocumentEvidence(String observation) {
        if (observation == null || observation.isBlank()
            || (!observation.contains("doc://") && !observation.contains(DOCUMENT_EVIDENCE_CONTRACT)
            && !observation.contains(UNIFIED_EVIDENCE_CONTRACT))) {
            return List.of();
        }
        List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence = new ArrayList<>();
        GroundedDocumentEvidenceBuilder current = null;
        boolean capturingContent = false;
        for (String rawLine : observation.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("[Evidence ")) {
                addGroundedEvidence(evidence, current);
                current = new GroundedDocumentEvidenceBuilder();
                capturingContent = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("type:")) {
                current.type = line.substring("type:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("citation:")) {
                current.citation = line.substring("citation:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("source:")) {
                current.source = line.substring("source:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("section:")) {
                current.section = line.substring("section:".length()).trim();
                capturingContent = false;
            } else if (line.startsWith("content:")) {
                capturingContent = true;
            } else if (capturingContent) {
                if (isEvidenceContextBoundary(line)) {
                    capturingContent = false;
                } else if (!line.isBlank()) {
                    if (!current.content.isEmpty()) {
                        current.content.append(' ');
                    }
                    current.content.append(shortText(line, 420));
                }
            }
        }
        addGroundedEvidence(evidence, current);
        return evidence;
    }

    private void addGroundedEvidence(List<AnswerDecisionEngine.GroundedDocumentEvidence> evidence,
                                     GroundedDocumentEvidenceBuilder current) {
        if (current == null) {
            return;
        }
        String type = current.type == null ? "" : current.type.trim();
        String citation = current.citation == null ? "" : current.citation.trim();
        if (!"DOCUMENT".equalsIgnoreCase(type) && !citation.startsWith("doc://")) {
            return;
        }
        String content = current.content.toString().trim();
        if (content.isBlank()) {
            return;
        }
        evidence.add(new AnswerDecisionEngine.GroundedDocumentEvidence(
            blankToNull(citation),
            blankToNull(current.source),
            blankToNull(current.section),
            content
        ));
    }

    private boolean isEvidenceContextBoundary(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.startsWith("[Evidence ")
            || line.startsWith("Evidence audit:")
            || line.startsWith("Document search summary:")
            || line.startsWith("Document evidence snippets:")
            || line.startsWith("Citation rule:")
            || line.startsWith(DeterministicAnswerCompiler.LOCK_HEADER)
            || line.startsWith(DeterministicAnswerCompiler.BEGIN_LOCKED_ANSWER)
            || line.startsWith(DeterministicAnswerCompiler.END_LOCKED_ANSWER);
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static class GroundedDocumentEvidenceBuilder {
        private String type;
        private String citation;
        private String source;
        private String section;
        private final StringBuilder content = new StringBuilder();
    }
}
