package com.chatchat.mcpserver.web;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LexicalEvidenceVectorStore implements EvidenceVectorStore {

    private final Map<String, Map<String, StoredVector>> collections = new ConcurrentHashMap<>();

    /**
     * Stores vectors for evidence chunks.
     *
     * @param collectionId the collection id value
     * @param evidenceChunks the evidence chunks value
     */
    @Override
    public void upsert(String collectionId, List<Map<String, Object>> evidenceChunks) {
        if (collectionId == null || collectionId.isBlank() || evidenceChunks == null || evidenceChunks.isEmpty()) {
            return;
        }
        Map<String, StoredVector> vectors = collections.computeIfAbsent(collectionId, ignored -> new ConcurrentHashMap<>());
        for (Map<String, Object> chunk : evidenceChunks) {
            String chunkId = stringValue(chunk.get("chunk_id"));
            String text = firstNonBlank(stringValue(chunk.get("text")), stringValue(chunk.get("excerpt")));
            if (chunkId == null || chunkId.isBlank() || text == null || text.isBlank()) {
                continue;
            }
            vectors.put(chunkId, new StoredVector(chunkId, vectorize(text)));
        }
    }

    /**
     * Searches similar evidence chunks.
     *
     * @param collectionId the collection id value
     * @param query the query value
     * @param topK the top k value
     * @return the operation result
     */
    @Override
    public List<VectorHit> search(String collectionId, String query, int topK) {
        if (collectionId == null || collectionId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, StoredVector> vectors = collections.get(collectionId);
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }
        Map<String, Double> queryVector = vectorize(query);
        int limit = Math.max(1, topK);
        return vectors.values().stream()
            .map(vector -> new VectorHit(vector.chunkId(), cosine(queryVector, vector.weights())))
            .filter(hit -> hit.similarity() > 0.0d)
            .sorted(Comparator.comparingDouble(VectorHit::similarity).reversed().thenComparing(VectorHit::chunkId))
            .limit(limit)
            .toList();
    }

    private Map<String, Double> vectorize(String text) {
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String token : terms(text)) {
            counts.merge(token, 1.0d, Double::sum);
        }
        double norm = Math.sqrt(counts.values().stream().mapToDouble(value -> value * value).sum());
        if (norm <= 0.0d) {
            return Map.of();
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        counts.forEach((term, count) -> weights.put(term, count / norm));
        return weights;
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return 0.0d;
        }
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = left.size() <= right.size() ? right : left;
        double score = 0.0d;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            score += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0d);
        }
        return Math.max(0.0d, Math.min(1.0d, score));
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

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record StoredVector(
        String chunkId,
        Map<String, Double> weights
    ) {
    }
}
