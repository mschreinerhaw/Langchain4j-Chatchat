package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AnswerGroundingGuard {

    private static final String INSUFFICIENT_EVIDENCE_ANSWER = "根据当前文档证据不足，无法确认。";

    private final SearchTokenizer tokenizer;
    private final EvidenceContextFormatter formatter;

    public EvidenceAnswer guard(EvidenceAnswer answer, List<DocumentEvidenceChunk> evidence) {
        List<DocumentEvidenceChunk> chunks = evidence == null ? List.of() : evidence;
        if (answer == null) {
            return insufficient(List.of("answer is missing"));
        }
        List<String> missing = new ArrayList<>(answer.missingInfo() == null ? List.of() : answer.missingInfo());
        if (chunks.isEmpty()) {
            missing.add("document evidence is missing");
            return insufficient(missing);
        }
        if (!hasText(answer.answer())) {
            missing.add("answer text is missing");
            return insufficient(missing);
        }
        if (answer.citations() == null || answer.citations().isEmpty()) {
            missing.add("answer citations are missing");
            return insufficient(missing);
        }
        Set<String> evidenceRefIds = new LinkedHashSet<>();
        StringBuilder evidenceText = new StringBuilder();
        for (DocumentEvidenceChunk chunk : chunks) {
            evidenceRefIds.add(formatter.refId(chunk));
            if (chunk.content() != null) {
                evidenceText.append(' ').append(chunk.content());
            }
            if (chunk.section() != null) {
                evidenceText.append(' ').append(chunk.section());
            }
            if (chunk.fileName() != null) {
                evidenceText.append(' ').append(chunk.fileName());
            }
        }
        boolean hasUnknownCitation = answer.citations().stream()
            .map(AnswerCitation::refId)
            .filter(this::hasText)
            .anyMatch(refId -> !evidenceRefIds.contains(refId));
        if (hasUnknownCitation) {
            missing.add("answer cites evidence not returned by document_search");
            return insufficient(missing);
        }
        if (!isGrounded(answer.answer(), evidenceText.toString())) {
            missing.add("answer conclusion is not grounded in evidence content");
            return insufficient(missing);
        }
        return new EvidenceAnswer(
            answer.answer(),
            List.copyOf(answer.citations()),
            hasText(answer.confidence()) ? answer.confidence() : "medium",
            List.copyOf(missing)
        );
    }

    public EvidenceAnswer fromAnswer(String answer, List<DocumentEvidenceChunk> evidence) {
        EvidenceAnswer candidate = formatter.toEvidenceAnswer(answer, evidence, "medium", List.of());
        return guard(candidate, evidence);
    }

    private boolean isGrounded(String answer, String evidenceText) {
        String normalizedEvidence = normalize(evidenceText);
        if (!hasText(normalizedEvidence)) {
            return false;
        }
        List<String> answerTerms = tokenizer.searchTokens(answer).stream()
            .map(this::normalize)
            .filter(this::isSignalTerm)
            .distinct()
            .toList();
        if (answerTerms.isEmpty()) {
            return true;
        }
        long matched = answerTerms.stream()
            .filter(normalizedEvidence::contains)
            .count();
        return matched >= Math.min(2, answerTerms.size());
    }

    private EvidenceAnswer insufficient(List<String> missingInfo) {
        return new EvidenceAnswer(
            INSUFFICIENT_EVIDENCE_ANSWER,
            List.of(),
            "low",
            missingInfo == null ? List.of() : List.copyOf(missingInfo)
        );
    }

    private boolean isSignalTerm(String term) {
        if (!hasText(term)) {
            return false;
        }
        String normalized = normalize(term);
        if (normalized.length() < 2) {
            return false;
        }
        return !List.of(
            "the", "and", "for", "with", "this", "that", "from",
            "检查", "处理", "需要", "可以", "如果", "首先", "当前", "文档", "证据"
        ).contains(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
