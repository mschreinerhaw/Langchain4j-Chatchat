package com.chatchat.knowledgebase.search;

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
        String title = normalize(document == null ? "" : document.getTitle());
        String combined = title + " " + section + " " + content;

        RetrievalRuleService.ChunkRule bestRule = null;
        int bestScore = 0;
        for (RetrievalRuleService.ChunkRule rule : ruleService.snapshot().chunkRules()) {
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
            if (!normalizedKeyword.isBlank() && value.contains(normalizedKeyword)) {
                score += (normalizedKeyword.length() > 3 ? 2 : 1) * rule.weight();
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
}
