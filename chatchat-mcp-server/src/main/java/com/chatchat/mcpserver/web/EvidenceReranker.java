package com.chatchat.mcpserver.web;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EvidenceReranker {

    public static final String VERSION = "evidence_reranker_v1";
    private static final String RANKER_NAME = "bm25-lite+vector-cosine-v1";

    private final EvidenceVectorStore vectorStore;

    public EvidenceReranker(EvidenceVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Reranks evidence chunks using deterministic BM25-like and vector features.
     *
     * @param query the query value
     * @param evidenceChunks the evidence chunks value
     * @param topK the top k value
     * @return the operation result
     */
    public RerankResult rerank(String query, List<Map<String, Object>> evidenceChunks, int topK) {
        if (evidenceChunks == null || evidenceChunks.isEmpty()) {
            return new RerankResult(List.of(), Map.of(
                "mode", "lightweight",
                "ranker", "bm25-lite+vector-cosine-v1",
                "generatedAt", Instant.now().toEpochMilli(),
                "inputCount", 0,
                "rankedCount", 0
            ));
        }

        String collectionId = collectionId(query, evidenceChunks);
        vectorStore.upsert(collectionId, evidenceChunks);
        Map<String, Double> vectorScores = new HashMap<>();
        for (EvidenceVectorStore.VectorHit hit : vectorStore.search(collectionId, query, evidenceChunks.size())) {
            vectorScores.put(hit.chunkId(), hit.similarity());
        }

        Map<String, Double> bm25Scores = bm25Scores(query, evidenceChunks);
        Map<String, Integer> originalRanks = originalRanks(evidenceChunks);
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> chunk : evidenceChunks) {
            String chunkId = stringValue(chunk.get("chunk_id"));
            double ruleScore = numberValue(chunk.get("score"), 0.0d).doubleValue();
            double bm25 = bm25Scores.getOrDefault(chunkId, 0.0d);
            double cosine = vectorScores.getOrDefault(chunkId, 0.0d);
            double finalScore = round(ruleScore * 0.55d + bm25 * 0.25d + cosine * 0.20d);

            Map<String, Object> item = new LinkedHashMap<>(chunk);
            item.put("rerank_score", finalScore);
            item.put("score_diff", round(finalScore - ruleScore));
            item.put("ranker_version", VERSION);
            item.put("rerank_breakdown", Map.of(
                "rule_score", round(ruleScore),
                "bm25_lite", round(bm25),
                "vector_cosine", round(cosine)
            ));
            ranked.add(item);
        }

        int limit = Math.max(1, Math.min(topK <= 0 ? ranked.size() : topK, ranked.size()));
        List<Map<String, Object>> top = ranked.stream()
            .sorted(Comparator
                .comparingDouble((Map<String, Object> item) -> numberValue(item.get("rerank_score"), 0.0d).doubleValue()).reversed()
                .thenComparing(item -> stringValue(item.get("chunk_id"))))
            .limit(limit)
            .toList();
        int rerankRank = 1;
        for (Map<String, Object> item : top) {
            String chunkId = stringValue(item.get("chunk_id"));
            int originalRank = originalRanks.getOrDefault(chunkId, rerankRank);
            item.put("original_rank", originalRank);
            item.put("rerank_rank", rerankRank);
            item.put("rank_delta", originalRank - rerankRank);
            rerankRank++;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("version", VERSION);
        metadata.put("mode", "lightweight");
        metadata.put("ranker", RANKER_NAME);
        metadata.put("collectionId", collectionId);
        metadata.put("generatedAt", Instant.now().toEpochMilli());
        metadata.put("inputCount", evidenceChunks.size());
        metadata.put("rankedCount", top.size());
        metadata.put("weights", Map.of("rule_score", 0.55d, "bm25_lite", 0.25d, "vector_cosine", 0.20d));
        return new RerankResult(top, metadata);
    }

    private Map<String, Integer> originalRanks(List<Map<String, Object>> evidenceChunks) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int rank = 1;
        for (Map<String, Object> chunk : evidenceChunks) {
            String chunkId = stringValue(chunk.get("chunk_id"));
            if (chunkId != null && !chunkId.isBlank()) {
                ranks.putIfAbsent(chunkId, rank);
            }
            rank++;
        }
        return ranks;
    }

    private Map<String, Double> bm25Scores(String query, List<Map<String, Object>> chunks) {
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return Map.of();
        }

        List<DocumentTerms> documents = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalTerms = 0.0d;
        for (Map<String, Object> chunk : chunks) {
            String chunkId = stringValue(chunk.get("chunk_id"));
            List<String> terms = terms(firstNonBlank(stringValue(chunk.get("text")), stringValue(chunk.get("excerpt"))));
            totalTerms += terms.size();
            Map<String, Integer> termFrequency = new HashMap<>();
            terms.forEach(term -> termFrequency.merge(term, 1, Integer::sum));
            documents.add(new DocumentTerms(chunkId, termFrequency, Math.max(1, terms.size())));
            Set<String> unique = new HashSet<>(terms);
            queryTerms.stream().filter(unique::contains).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }

        int documentCount = Math.max(1, documents.size());
        double averageLength = Math.max(1.0d, totalTerms / documentCount);
        Map<String, Double> rawScores = new LinkedHashMap<>();
        double maxScore = 0.0d;
        for (DocumentTerms document : documents) {
            double score = 0.0d;
            for (String term : queryTerms) {
                int tf = document.termFrequency().getOrDefault(term, 0);
                if (tf <= 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0d + (documentCount - df + 0.5d) / (df + 0.5d));
                double denominator = tf + 1.2d * (1.0d - 0.75d + 0.75d * document.length() / averageLength);
                score += idf * (tf * 2.2d / denominator);
            }
            rawScores.put(document.chunkId(), score);
            maxScore = Math.max(maxScore, score);
        }
        if (maxScore <= 0.0d) {
            return Map.of();
        }
        double finalMaxScore = maxScore;
        Map<String, Double> normalized = new LinkedHashMap<>();
        rawScores.forEach((chunkId, score) -> normalized.put(chunkId, Math.max(0.0d, Math.min(1.0d, score / finalMaxScore))));
        return normalized;
    }

    private List<String> terms(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private String collectionId(String query, List<Map<String, Object>> evidenceChunks) {
        StringBuilder builder = new StringBuilder(query == null ? "" : query.trim());
        evidenceChunks.stream()
            .map(chunk -> stringValue(chunk.get("chunk_hash")))
            .filter(value -> value != null && !value.isBlank())
            .sorted()
            .forEach(builder::append);
        return "evidence-" + sha256(builder.toString()).substring(0, 24);
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

    public record RerankResult(
        List<Map<String, Object>> chunks,
        Map<String, Object> metadata
    ) {
    }

    private record DocumentTerms(
        String chunkId,
        Map<String, Integer> termFrequency,
        int length
    ) {
    }
}
