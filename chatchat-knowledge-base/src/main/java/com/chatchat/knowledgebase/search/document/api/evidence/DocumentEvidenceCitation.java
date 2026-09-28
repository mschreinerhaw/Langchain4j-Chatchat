package com.chatchat.knowledgebase.search.document;

import com.chatchat.knowledgebase.search.evidence.Citation;

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
