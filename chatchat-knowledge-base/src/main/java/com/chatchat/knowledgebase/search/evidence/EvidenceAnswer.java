package com.chatchat.knowledgebase.search.evidence;

import java.util.List;

public record EvidenceAnswer(
    String answer,
    List<AnswerCitation> citations,
    String confidence,
    List<String> missingInfo
) {
}
