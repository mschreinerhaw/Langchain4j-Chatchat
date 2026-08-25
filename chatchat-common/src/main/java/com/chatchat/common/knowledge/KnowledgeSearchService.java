package com.chatchat.common.knowledge;

/** Provider-neutral knowledge recall interface. */
public interface KnowledgeSearchService<Q, T extends KnowledgeDocument> {
    SearchResult<T> search(Q request);
}
