package com.chatchat.mcpserver.web;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvidenceContractService {

    public static final String SCHEMA_VERSION = "evidence_contract_v1";

    /**
     * Normalizes reranked evidence chunks to the stable public contract.
     *
     * @param rerankedChunks the reranked chunks value
     * @return the operation result
     */
    public ContractResult normalize(List<Map<String, Object>> rerankedChunks) {
        if (rerankedChunks == null || rerankedChunks.isEmpty()) {
            return new ContractResult(List.of(), List.of(), metadata(0));
        }

        List<Map<String, Object>> chunks = new ArrayList<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> source : rerankedChunks) {
            String chunkId = firstNonBlank(stringValue(source.get("chunk_id")), "web-" + rank);
            String sourceUrl = firstNonBlank(stringValue(source.get("source_url")), stringValue(source.get("url")));
            String content = firstNonBlank(stringValue(source.get("content")), firstNonBlank(
                stringValue(source.get("text")),
                stringValue(source.get("excerpt"))
            ));
            double ruleScore = numberValue(source.get("score"), 0.0d).doubleValue();
            double rerankScore = numberValue(source.get("rerank_score"), ruleScore).doubleValue();
            Map<String, Object> rerankBreakdown = asMap(source.get("rerank_breakdown"));

            Map<String, Object> features = new LinkedHashMap<>();
            features.put("rule_score", round(numberValue(rerankBreakdown.get("rule_score"), ruleScore).doubleValue()));
            features.put("bm25", round(numberValue(rerankBreakdown.get("bm25_lite"), 0.0d).doubleValue()));
            features.put("vector", round(numberValue(rerankBreakdown.get("vector_cosine"), 0.0d).doubleValue()));
            features.put("score_diff", round(rerankScore - ruleScore));
            features.put("rank_delta", numberValue(source.get("rank_delta"), 0).intValue());

            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("chunk_id", chunkId);
            citation.put("url", sourceUrl);
            citation.put("title", source.get("title"));
            citation.put("confidence", round(rerankScore));
            citation.put("chunk_hash", source.get("chunk_hash"));

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("schema_version", SCHEMA_VERSION);
            chunk.put("chunk_id", chunkId);
            chunk.put("content", content == null ? "" : content);
            chunk.put("score", round(rerankScore));
            chunk.put("source_url", sourceUrl);
            chunk.put("domain", domain(sourceUrl));
            chunk.put("timestamp", firstNonBlank(stringValue(source.get("timestamp")), String.valueOf(Instant.now().toEpochMilli())));
            chunk.put("features", features);
            chunk.put("citations", List.of(citation));

            // Compatibility aliases used by existing Agent citation extraction.
            chunk.put("url", sourceUrl);
            chunk.put("title", source.get("title"));
            chunk.put("snippet", firstNonBlank(stringValue(source.get("snippet")), firstNonBlank(
                stringValue(source.get("excerpt")),
                content == null ? "" : excerpt(content)
            )));
            chunk.put("excerpt", firstNonBlank(stringValue(source.get("excerpt")), content == null ? "" : excerpt(content)));
            chunk.put("chunk_hash", source.get("chunk_hash"));
            chunk.put("rerank_rank", rank++);

            chunks.add(chunk);
            citations.add(citation);
        }
        return new ContractResult(chunks, citations, metadata(chunks.size()));
    }

    private Map<String, Object> metadata(int chunkCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", SCHEMA_VERSION);
        metadata.put("generatedAt", Instant.now().toEpochMilli());
        metadata.put("chunkCount", chunkCount);
        metadata.put("requiredFields", List.of("chunk_id", "content", "score", "source_url", "domain", "timestamp", "features", "citations"));
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String domain(String url) {
        try {
            return url == null || url.isBlank() ? "" : URI.create(url).getHost();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String excerpt(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
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

    public record ContractResult(
        List<Map<String, Object>> chunks,
        List<Map<String, Object>> citations,
        Map<String, Object> metadata
    ) {
    }
}
