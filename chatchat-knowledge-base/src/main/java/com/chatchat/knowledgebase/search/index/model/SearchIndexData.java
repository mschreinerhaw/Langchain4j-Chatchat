package com.chatchat.knowledgebase.search.index;

import java.util.List;

public record SearchIndexData(
    List<String> keywords,
    List<String> tags,
    List<String> companies,
    List<String> industries
) {
}
