package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.model.NewsDocument;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data-driven quality ranking for local news results without topic-specific vocabulary. */
public final class NewsRelevanceRanker {
    public static final String STRATEGY = "news_semantic_idf_ngram_v1";
    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
        "(?<!\\d)\\d{4}(?:年|[-/.])\\d{1,2}(?:月|[-/.])\\d{1,2}日?(?!\\d)");
    private static final double RELATIVE_SCORE_FLOOR = 0.20D;
    private static final double MINIMUM_QUERY_COVERAGE = 0.08D;
    private static final int MAX_CONTENT_CHARS = 6_000;

    private NewsRelevanceRanker() {
    }

    public static List<RankedNews> rank(String query, List<NewsDocument> candidates, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> queryTerms = terms(withoutExplicitDate(query));
        if (queryTerms.isEmpty()) {
            return candidates.stream().filter(java.util.Objects::nonNull).limit(limit)
                .map(document -> new RankedNews(document, 1.0D, 0, 1.0D)).toList();
        }

        List<WeightedNews> documents = candidates.stream()
            .filter(java.util.Objects::nonNull)
            .map(NewsRelevanceRanker::document)
            .toList();
        if (documents.isEmpty()) return List.of();
        Map<String, Integer> frequency = new HashMap<>();
        for (WeightedNews document : documents) {
            for (String term : document.weights().keySet()) {
                if (queryTerms.contains(term)) frequency.merge(term, 1, Integer::sum);
            }
        }

        double queryWeight = queryTerms.stream().mapToDouble(NewsRelevanceRanker::termWeight).sum();
        List<ScoredNews> scored = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            WeightedNews document = documents.get(index);
            double score = 0.0D;
            double matchedWeight = 0.0D;
            int matched = 0;
            for (String term : queryTerms) {
                Double fieldWeight = document.weights().get(term);
                if (fieldWeight == null) continue;
                double weight = termWeight(term);
                double idf = Math.log((documents.size() + 1.0D)
                    / (frequency.getOrDefault(term, 0) + 1.0D)) + 1.0D;
                score += weight * idf * fieldWeight;
                matchedWeight += weight;
                matched++;
            }
            double coverage = queryWeight <= 0.0D ? 0.0D : matchedWeight / queryWeight;
            if (matched > 0) score *= 1.0D + Math.log1p(matched) * 0.15D;
            scored.add(new ScoredNews(document.document(), score, matched, coverage, index));
        }
        scored.sort(java.util.Comparator.comparingDouble(ScoredNews::score).reversed()
            .thenComparingInt(ScoredNews::originalOrder));
        double best = scored.get(0).score();
        if (best <= 0.0D) return List.of();
        double floor = best * RELATIVE_SCORE_FLOOR;
        return scored.stream()
            .filter(item -> item.matchedTerms() > 0
                && item.coverage() >= MINIMUM_QUERY_COVERAGE
                && item.score() >= floor)
            .limit(limit)
            .map(item -> new RankedNews(item.document(), round(item.score()), item.matchedTerms(),
                round(item.coverage())))
            .toList();
    }

    private static WeightedNews document(NewsDocument source) {
        Map<String, Double> weights = new HashMap<>();
        add(weights, source.title(), 6.0D);
        add(weights, source.tags(), 5.0D);
        add(weights, source.categories(), 4.5D);
        add(weights, source.summary(), 3.0D);
        add(weights, source.sourceName(), 1.5D);
        String content = source.content();
        add(weights, content == null || content.length() <= MAX_CONTENT_CHARS
            ? content : content.substring(0, MAX_CONTENT_CHARS), 1.0D);
        return new WeightedNews(source, Map.copyOf(weights));
    }

    private static void add(Map<String, Double> target, Object value, double weight) {
        if (value == null) return;
        if (value instanceof Iterable<?> values) {
            for (Object item : values) add(target, item, weight);
            return;
        }
        for (String term : terms(String.valueOf(value))) target.merge(term, weight, Math::max);
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
            if (points.length >= 2 && points.length <= 32) result.add(segment);
            for (int size = 2; size <= 3; size++) {
                for (int start = 0; start + size <= points.length; start++) {
                    String term = new String(points, start, size);
                    if (!term.codePoints().allMatch(Character::isDigit)) result.add(term);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String withoutExplicitDate(String query) {
        return query == null ? "" : EXPLICIT_DATE.matcher(query).replaceAll(" ");
    }

    private static double termWeight(String term) {
        int length = term.codePointCount(0, term.length());
        if (length >= 4) return 1.7D;
        if (length == 3) return 1.25D;
        return 1.0D;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    public record RankedNews(NewsDocument document, double score, int matchedTerms, double coverage) {
    }

    private record WeightedNews(NewsDocument document, Map<String, Double> weights) {
    }

    private record ScoredNews(NewsDocument document, double score, int matchedTerms,
                              double coverage, int originalOrder) {
    }
}
