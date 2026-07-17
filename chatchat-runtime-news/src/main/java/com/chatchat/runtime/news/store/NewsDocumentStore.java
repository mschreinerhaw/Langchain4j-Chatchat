package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;

import java.util.List;

public interface NewsDocumentStore {
    void bulkIndex(List<NewsDocument> documents) throws Exception;

    List<NewsDocument> search(NewsSearchQuery query) throws Exception;
}
