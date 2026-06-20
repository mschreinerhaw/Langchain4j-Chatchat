package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchHit(
    String docId,
    String title,
    String fileName,
    String documentType,
    Double score,
    List<String> tags
) {
}
