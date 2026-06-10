package com.chatchat.knowledgebase.search;

import java.util.Map;

public record SearchScoreBreakdown(
    int baseTokenScore,
    int titleScore,
    int keywordScore,
    int tagScore,
    int companyScore,
    int industryScore,
    int contentScore,
    int sourceScore,
    int phraseScore,
    int coverageScore,
    double coverageRatio,
    Map<String, Integer> fieldScores
) {
}
