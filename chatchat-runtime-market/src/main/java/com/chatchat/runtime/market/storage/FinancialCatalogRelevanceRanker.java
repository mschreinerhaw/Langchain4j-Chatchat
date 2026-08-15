package com.chatchat.runtime.market.storage;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data-driven catalog reranking that is independent of any financial vocabulary or dataset code.
 *
 * <p>The index is used for broad candidate recall. This class then compares the request only with
 * the catalog's semantic fields. Character n-grams make the comparison work for languages without
 * whitespace tokenization, while corpus IDF suppresses boilerplate shared by many catalog entries.</p>
 */
public final class FinancialCatalogRelevanceRanker {
    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final double RELATIVE_SCORE_FLOOR = 0.22D;

    private FinancialCatalogRelevanceRanker() {
    }

    public static List<Map<String, Object>> rank(String query,
                                                  List<Map<String, Object>> candidates,
                                                  int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return candidates.stream().limit(limit).toList();

        List<WeightedDocument> documents = candidates.stream()
            .filter(java.util.Objects::nonNull)
            .map(FinancialCatalogRelevanceRanker::document)
            .toList();
        if (documents.isEmpty()) return List.of();

        Map<String, Integer> documentFrequency = new HashMap<>();
        for (WeightedDocument document : documents) {
            for (String term : document.weights().keySet()) {
                if (queryTerms.contains(term)) documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        int population = documents.size();
        List<ScoredDocument> scored = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            WeightedDocument document = documents.get(index);
            double score = 0.0D;
            int matchedTerms = 0;
            for (String term : queryTerms) {
                Double fieldWeight = document.weights().get(term);
                if (fieldWeight == null) continue;
                double idf = Math.log((population + 1.0D)
                    / (documentFrequency.getOrDefault(term, 0) + 1.0D)) + 1.0D;
                score += idf * fieldWeight * termWeight(term);
                matchedTerms++;
            }
            if (matchedTerms > 0) {
                // Reward broad intent coverage without allowing repeated catalog boilerplate to help.
                score *= 1.0D + Math.log1p(matchedTerms) * 0.18D;
            }
            scored.add(new ScoredDocument(document.source(), score, index));
        }
        scored.sort(java.util.Comparator.comparingDouble(ScoredDocument::score).reversed()
            .thenComparingInt(ScoredDocument::originalOrder));
        double best = scored.get(0).score();
        if (best <= 0.0D) return candidates.stream().limit(limit).toList();
        double floor = best * RELATIVE_SCORE_FLOOR;

        return scored.stream()
            .filter(item -> item.score() >= floor)
            .limit(limit)
            .map(item -> withRelevance(item.source(), item.score()))
            .toList();
    }

    private static WeightedDocument document(Map<String, Object> source) {
        Map<String, Double> weights = new HashMap<>();
        add(weights, source.get("asset_name"), 5.0D);
        add(weights, source.get("title"), 5.0D);
        add(weights, source.get("business_tags_json"), 4.5D);
        add(weights, source.get("business_description"), 3.0D);
        add(weights, source.get("description"), 3.0D);
        add(weights, source.get("dataset_code"), 2.0D);
        add(weights, source.get("table_name"), 1.5D);
        Object fields = source.get("fields");
        if (fields instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (!(value instanceof Map<?, ?> field)) continue;
                add(weights, field.get("business_description"), 2.0D);
                add(weights, field.get("field_name"), 1.25D);
                add(weights, field.get("source_field"), 1.0D);
            }
        }
        return new WeightedDocument(source, Map.copyOf(weights));
    }

    private static void add(Map<String, Double> target, Object value, double weight) {
        if (value == null) return;
        for (String term : terms(String.valueOf(value))) {
            target.merge(term, weight, Math::max);
        }
    }

    private static Set<String> terms(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = SEGMENT.matcher(normalized);
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment.codePoints().allMatch(Character::isDigit)) continue;
            int[] points = segment.codePoints().toArray();
            if (points.length >= 2 && points.length <= 24) result.add(segment);
            for (int size = 2; size <= 3; size++) {
                for (int start = 0; start + size <= points.length; start++) {
                    String term = new String(points, start, size);
                    if (!term.codePoints().allMatch(Character::isDigit)) result.add(term);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static double termWeight(String term) {
        int length = term.codePointCount(0, term.length());
        if (length >= 4) return 1.7D;
        if (length == 3) return 1.25D;
        return 1.0D;
    }

    private static Map<String, Object> withRelevance(Map<String, Object> source, double semanticScore) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        Object engineScore = result.get("relevance_score");
        if (engineScore != null) result.put("search_engine_score", engineScore);
        result.put("relevance_score", Math.round(semanticScore * 1000.0D) / 1000.0D);
        result.put("relevance_strategy", "catalog_semantic_idf_ngram_v1");
        return java.util.Collections.unmodifiableMap(result);
    }

    private record WeightedDocument(Map<String, Object> source, Map<String, Double> weights) {
    }

    private record ScoredDocument(Map<String, Object> source, double score, int originalOrder) {
    }
}
