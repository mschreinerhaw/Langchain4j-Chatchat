package com.chatchat.mcpserver.web;

import java.util.List;
import java.util.Map;

/**
 * Stores evidence chunk vectors and exposes similarity lookup.
 */
public interface EvidenceVectorStore {

    /**
     * Stores vectors for evidence chunks.
     *
     * @param collectionId the collection id value
     * @param evidenceChunks the evidence chunks value
     */
    void upsert(String collectionId, List<Map<String, Object>> evidenceChunks);

    /**
     * Searches similar evidence chunks.
     *
     * @param collectionId the collection id value
     * @param query the query value
     * @param topK the top k value
     * @return the operation result
     */
    List<VectorHit> search(String collectionId, String query, int topK);

    record VectorHit(
        String chunkId,
        double similarity
    ) {
    }
}
