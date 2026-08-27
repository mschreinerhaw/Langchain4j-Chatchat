package com.chatchat.knowledgebase.search.evidence;

import com.chatchat.knowledgebase.search.document.DocumentEvidenceCitation;

import java.util.List;

public record CitationBoundAnswer(
    String answer,
    List<DocumentEvidenceCitation> citations
) {
}
