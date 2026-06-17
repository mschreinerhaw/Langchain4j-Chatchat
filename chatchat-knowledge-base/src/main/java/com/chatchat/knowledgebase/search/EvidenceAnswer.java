package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceAnswer(
    String answer,
    List<AnswerCitation> citations,
    String confidence,
    List<String> missingInfo
) {
}
