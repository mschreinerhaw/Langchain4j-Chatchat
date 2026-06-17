package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class KeywordExtractor {

    private static final int DEFAULT_LIMIT = 12;

    private final SearchTokenizer tokenizer;

    public List<String> extractKeywords(String text) {
        return extractKeywords(text, DEFAULT_LIMIT);
    }

    public List<String> extractKeywords(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Long> frequencies = new LinkedHashMap<>();
        for (String token : tokenizer.tokenizeOccurrences(text)) {
            if (tokenizer.isSearchNoiseToken(token)) {
                continue;
            }
            if (isLowValueToken(token)) {
                continue;
            }
            frequencies.merge(token, 1L, Long::sum);
        }
        return frequencies.entrySet().stream()
            .sorted(Comparator
                .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(entry -> -entry.getKey().length())
                .thenComparing(Map.Entry::getKey))
            .limit(Math.max(1, limit))
            .map(Map.Entry::getKey)
            .toList();
    }

    public List<String> mergeKeywords(List<String> existingKeywords, String text) {
        Set<String> merged = new LinkedHashSet<>();
        if (existingKeywords != null) {
            for (String keyword : existingKeywords) {
                if (keyword != null && !keyword.isBlank()) {
                    merged.add(keyword.trim());
                }
            }
        }
        merged.addAll(extractKeywords(text));
        return new ArrayList<>(merged);
    }

    private boolean isLowValueToken(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        if (token.length() <= 2 && token.chars().allMatch(ch -> ch < 128)) {
            return true;
        }
        return token.chars().allMatch(Character::isDigit);
    }
}
