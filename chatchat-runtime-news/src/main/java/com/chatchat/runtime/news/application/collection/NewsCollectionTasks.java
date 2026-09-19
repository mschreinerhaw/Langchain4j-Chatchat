package com.chatchat.runtime.news.application.collection;

import com.chatchat.runtime.news.model.NewsCollectResult;

import java.time.Instant;
import java.util.List;

/** Asynchronous collection-task submission and observation boundary. */
public interface NewsCollectionTasks {
    Task submit(Long sourceId);

    Task submitScheduled(Long sourceId);

    /** Executes a durable scheduler-owned task on the caller thread (for Temporal Activities). */
    Task executeScheduled(Long sourceId, String executionId);

    Task get(Long sourceId, String executionId);

    List<Task> recent(int limit);

    record Task(String executionId, Long sourceId, String trigger, String status, NewsCollectResult result,
                String errorMessage, Instant createdAt, Instant startedAt, Instant completedAt) { }
}
