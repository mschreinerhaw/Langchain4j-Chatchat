package com.chatchat.runtime.news.collector.service;

import com.chatchat.runtime.news.application.collection.NewsCollectionOperations;
import com.chatchat.runtime.news.application.collection.NewsCollectionTasks;
import com.chatchat.runtime.news.model.NewsCollectResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** One observable, source-deduplicated queue for manual and scheduled harvesting. */
@Slf4j
@Service
public class NewsCollectionTaskService implements NewsCollectionTasks {
    private final NewsCollectionOperations collection;
    private final Executor executor;
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    public NewsCollectionTaskService(NewsCollectionOperations collection,
                                     @Qualifier("newsCollectorExecutor") Executor executor) {
        this.collection = collection;
        this.executor = executor;
    }

    @Override
    public synchronized Task submit(Long sourceId) {
        return submit(sourceId, "MANUAL");
    }

    @Override
    public synchronized Task submitScheduled(Long sourceId) {
        return submit(sourceId, "SCHEDULED");
    }

    @Override
    public Task executeScheduled(Long sourceId, String executionId) {
        if (sourceId == null || executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("sourceId and executionId are required");
        }
        Task task;
        synchronized (this) {
            Task previousAttempt = tasks.get(executionId);
            if (previousAttempt != null && !"FAILED".equals(previousAttempt.status())) {
                return previousAttempt;
            }
            for (Task active : tasks.values()) {
                if (active.sourceId().equals(sourceId) && active.completedAt() == null) {
                    log.debug("news_collection_task_deduplicated sourceId={} executionId={} trigger=SCHEDULED existingTrigger={}",
                        sourceId, active.executionId(), active.trigger());
                    return active;
                }
            }
            if (previousAttempt == null) {
                pruneCompletedTasks();
                task = new Task(executionId, sourceId, "SCHEDULED", "QUEUED", null, null,
                    Instant.now(), null, null);
            } else {
                task = new Task(executionId, sourceId, "SCHEDULED", "QUEUED", null, null,
                    previousAttempt.createdAt(), null, null);
            }
            tasks.put(executionId, task);
        }
        log.info("news_collection_task_started_by_temporal sourceId={} executionId={}", sourceId, executionId);
        return run(task, true);
    }

    private Task submit(Long sourceId, String trigger) {
        for (Task task : tasks.values()) {
            if (task.sourceId().equals(sourceId) && task.completedAt() == null) {
                log.debug("news_collection_task_deduplicated sourceId={} executionId={} trigger={} existingTrigger={}",
                    sourceId, task.executionId(), trigger, task.trigger());
                return task;
            }
        }
        pruneCompletedTasks();
        String id = UUID.randomUUID().toString();
        Task task = new Task(id, sourceId, trigger, "QUEUED", null, null, Instant.now(), null, null);
        tasks.put(id, task);
        try {
            executor.execute(() -> run(task, false));
        } catch (RuntimeException rejected) {
            tasks.remove(id);
            throw new IllegalStateException("News collection task submission was rejected", rejected);
        }
        log.info("news_collection_task_queued sourceId={} executionId={} trigger={}", sourceId, id, trigger);
        return tasks.get(id);
    }

    @Override
    public synchronized Task get(Long sourceId, String executionId) {
        Task task = tasks.get(executionId);
        if (task == null || !task.sourceId().equals(sourceId)) {
            throw new IllegalArgumentException("News collection task does not exist or has expired");
        }
        return task;
    }

    @Override
    public synchronized List<Task> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 256));
        return tasks.values().stream().sorted(Comparator.comparing(Task::createdAt).reversed())
            .limit(safeLimit).toList();
    }

    private Task run(Task task, boolean propagateFailure) {
        Instant startedAt = Instant.now();
        boolean resultRecorded = false;
        update(new Task(task.executionId(), task.sourceId(), task.trigger(), "RUNNING", null, null,
            task.createdAt(), startedAt, null));
        try {
            NewsCollectResult result = collection.collect(task.sourceId(), task.executionId());
            Task completed = new Task(task.executionId(), task.sourceId(), task.trigger(),
                result.failedCount() > 0 ? "FAILED" : "COMPLETED", result, result.errorMessage(),
                task.createdAt(), startedAt, Instant.now());
            update(completed);
            resultRecorded = true;
            if (propagateFailure && result.failedCount() > 0) {
                throw new IllegalStateException(result.errorMessage() == null
                    ? "News collection reported " + result.failedCount() + " failures"
                    : result.errorMessage());
            }
            return completed;
        } catch (RuntimeException failure) {
            if (!resultRecorded) {
                update(new Task(task.executionId(), task.sourceId(), task.trigger(), "FAILED", null,
                    failure.getMessage(), task.createdAt(), startedAt, Instant.now()));
            }
            if (propagateFailure) throw failure;
            return current(task.executionId());
        }
    }

    private synchronized Task current(String executionId) {
        return tasks.get(executionId);
    }

    private void pruneCompletedTasks() {
        tasks.values().removeIf(task -> task.completedAt() != null
            && task.completedAt().isBefore(Instant.now().minusSeconds(86400)));
        if (tasks.size() >= 256) {
            var completed = tasks.values().stream().filter(task -> task.completedAt() != null).findFirst();
            completed.ifPresent(task -> tasks.remove(task.executionId()));
            if (tasks.size() >= 256) throw new IllegalStateException("News collection task queue is full");
        }
    }

    private synchronized void update(Task task) {
        tasks.put(task.executionId(), task);
    }
}
