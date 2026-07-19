package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;

import java.util.List;
import java.util.Optional;

public interface NewsDocumentStore {
    void bulkIndex(List<NewsDocument> documents) throws Exception;

    default Optional<NewsDocument> findById(String documentId) throws Exception {
        throw new UnsupportedOperationException("News document lookup is not supported by this store");
    }

    List<NewsDocument> search(NewsSearchQuery query) throws Exception;
}
