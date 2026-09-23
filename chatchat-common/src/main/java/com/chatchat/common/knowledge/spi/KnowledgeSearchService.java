package com.chatchat.common.knowledge.spi;

import com.chatchat.common.knowledge.search.KnowledgeDocument;
import com.chatchat.common.knowledge.search.SearchResult;

/** Provider-neutral knowledge recall interface. */
public interface KnowledgeSearchService<Q, T extends KnowledgeDocument> {
    SearchResult<T> search(Q request);
}
