package com.chatchat.knowledgebase.search;

import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class QueryExpander {

    private final SearchTokenizer tokenizer;
    private final QueryIntentClassifier intentClassifier;
    private final RetrievalRuleService ruleService;

    public List<String> expandTokens(List<String> tokens) {
        return expandTokens(tokens, QueryIntent.GENERAL);
    }

    public List<String> expandTokens(List<String> tokens, QueryIntent intent) {
        return expandTokens(tokens, intent == null ? QueryIntent.GENERAL.name() : intent.name(), "");
    }

    public List<String> expandTokens(List<String> tokens, String intentName, String query) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String token : tokens) {
            addToken(expanded, token);
        }
        String normalizedQuery = normalize(query);
        applySemanticLexicon(expanded, normalizedQuery, tokens);
        for (RetrievalRuleService.ExpandRule rule : ruleService.snapshot().expandRules()) {
            if (!intentMatches(rule.intent(), intentName) || !sourceMatches(rule.sourceWord(), normalizedQuery, tokens)) {
                continue;
            }
            for (int i = 0; i < rule.weight(); i++) {
                for (String expandWord : rule.expandWords()) {
                    addToken(expanded, expandWord);
                }
            }
        }
        return new ArrayList<>(expanded);
    }

    public List<String> expandQuery(String query) {
        String normalizedQuery = normalizeQuery(query);
        List<String> tokens = tokenizer.searchTokens(normalizedQuery);
        return expandTokens(tokens, intentClassifier.classifyName(normalizedQuery, tokens), normalizedQuery);
    }

    public QueryIntent classifyIntent(String query) {
        return intentClassifier.classify(normalizeQuery(query));
    }

    public String classifyIntentName(String query) {
        return intentClassifier.classifyName(normalizeQuery(query));
    }

    public String rewriteQuery(String query) {
        return String.join(" ", expandQuery(query));
    }

    public String normalizeQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return "";
        }
        Set<String> appended = new LinkedHashSet<>();
        String normalizedText = normalize(normalized);
        for (RetrievalRuleService.SemanticLexiconEntry entry : ruleService.snapshot().semanticLexicon()) {
            if (!lexiconMatches(entry, normalizedText, tokenizer.searchTokens(normalizedText))) {
                continue;
            }
            addRawLexiconValue(appended, entry.term(), normalizedText);
            addRawLexiconValue(appended, entry.mappedTerm(), normalizedText);
        }
        if (appended.isEmpty()) {
            return normalized;
        }
        return normalized + " " + String.join(" ", appended);
    }

    private boolean intentMatches(String ruleIntent, String intentName) {
        if (ruleIntent == null || ruleIntent.isBlank()) {
            return true;
        }
        return normalize(ruleIntent).equals(normalize(intentName));
    }

    private boolean sourceMatches(String sourceWord, String query, List<String> tokens) {
        String source = normalize(sourceWord);
        if (source.isBlank()) {
            return true;
        }
        if (!query.isBlank() && query.contains(source)) {
            return true;
        }
        for (String token : tokens) {
            String normalizedToken = normalize(token);
            if (normalizedToken.equals(source) || normalizedToken.contains(source) || source.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private void applySemanticLexicon(Set<String> expanded, String query, List<String> tokens) {
        for (RetrievalRuleService.SemanticLexiconEntry entry : ruleService.snapshot().semanticLexicon()) {
            if (!lexiconMatches(entry, query, tokens)) {
                continue;
            }
            for (int i = 0; i < Math.max(1, entry.weight()); i++) {
                addToken(expanded, entry.term());
                addToken(expanded, entry.mappedTerm());
                for (String alias : entry.aliases()) {
                    addToken(expanded, alias);
                }
                addToken(expanded, entry.category());
                addToken(expanded, entry.domain());
            }
        }
    }

    private boolean lexiconMatches(RetrievalRuleService.SemanticLexiconEntry entry, String query, List<String> tokens) {
        if (entry == null) {
            return false;
        }
        if (sourceMatches(entry.term(), query, tokens) || sourceMatches(entry.mappedTerm(), query, tokens)) {
            return true;
        }
        for (String alias : entry.aliases()) {
            if (sourceMatches(alias, query, tokens)) {
                return true;
            }
        }
        return false;
    }

    private void addRawLexiconValue(Set<String> target, String value, String normalizedQuery) {
        String normalized = normalize(value);
        if (normalized.isBlank() || normalizedQuery.contains(normalized)) {
            return;
        }
        target.add(normalized);
    }

    private void addToken(Set<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : tokenizer.searchTokens(value)) {
            if (!token.isBlank()) {
                target.add(token);
            }
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
