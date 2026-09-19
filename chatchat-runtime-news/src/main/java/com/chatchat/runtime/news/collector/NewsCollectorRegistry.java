package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsSource;

import java.util.Collection;
import java.util.Optional;

/** Discovery boundary for independently implemented news collectors. */
public interface NewsCollectorRegistry {
    NewsCollector require(NewsSource source);

    Optional<NewsCollector> findById(String collectorId);

    Collection<NewsCollectorDescriptor> descriptors();
}
