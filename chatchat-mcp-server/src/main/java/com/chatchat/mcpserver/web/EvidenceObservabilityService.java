package com.chatchat.mcpserver.web;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvidenceObservabilityService {

    /**
     * Builds diagnostics for an evidence generation run.
     *
     * @param query the query value
     * @param mode the mode value
     * @param searchResults the search results value
     * @param pages the crawled pages value
     * @param evidenceChunks the normalized evidence chunks value
     * @return the operation result
     */
    public Map<String, Object> summarize(String query,
                                         String mode,
                                         List<Map<String, Object>> searchResults,
                                         List<Map<String, Object>> pages,
                                         List<Map<String, Object>> evidenceChunks) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", "evidence_observability_v1");
        values.put("generatedAt", Instant.now().toEpochMilli());
        values.put("queryType", queryType(query));
        values.put("mode", mode == null || mode.isBlank() ? "fast" : mode);
        values.put("resultCounts", resultCounts(searchResults, pages, evidenceChunks));
        values.put("domainDistribution", domainDistribution(evidenceChunks));
        values.put("scoreDistribution", scoreDistribution(evidenceChunks));
        values.put("rerankDrift", rerankDrift(evidenceChunks));
        values.put("cache", cacheStats(pages));
        return values;
    }

    private String queryType(String query) {
        if (query == null || query.isBlank()) {
            return "unknown";
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "latest", "today", "recent", "current", "news", "2025", "2026", "最新", "今天", "新闻")) {
            return "freshness_sensitive";
        }
        if (containsAny(normalized, " vs ", "compare", "comparison", "difference", "区别", "对比", "比较")) {
            return "comparison";
        }
        if (containsAny(normalized, "how to", "tutorial", "guide", "steps", "如何", "怎么", "步骤")) {
            return "how_to";
        }
        if (containsAny(normalized, "docs", "documentation", "api", "reference", "schema", "文档", "接口")) {
            return "reference";
        }
        return "general";
    }

    private Map<String, Object> resultCounts(List<Map<String, Object>> searchResults,
                                             List<Map<String, Object>> pages,
                                             List<Map<String, Object>> evidenceChunks) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("searchResults", searchResults == null ? 0 : searchResults.size());
        counts.put("crawledPages", pages == null ? 0 : pages.size());
        counts.put("evidenceChunks", evidenceChunks == null ? 0 : evidenceChunks.size());
        return counts;
    }

    private Map<String, Integer> domainDistribution(List<Map<String, Object>> evidenceChunks) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        if (evidenceChunks == null) {
            return distribution;
        }
        for (Map<String, Object> chunk : evidenceChunks) {
            String domain = firstNonBlank(stringValue(chunk.get("domain")), domain(stringValue(chunk.get("source_url"))));
            if (domain == null || domain.isBlank()) {
                domain = "unknown";
            }
            distribution.merge(domain, 1, Integer::sum);
        }
        return distribution;
    }

    private Map<String, Object> scoreDistribution(List<Map<String, Object>> evidenceChunks) {
        List<Double> scores = new ArrayList<>();
        if (evidenceChunks != null) {
            for (Map<String, Object> chunk : evidenceChunks) {
                scores.add(numberValue(chunk.get("score"), 0.0d).doubleValue());
            }
        }
        scores.sort(Comparator.naturalOrder());
        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("count", scores.size());
        if (scores.isEmpty()) {
            distribution.put("min", 0.0d);
            distribution.put("max", 0.0d);
            distribution.put("avg", 0.0d);
            distribution.put("p50", 0.0d);
            distribution.put("buckets", Map.of());
            return distribution;
        }
        double sum = scores.stream().mapToDouble(Double::doubleValue).sum();
        distribution.put("min", round(scores.get(0)));
        distribution.put("max", round(scores.get(scores.size() - 1)));
        distribution.put("avg", round(sum / scores.size()));
        distribution.put("p50", round(scores.get(scores.size() / 2)));
        distribution.put("buckets", scoreBuckets(scores));
        return distribution;
    }

    private Map<String, Integer> scoreBuckets(List<Double> scores) {
        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("0.00-0.25", 0);
        buckets.put("0.25-0.50", 0);
        buckets.put("0.50-0.75", 0);
        buckets.put("0.75-1.00", 0);
        for (double score : scores) {
            if (score < 0.25d) {
                buckets.merge("0.00-0.25", 1, Integer::sum);
            } else if (score < 0.50d) {
                buckets.merge("0.25-0.50", 1, Integer::sum);
            } else if (score < 0.75d) {
                buckets.merge("0.50-0.75", 1, Integer::sum);
            } else {
                buckets.merge("0.75-1.00", 1, Integer::sum);
            }
        }
        return buckets;
    }

    private Map<String, Object> rerankDrift(List<Map<String, Object>> evidenceChunks) {
        int positive = 0;
        int negative = 0;
        int unchanged = 0;
        int maxGain = 0;
        int maxLoss = 0;
        double totalScoreDiff = 0.0d;
        int scoreDiffCount = 0;

        if (evidenceChunks != null) {
            for (Map<String, Object> chunk : evidenceChunks) {
                Map<String, Object> features = asMap(chunk.get("features"));
                int rankDelta = numberValue(features.get("rank_delta"), 0).intValue();
                if (rankDelta > 0) {
                    positive++;
                    maxGain = Math.max(maxGain, rankDelta);
                } else if (rankDelta < 0) {
                    negative++;
                    maxLoss = Math.min(maxLoss, rankDelta);
                } else {
                    unchanged++;
                }
                if (features.containsKey("score_diff")) {
                    totalScoreDiff += numberValue(features.get("score_diff"), 0.0d).doubleValue();
                    scoreDiffCount++;
                }
            }
        }

        Map<String, Object> drift = new LinkedHashMap<>();
        drift.put("positiveRankDeltaCount", positive);
        drift.put("negativeRankDeltaCount", negative);
        drift.put("unchangedRankCount", unchanged);
        drift.put("maxRankGain", maxGain);
        drift.put("maxRankLoss", maxLoss);
        drift.put("avgScoreDiff", round(scoreDiffCount == 0 ? 0.0d : totalScoreDiff / scoreDiffCount));
        return drift;
    }

    private Map<String, Object> cacheStats(List<Map<String, Object>> pages) {
        int total = pages == null ? 0 : pages.size();
        long hits = pages == null ? 0L : pages.stream()
            .filter(page -> Boolean.TRUE.equals(page.get("cacheHit")))
            .count();
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("pageCount", total);
        cache.put("hitCount", hits);
        cache.put("hitRate", total == 0 ? 0.0d : round(hits / (double) total));
        return cache;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String domain(String url) {
        try {
            return url == null || url.isBlank() ? null : URI.create(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Number numberValue(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
