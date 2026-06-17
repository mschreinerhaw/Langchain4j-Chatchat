package com.chatchat.knowledgebase.search;

import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class QueryIntentClassifier {

    private final SearchTokenizer tokenizer;
    private final RetrievalRuleService ruleService;

    public QueryIntent classify(String query) {
        return classify(query, tokenizer.searchTokens(query));
    }

    public QueryIntent classify(String query, List<String> tokens) {
        return parseIntent(classifyName(query, tokens));
    }

    public String classifyName(String query) {
        return classifyName(query, tokenizer.searchTokens(query));
    }

    public String classifyName(String query, List<String> tokens) {
        String normalized = normalize(query);
        RetrievalRuleService.IntentRule bestRule = null;
        int bestScore = 0;
        for (RetrievalRuleService.IntentRule rule : ruleService.snapshot().intentRules()) {
            int score = score(normalized, tokens, rule);
            if (score > bestScore || (score == bestScore && shouldPrefer(rule, bestRule))) {
                bestRule = rule;
                bestScore = score;
            }
        }
        if (bestRule != null && bestScore > 0) {
            return bestRule.intent();
        }
        return QueryIntent.GENERAL.name();
    }

    private int score(String query, List<String> tokens, RetrievalRuleService.IntentRule rule) {
        int score = 0;
        for (String term : rule.keywords()) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && query.contains(normalizedTerm)) {
                score += (normalizedTerm.length() > 3 ? 2 : 1) * rule.weight();
            }
        }
        if (rule.pattern() != null && rule.pattern().matcher(query).find()) {
            score += 5 * rule.weight();
        }
        if (tokens != null) {
            for (String token : tokens) {
                String normalizedToken = normalize(token);
                for (String term : rule.keywords()) {
                    if (normalizedToken.equals(normalize(term))) {
                        score += 2 * rule.weight();
                    }
                }
            }
        }
        return score > 0 ? score + rule.priority() * 2 : 0;
    }

    private boolean shouldPrefer(RetrievalRuleService.IntentRule candidate,
                                 RetrievalRuleService.IntentRule current) {
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

    private QueryIntent parseIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return QueryIntent.GENERAL;
        }
        try {
            return QueryIntent.valueOf(intent.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return QueryIntent.GENERAL;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
