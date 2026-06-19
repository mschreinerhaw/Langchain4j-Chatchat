package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RetrievalEvidenceQualityScorer {

    private final SearchProperties properties;

    public RetrievalEvidenceQuality score(List<DocumentEvidenceChunk> chunks) {
        SearchProperties.RetrievalControl control = properties.getRetrievalControl();
        if (control == null || !control.isEnabled() || !control.isQualityScoringEnabled()) {
            return new RetrievalEvidenceQuality(true, 1.0D, "quality_scoring_disabled");
        }
        if (chunks == null || chunks.isEmpty()) {
            return RetrievalEvidenceQuality.empty("empty_result");
        }
        double bestScore = chunks.stream()
            .filter(chunk -> chunk != null && chunk.score() != null)
            .mapToDouble(DocumentEvidenceChunk::score)
            .max()
            .orElse(0.0D);
        long chunksWithContent = chunks.stream()
            .filter(chunk -> chunk != null && hasText(chunk.content()))
            .count();
        if (chunksWithContent == 0) {
            return RetrievalEvidenceQuality.empty("low_density");
        }
        double minScore = Math.max(0.0D, control.getMinQualityScore());
        if (bestScore < minScore) {
            return new RetrievalEvidenceQuality(false, confidence(bestScore), "weak_match");
        }
        double density = Math.min(1.0D, chunksWithContent / Math.max(1.0D, chunks.size()));
        return new RetrievalEvidenceQuality(true, Math.min(1.0D, confidence(bestScore) * density), "usable");
    }

    private double confidence(double score) {
        return Math.round(Math.max(0.0D, Math.min(1.0D, score / 100.0D)) * 100.0D) / 100.0D;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
