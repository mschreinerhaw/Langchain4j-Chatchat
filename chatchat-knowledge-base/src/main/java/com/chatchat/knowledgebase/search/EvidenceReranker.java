package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvidenceReranker {

    public List<DocumentEvidenceChunk> rerank(String query, List<DocumentEvidenceChunk> chunks, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, DocumentEvidenceChunk> deduped = new LinkedHashMap<>();
        for (DocumentEvidenceChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String key = key(chunk);
            DocumentEvidenceChunk current = deduped.get(key);
            if (current == null || score(chunk) > score(current)) {
                deduped.put(key, chunk);
            }
        }
        return deduped.values().stream()
            .sorted(Comparator
                .comparingDouble(this::score)
                .reversed()
                .thenComparing(DocumentEvidenceChunk::fileName, Comparator.nullsLast(String::compareTo))
                .thenComparing(chunk -> chunk.chunkIndex() == null ? Integer.MAX_VALUE : chunk.chunkIndex()))
            .limit(Math.max(1, topK))
            .toList();
    }

    private String key(DocumentEvidenceChunk chunk) {
        if (chunk.refId() != null && !chunk.refId().isBlank()) {
            return chunk.refId();
        }
        return String.join(":",
            chunk.fileId() == null ? "" : chunk.fileId(),
            chunk.chunkId() == null ? "" : chunk.chunkId(),
            chunk.chunkIndex() == null ? "" : String.valueOf(chunk.chunkIndex())
        );
    }

    private double score(DocumentEvidenceChunk chunk) {
        return chunk == null || chunk.score() == null ? 0.0D : chunk.score();
    }
}
