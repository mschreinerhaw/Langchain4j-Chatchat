package com.chatchat.knowledgebase.search.evidence.api;

public record AnswerCitation(
    String refId,
    String fileId,
    String fileName,
    String section,
    Integer chunkIndex
) {
}
