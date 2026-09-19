package com.chatchat.runtime.news.application.collection;

import com.chatchat.runtime.news.compliance.RobotsComplianceReport;
import com.chatchat.runtime.news.model.NewsCollectResult;

import java.util.concurrent.CompletableFuture;

/** Collection use cases shared by schedulers and the internal administration API. */
public interface NewsCollectionOperations {
    CompletableFuture<NewsCollectResult> collectAsync(Long sourceId);

    NewsCollectResult collect(Long sourceId, String executionId);

    RobotsComplianceReport checkRobots(Long sourceId);
}
