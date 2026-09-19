package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public interface NewsCollector {
    boolean supports(NewsSourceType sourceType);

    NewsCollectResult collect(NewsSource source, NewsCollectContext context);

    /** Stable extension id. Override when the id is stored in source configuration. */
    default String collectorId() {
        return getClass().getSimpleName();
    }

    /** Higher-priority collectors can intentionally replace a generic/default implementation. */
    default int priority() {
        return 0;
    }

    /** Metadata is derived for legacy collectors and may be overridden by external implementations. */
    default NewsCollectorDescriptor descriptor() {
        Set<NewsSourceType> supportedTypes = Arrays.stream(NewsSourceType.values())
            .filter(this::supports)
            .collect(Collectors.toUnmodifiableSet());
        return new NewsCollectorDescriptor(collectorId(), supportedTypes, priority());
    }
}
