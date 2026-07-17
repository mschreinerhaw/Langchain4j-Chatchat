package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSearchQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledNewsDocumentStore implements NewsDocumentStore {
    @Override
    public void bulkIndex(List<NewsDocument> documents) {
        throw new IllegalStateException("News OpenSearch storage is disabled");
    }

    @Override
    public List<NewsDocument> search(NewsSearchQuery query) {
        throw new IllegalStateException("News OpenSearch storage is disabled");
    }
}
