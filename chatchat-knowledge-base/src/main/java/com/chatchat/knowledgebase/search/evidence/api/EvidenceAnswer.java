package com.chatchat.knowledgebase.search.evidence.api;

import java.util.List;

public record EvidenceAnswer(
    String answer,
    List<AnswerCitation> citations,
    String confidence,
    List<String> missingInfo
) {
}
