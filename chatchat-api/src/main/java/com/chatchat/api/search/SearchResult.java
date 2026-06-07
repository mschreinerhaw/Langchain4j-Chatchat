package com.chatchat.api.search;

import java.util.List;

public record SearchResult(
    String docId,
    String title,
    String summary,
    String source,
    String date,
    String fileName,
    String documentType,
    String detailPath,
    List<String> tags,
    List<String> companies,
    List<String> industries,
    int score,
    List<String> matchedKeywords
) {
}
