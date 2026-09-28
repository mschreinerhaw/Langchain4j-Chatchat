package com.chatchat.knowledgebase.search.document.api.evidence;

import com.chatchat.knowledgebase.search.evidence.api.Citation;
import com.chatchat.knowledgebase.search.model.SearchTrace;
import com.chatchat.knowledgebase.search.query.ChunkType;

import java.util.List;

public record DocumentEvidenceChunk(
    String refId,
    String chunkId,
    String fileId,
    String fileName,
    String section,
    Integer chunkIndex,
    String chunkType,
    Double score,
    String content,
    List<String> highlights,
    Citation citation,
    SearchTrace trace,
    String tenantId,
    String userId,
    String visibility,
    List<String> permissionRoles
) {
}
