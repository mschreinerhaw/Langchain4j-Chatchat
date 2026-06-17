package com.chatchat.knowledgebase.search;

import java.util.List;

public record CitationBoundAnswer(
    String answer,
    List<DocumentEvidenceCitation> citations
) {
}
