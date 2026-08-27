package com.chatchat.knowledgebase.search.query;

import com.chatchat.knowledgebase.search.model.SearchDocument;

import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ChunkTypeClassifier {

    private final RetrievalRuleService ruleService;

    public String classify(SearchDocument document, TextChunker.TextChunk chunk) {
        String content = normalize(chunk == null ? "" : chunk.content());
        String section = normalize(chunk == null ? "" : chunk.section());
        if (isOcrText(content) || isOcrText(section)) {
            return ChunkType.OCR_TEXT.value();
        }
        String title = normalize(document == null ? "" : document.getTitle());
        String combined = title + " " + section + " " + content;

        RetrievalRuleService.ChunkRule bestRule = null;
        int bestScore = 0;
        for (RetrievalRuleService.ChunkRule rule : ruleService.snapshot().matchingChunkRules(combined)) {
            int score = score(combined, rule);
            if (score > bestScore || (score == bestScore && shouldPrefer(rule, bestRule))) {
                bestRule = rule;
                bestScore = score;
            }
        }
        if (bestRule != null && bestScore > 0) {
            return bestRule.chunkType();
        }
        return ChunkType.GENERAL.value();
    }

    private int score(String value, RetrievalRuleService.ChunkRule rule) {
        int score = 0;
        for (String keyword : rule.keywords()) {
            String normalizedKeyword = normalize(keyword);
            int frequency = termFrequency(value, normalizedKeyword);
            if (frequency > 0) {
                score += frequency * (normalizedKeyword.length() > 3 ? 2 : 1) * rule.weight();
            }
        }
        if (rule.pattern() != null && rule.pattern().matcher(value).find()) {
            score += 3 * rule.weight();
        }
        return score > 0 ? score + rule.priority() * 2 : 0;
    }

    private boolean shouldPrefer(RetrievalRuleService.ChunkRule candidate,
                                 RetrievalRuleService.ChunkRule current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        if (candidate.priority() != current.priority()) {
            return candidate.priority() > current.priority();
        }
        return candidate.weight() > current.weight();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean isOcrText(String value) {
        return value != null && (value.startsWith("# ocr_text") || value.startsWith("ocr_text"));
    }

    private int termFrequency(String value, String term) {
        if (value == null || term == null || term.isBlank()) {
            return 0;
        }
        int frequency = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(term, cursor)) >= 0) {
            frequency++;
            cursor += term.length();
        }
        return frequency;
    }
}
