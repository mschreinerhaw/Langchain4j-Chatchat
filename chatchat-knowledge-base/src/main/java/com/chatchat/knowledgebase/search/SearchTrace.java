package com.chatchat.knowledgebase.search;

import java.util.List;

public record SearchTrace(
    List<String> matchedKeywords,
    String intent,
    List<String> matchedRules,
    String rerankReason
) {
}
