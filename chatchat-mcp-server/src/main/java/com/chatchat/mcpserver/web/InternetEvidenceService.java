package com.chatchat.mcpserver.web;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class InternetEvidenceService {

    public static final String VERSION = "evidence_ranker_v1";

    private static final Set<String> HIGH_TRUST_DOMAINS = Set.of(
        "docs.", "developer.", "learn.microsoft.com", "github.com", "wikipedia.org",
        "openai.com", "spring.io", "oracle.com", "ietf.org", "w3.org", "apache.org"
    );

    private static final Set<String> LOW_SIGNAL_TERMS = Set.of(
        "subscribe", "newsletter", "cookie", "advertisement", "privacy", "login", "sign up"
    );

    /**
     * Builds ranked evidence chunks from search results and crawled pages.
     *
     * @param query the query value
     * @param searchResults the search results value
     * @param pages the pages value
     * @param limit the limit value
     * @return the operation result
     */
    public EvidenceResult generateEvidence(String query,
                                           List<Map<String, Object>> searchResults,
                                           List<Map<String, Object>> pages,
                                           int limit) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.addAll(pageCandidates(pages));
        if (candidates.isEmpty()) {
            candidates.addAll(snippetCandidates(searchResults));
        }

        Set<String> exactHashes = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        List<Map<String, Object>> evidenceChunks = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String text = stringValue(candidate.get("text"));
            if (text == null || text.isBlank()) {
                continue;
            }
            String chunkHash = sha256(normalizeForHash(text));
            String fingerprint = fingerprint(text);
            boolean duplicate = !exactHashes.add(chunkHash) || !fingerprints.add(fingerprint);
            Map<String, Object> scored = new LinkedHashMap<>(candidate);
            scored.put("chunk_id", "web-" + (evidenceChunks.size() + 1));
            scored.put("chunk_hash", chunkHash);
            scored.put("semantic_fingerprint", fingerprint);
            scored.put("duplicate", duplicate);
            EvidenceScore score = score(query, scored, duplicate);
            scored.put("score", score.score());
            scored.put("score_breakdown", score.breakdown());
            scored.put("confidence", score.score());
            evidenceChunks.add(scored);
        }

        int evidenceLimit = Math.max(1, Math.min(50, limit <= 0 ? 10 : limit));
        List<Map<String, Object>> rankedEvidence = evidenceChunks.stream()
            .sorted(Comparator
                .comparingDouble((Map<String, Object> item) -> numberValue(item.get("score"), 0.0d).doubleValue()).reversed()
                .thenComparing(item -> stringValue(item.get("chunk_id"))))
            .limit(evidenceLimit)
            .toList();
        List<Map<String, Object>> citations = rankedEvidence.stream()
            .map(this::citationFor)
            .toList();

        Map<String, Object> ranker = new LinkedHashMap<>();
        ranker.put("version", VERSION);
        ranker.put("name", "domain-content-structure-lexical-v1");
        ranker.put("dedup", "exact_hash+lexical_fingerprint");
        ranker.put("generatedAt", Instant.now().toEpochMilli());
        ranker.put("candidateCount", candidates.size());
        ranker.put("rankedCount", rankedEvidence.size());
        ranker.put("duplicateCount", evidenceChunks.stream().filter(item -> Boolean.TRUE.equals(item.get("duplicate"))).count());

        return new EvidenceResult(rankedEvidence, citations, ranker);
    }

    private List<Map<String, Object>> pageCandidates(List<Map<String, Object>> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            String url = stringValue(page.get("url"));
            String title = stringValue(page.get("title"));
            List<String> chunks = stringList(page.get("chunks"));
            int index = 1;
            for (String chunk : chunks) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("source_type", "crawled_page");
                candidate.put("url", url);
                candidate.put("title", title);
                candidate.put("text", chunk);
                candidate.put("excerpt", excerpt(chunk));
                candidate.put("snippet", excerpt(chunk));
                candidate.put("chunk_index", index++);
                candidate.put("contentLength", numberValue(page.get("contentLength"), chunk.length()));
                candidate.put("cacheHit", page.get("cacheHit"));
                candidate.put("timestamp", page.get("timestamp"));
                candidate.put("rendered", page.get("rendered"));
                candidate.put("contentHash", page.get("contentHash"));
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private List<Map<String, Object>> snippetCandidates(List<Map<String, Object>> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> result : searchResults) {
            String snippet = firstNonBlank(stringValue(result.get("snippet")), stringValue(result.get("description")));
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("source_type", "search_snippet");
            candidate.put("url", stringValue(result.get("url")));
            candidate.put("title", stringValue(result.get("title")));
            candidate.put("text", snippet);
            candidate.put("excerpt", excerpt(snippet));
            candidate.put("snippet", excerpt(snippet));
            candidate.put("searchRank", numberValue(result.get("rank"), 100));
            candidate.put("searchScore", numberValue(result.get("score"), 0));
            candidate.put("contentLength", snippet.length());
            candidates.add(candidate);
        }
        return candidates;
    }

    private EvidenceScore score(String query, Map<String, Object> item, boolean duplicate) {
        String url = stringValue(item.get("url"));
        String text = stringValue(item.get("text"));
        String title = stringValue(item.get("title"));
        String host = host(url);

        double domainWeight = trustedDomain(host) ? 0.25d : 0.08d;
        double freshnessScore = freshnessScore(item.get("timestamp"));
        double contentLengthScore = contentLengthScore(text);
        double structureScore = structureScore(item);
        double lexicalScore = lexicalScore(query, title + " " + text);
        double adNoisePenalty = adNoisePenalty(text);
        double duplicationPenalty = duplicate ? 0.35d : 0.0d;

        double score = clamp(domainWeight + freshnessScore + contentLengthScore + structureScore + lexicalScore
            - adNoisePenalty - duplicationPenalty);

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("domain_weight", round(domainWeight));
        breakdown.put("freshness_score", round(freshnessScore));
        breakdown.put("content_length_score", round(contentLengthScore));
        breakdown.put("structure_score", round(structureScore));
        breakdown.put("lexical_score", round(lexicalScore));
        breakdown.put("ad_noise_penalty", round(adNoisePenalty));
        breakdown.put("duplication_penalty", round(duplicationPenalty));
        return new EvidenceScore(round(score), breakdown);
    }

    private Map<String, Object> citationFor(Map<String, Object> evidence) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("chunk_id", evidence.get("chunk_id"));
        citation.put("url", evidence.get("url"));
        citation.put("title", evidence.get("title"));
        citation.put("confidence", evidence.get("confidence"));
        citation.put("chunk_hash", evidence.get("chunk_hash"));
        return citation;
    }

    private boolean trustedDomain(String host) {
        return host != null && HIGH_TRUST_DOMAINS.stream().anyMatch(host::contains);
    }

    private double freshnessScore(Object timestampValue) {
        long timestamp = longValue(timestampValue);
        if (timestamp <= 0L) {
            return 0.03d;
        }
        long ageDays = Math.max(0L, (System.currentTimeMillis() - timestamp) / 86_400_000L);
        if (ageDays <= 7L) {
            return 0.12d;
        }
        if (ageDays <= 30L) {
            return 0.08d;
        }
        return 0.04d;
    }

    private double contentLengthScore(String text) {
        int length = text == null ? 0 : text.length();
        if (length >= 800) {
            return 0.18d;
        }
        if (length >= 300) {
            return 0.14d;
        }
        if (length >= 120) {
            return 0.08d;
        }
        return 0.02d;
    }

    private double structureScore(Map<String, Object> item) {
        double score = 0.02d;
        if ("crawled_page".equals(item.get("source_type"))) {
            score += 0.08d;
        }
        if (numberValue(item.get("chunk_index"), 0).intValue() == 1) {
            score += 0.03d;
        }
        return score;
    }

    private double lexicalScore(String query, String text) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || text == null || text.isBlank()) {
            return 0.0d;
        }
        Set<String> textTerms = terms(text);
        long matched = queryTerms.stream().filter(textTerms::contains).count();
        return Math.min(0.25d, (matched / (double) queryTerms.size()) * 0.25d);
    }

    private double adNoisePenalty(String text) {
        if (text == null || text.isBlank()) {
            return 0.12d;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        long hits = LOW_SIGNAL_TERMS.stream().filter(normalized::contains).count();
        return Math.min(0.18d, hits * 0.04d);
    }

    private Set<String> terms(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private String fingerprint(String text) {
        List<String> tokens = new ArrayList<>(terms(text));
        tokens.sort(String::compareTo);
        int limit = Math.min(24, tokens.size());
        return sha256(String.join(" ", tokens.subList(0, limit)));
    }

    private String normalizeForHash(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private String host(String url) {
        try {
            return url == null ? null : URI.create(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(this::stringValue)
            .filter(text -> text != null && !text.isBlank())
            .toList();
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

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record EvidenceResult(
        List<Map<String, Object>> evidenceChunks,
        List<Map<String, Object>> citations,
        Map<String, Object> ranker
    ) {
    }

    private record EvidenceScore(double score, Map<String, Object> breakdown) {
    }
}
