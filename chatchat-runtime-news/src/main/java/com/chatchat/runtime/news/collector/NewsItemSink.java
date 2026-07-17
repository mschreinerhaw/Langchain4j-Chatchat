package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.RawNewsItem;

/** Collector-to-storage boundary; collectors never write OpenSearch directly. */
public interface NewsItemSink {
    NewsAcceptance accept(RawNewsItem item);
}
