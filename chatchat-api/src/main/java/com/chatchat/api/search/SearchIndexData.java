package com.chatchat.api.search;

import java.util.List;

record SearchIndexData(
    List<String> keywords,
    List<String> tags,
    List<String> companies,
    List<String> industries
) {
}
