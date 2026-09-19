package com.chatchat.runtime.news.application.collection;

import com.chatchat.runtime.news.compliance.RobotsComplianceReport;
import com.chatchat.runtime.news.model.NewsCollectResult;

/** Collection use cases shared by schedulers and the internal administration API. */
public interface NewsCollectionOperations {
    NewsCollectResult collect(Long sourceId, String executionId);

    RobotsComplianceReport checkRobots(Long sourceId);
}
