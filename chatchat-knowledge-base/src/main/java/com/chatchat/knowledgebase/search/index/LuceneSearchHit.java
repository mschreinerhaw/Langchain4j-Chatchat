package com.chatchat.knowledgebase.search.index;

import com.chatchat.knowledgebase.search.query.ChunkType;

public record LuceneSearchHit(
    String docId,
    String fileName,
    String section,
    String chunkType,
    String chunkId,
    int chunkIndex,
    String chunkText,
    float positionRatio,
    float score,
    String tenantId,
    String userId,
    String visibility,
    java.util.List<String> permissionRoles,
    String sectionId,
    Integer sourceVersion
) {
    public LuceneSearchHit(String docId, String fileName, String section, String chunkType,
                           String chunkId, int chunkIndex, String chunkText, float positionRatio,
                           float score, String tenantId, String userId, String visibility,
                           java.util.List<String> permissionRoles) {
        this(docId, fileName, section, chunkType, chunkId, chunkIndex, chunkText,
            positionRatio, score, tenantId, userId, visibility, permissionRoles, null, null);
    }
}
