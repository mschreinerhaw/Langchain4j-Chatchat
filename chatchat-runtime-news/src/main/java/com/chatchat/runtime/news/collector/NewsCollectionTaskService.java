package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Short administrative requests submit or observe collection; harvesting runs on the collector executor. */
@Service
public class NewsCollectionTaskService {
    private final NewsCollectionService collection;
    private final Executor executor;
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    public NewsCollectionTaskService(NewsCollectionService collection,
                                    @Qualifier("newsCollectorExecutor") Executor executor) {
        this.collection = collection;
        this.executor = executor;
    }

    public synchronized Task submit(Long sourceId) {
        for (Task task : tasks.values()) {
            if (task.sourceId().equals(sourceId) && task.completedAt() == null) return task;
        }
        tasks.values().removeIf(task -> task.completedAt() != null
            && task.completedAt().isBefore(Instant.now().minusSeconds(86400)));
        if (tasks.size() >= 256) {
            var completed = tasks.values().stream().filter(t -> t.completedAt() != null).findFirst();
            completed.ifPresent(t -> tasks.remove(t.executionId()));
            if (tasks.size() >= 256) throw new IllegalStateException("采集任务队列已满，请稍后重试");
        }
        String id = UUID.randomUUID().toString();
        Task task = new Task(id, sourceId, "QUEUED", null, null, Instant.now(), null);
        tasks.put(id, task);
        try {
            executor.execute(() -> run(task));
        } catch (RuntimeException rejected) {
            tasks.remove(id);
            throw new IllegalStateException("采集任务提交失败，请稍后重试", rejected);
        }
        return tasks.get(id);
    }

    public synchronized Task get(Long sourceId, String executionId) {
        Task task = tasks.get(executionId);
        if (task == null || !task.sourceId().equals(sourceId))
            throw new IllegalArgumentException("采集任务不存在或已过期；服务重启后请重新提交");
        return task;
    }

    private void run(Task task) {
        update(new Task(task.executionId(), task.sourceId(), "RUNNING", null, null, task.createdAt(), null));
        try {
            NewsCollectResult result = collection.collect(task.sourceId(), task.executionId());
            update(new Task(task.executionId(), task.sourceId(), result.failedCount() > 0 ? "FAILED" : "COMPLETED",
                result, result.errorMessage(), task.createdAt(), Instant.now()));
        } catch (RuntimeException failure) {
            update(new Task(task.executionId(), task.sourceId(), "FAILED", null,
                failure.getMessage(), task.createdAt(), Instant.now()));
        }
    }

    private synchronized void update(Task task) { tasks.put(task.executionId(), task); }

    public record Task(String executionId, Long sourceId, String status, NewsCollectResult result,
                       String errorMessage, Instant createdAt, Instant completedAt) { }
}
