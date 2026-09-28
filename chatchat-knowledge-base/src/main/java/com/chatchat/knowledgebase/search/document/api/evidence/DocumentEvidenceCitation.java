package com.chatchat.knowledgebase.search.document.api.evidence;

import com.chatchat.knowledgebase.search.evidence.api.Citation;

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
