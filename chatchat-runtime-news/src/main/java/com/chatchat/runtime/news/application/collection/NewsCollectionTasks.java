package com.chatchat.runtime.news.application.collection;

import com.chatchat.runtime.news.model.NewsCollectResult;

import java.time.Instant;

/** Asynchronous collection-task submission and observation boundary. */
public interface NewsCollectionTasks {
    Task submit(Long sourceId);

    Task get(Long sourceId, String executionId);

    record Task(String executionId, Long sourceId, String status, NewsCollectResult result,
                String errorMessage, Instant createdAt, Instant completedAt) { }
}
