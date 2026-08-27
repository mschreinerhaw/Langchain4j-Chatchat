package com.chatchat.knowledgebase.search.evidence;

public record AnswerCitation(
    String refId,
    String fileId,
    String fileName,
    String section,
    Integer chunkIndex
) {
}
