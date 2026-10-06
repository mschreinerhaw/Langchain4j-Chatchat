package com.chatchat.knowledgebase.search.evidence.application;

import com.chatchat.knowledgebase.search.document.api.evidence.DocumentEvidenceChunk;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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
        List<DocumentEvidenceChunk> ranked = deduped.values().stream()
            .sorted(Comparator
                .comparingDouble(this::score)
                .reversed()
                .thenComparing(DocumentEvidenceChunk::fileName, Comparator.nullsLast(String::compareTo))
                .thenComparing(chunk -> chunk.chunkIndex() == null ? Integer.MAX_VALUE : chunk.chunkIndex()))
            .toList();
        // Preserve relevance within each document while reserving evidence for other
        // recalled documents. A long document must not consume the entire answer budget.
        Map<String, List<DocumentEvidenceChunk>> documents = new LinkedHashMap<>();
        for (DocumentEvidenceChunk chunk : ranked) {
            String document = chunk.fileId() == null || chunk.fileId().isBlank() ? key(chunk) : chunk.fileId();
            documents.computeIfAbsent(document, ignored -> new ArrayList<>()).add(chunk);
        }
        List<DocumentEvidenceChunk> selected = new ArrayList<>();
        for (int round = 0; selected.size() < Math.max(1, topK); round++) {
            int before = selected.size();
            for (List<DocumentEvidenceChunk> evidence : documents.values()) {
                if (round < evidence.size()) selected.add(evidence.get(round));
                if (selected.size() >= Math.max(1, topK)) break;
            }
            if (selected.size() == before) break;
        }
        return List.copyOf(selected);
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
