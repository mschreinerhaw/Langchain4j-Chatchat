package com.chatchat.knowledgebase.search.evidence;

import com.chatchat.knowledgebase.search.document.api.evidence.DocumentEvidenceCitation;

import java.util.List;

public record CitationBoundAnswer(
    String answer,
    List<DocumentEvidenceCitation> citations
) {
}
