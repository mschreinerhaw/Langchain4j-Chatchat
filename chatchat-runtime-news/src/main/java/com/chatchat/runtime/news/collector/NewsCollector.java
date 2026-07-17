package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;

public interface NewsCollector {
    boolean supports(NewsSourceType sourceType);

    NewsCollectResult collect(NewsSource source, NewsCollectContext context);
}
