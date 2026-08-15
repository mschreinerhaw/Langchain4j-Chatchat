package com.chatchat.mcpserver.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backend-independent quality ranking for logical asset discovery.
 *
 * <p>The search backend is responsible for recall. This class uses candidate-local text,
 * query-term coverage and an IDF-style weight to decide which retrieved assets are relevant
 * enough to expose. It intentionally contains no domain or business keyword dictionaries.</p>
 */
public final class AssetRelevanceRanker {

    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int MIN_EXTRA_CANDIDATES = 8;
    private static final int MAX_CANDIDATES = 100;
    private static final double MIN_QUERY_COVERAGE = 0.34D;
    private static final double MIN_RELATIVE_SCORE = 0.42D;

    private AssetRelevanceRanker() {
    }

    public static int expandedLimit(int requestedLimit, int availableCandidates) {
        int available = availableCandidates <= 0 ? MAX_CANDIDATES : availableCandidates;
        int requested = Math.max(1, requestedLimit);
        long expanded = Math.max((long) requested * CANDIDATE_MULTIPLIER,
            (long) requested + MIN_EXTRA_CANDIDATES);
        return (int) Math.min(Math.min(expanded, MAX_CANDIDATES), available);
    }

    public static <T> List<Ranked<T>> rank(String queryText, List<Candidate<T>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = terms(queryText);
        double maxBackendScore = candidates.stream()
            .mapToDouble(Candidate::backendScore)
            .max()
            .orElse(0.0D);
        Map<String, Double> idf = inverseDocumentFrequency(queryTerms, candidates);
        List<Ranked<T>> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate<T> candidate = candidates.get(index);
            Set<String> identityTerms = searchableTerms(candidate.identityTexts());
            Set<String> contentTerms = searchableTerms(candidate.contentTexts());
            int matched = 0;
            double matchedWeight = 0.0D;
            double totalWeight = 0.0D;
            for (String term : queryTerms) {
                double weight = idf.getOrDefault(term, 1.0D);
                totalWeight += weight;
                if (matches(term, identityTerms) || matches(term, contentTerms)) {
                    matched++;
                    matchedWeight += weight;
                }
            }
            double coverage = totalWeight <= 0.0D ? 0.0D : matchedWeight / totalWeight;
            boolean identityMatch = exactIdentity(queryText, candidate.identityTexts());
            double backend = maxBackendScore > 0.0D
                ? Math.max(0.0D, candidate.backendScore()) / maxBackendScore
                : 0.0D;
            double score = Math.min(1.0D,
                coverage * 0.72D + (identityMatch ? 0.18D : 0.0D) + backend * 0.10D);
            ranked.add(new Ranked<>(candidate.value(), score, coverage, matched,
                queryTerms.size(), identityMatch, backend, index));
        }
        ranked.sort(Comparator
            .comparingDouble((Ranked<T> item) -> item.score()).reversed()
            .thenComparingInt(Ranked::originalOrder));
        if (queryTerms.isEmpty()) {
            return ranked;
        }
        double bestScore = ranked.isEmpty() ? 0.0D : ranked.get(0).score();
        int requiredMatches = queryTerms.size() <= 2 ? 1 : 2;
        return ranked.stream()
            .filter(item -> item.identityMatch()
                || item.matchedTerms() >= requiredMatches
                    && item.coverage() >= MIN_QUERY_COVERAGE
                    && item.score() >= bestScore * MIN_RELATIVE_SCORE)
            .toList();
    }

    private static <T> Map<String, Double> inverseDocumentFrequency(List<String> queryTerms,
                                                                     List<Candidate<T>> candidates) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String term : queryTerms) {
            long documentFrequency = candidates.stream().filter(candidate -> {
                Set<String> searchable = searchableTerms(candidate.identityTexts());
                searchable.addAll(searchableTerms(candidate.contentTexts()));
                return matches(term, searchable);
            }).count();
            weights.put(term, Math.log((candidates.size() + 1.0D) / (documentFrequency + 1.0D)) + 1.0D);
        }
        return weights;
    }

    private static boolean exactIdentity(String queryText, List<String> identityTexts) {
        String query = normalizePhrase(queryText);
        if (query.isBlank()) {
            return false;
        }
        return safe(identityTexts).stream()
            .map(AssetRelevanceRanker::normalizePhrase)
            .anyMatch(query::equals);
    }

    private static Set<String> searchableTerms(List<String> texts) {
        Set<String> result = new LinkedHashSet<>();
        for (String text : safe(texts)) {
            result.addAll(terms(text));
        }
        return result;
    }

    private static boolean matches(String queryTerm, Set<String> candidateTerms) {
        if (queryTerm == null || queryTerm.isBlank()) {
            return false;
        }
        return candidateTerms.stream().anyMatch(candidateTerm ->
            candidateTerm.equals(queryTerm)
                || candidateTerm.length() >= 3 && candidateTerm.contains(queryTerm)
                || queryTerm.length() >= 3 && queryTerm.contains(candidateTerm));
    }

    private static List<String> terms(String value) {
        List<String> segmented = new ArrayList<>(SearchQueryTokenizer.terms(value));
        String phrase = normalizePhrase(value);
        segmented.remove(phrase);
        return segmented.isEmpty() && !phrase.isBlank() ? List.of(phrase) : segmented;
    }

    private static String normalizePhrase(String value) {
        return SearchQueryTokenizer.normalize(value);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record Candidate<T>(T value,
                               List<String> identityTexts,
                               List<String> contentTexts,
                               double backendScore) {
        public Candidate {
            identityTexts = safe(identityTexts);
            contentTexts = safe(contentTexts);
        }
    }

    public record Ranked<T>(T value,
                            double score,
                            double coverage,
                            int matchedTerms,
                            int queryTerms,
                            boolean identityMatch,
                            double normalizedBackendScore,
                            int originalOrder) {
    }
}
