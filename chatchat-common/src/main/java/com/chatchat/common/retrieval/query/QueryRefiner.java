package com.chatchat.common.retrieval.query;

import java.util.List;

/** Optional extraction only. No tools, connections, authorization or exploration decisions. */
public interface QueryRefiner {
    List<String> refine(String originalQuery, List<String> protectedPhrases, int maximumKeywords,
                        long remainingMillis);
}
