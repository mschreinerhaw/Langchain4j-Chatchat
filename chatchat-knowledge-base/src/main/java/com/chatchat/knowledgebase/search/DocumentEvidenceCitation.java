package com.chatchat.knowledgebase.search;

public record DocumentEvidenceCitation(
    String refId,
    String fileId,
    String chunkId,
    String fileName,
    String section,
    Integer chunkIndex,
    String citation
) {
}
