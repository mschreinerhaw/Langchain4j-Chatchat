package com.chatchat.common.retrieval.query;

import java.util.List;

/** Read-only vocabulary adapter. Return only phrases already present in the user's source. */
public interface QueryTermProtector {
    List<String> protectedPhrases(String source, int maximumPhrases);
    default List<String> keywordCandidates(String source, int maximumKeywords) { return List.of(); }
}
