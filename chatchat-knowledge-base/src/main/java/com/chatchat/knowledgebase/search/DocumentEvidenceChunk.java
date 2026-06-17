package com.chatchat.knowledgebase.search;

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
