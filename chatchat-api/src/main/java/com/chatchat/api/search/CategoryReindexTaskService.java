package com.chatchat.api.search;

import com.chatchat.knowledgebase.search.SearchPermissionContext;
import com.chatchat.knowledgebase.search.SearchService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryReindexTaskService {

    private final SearchService searchService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new CategoryReindexThreadFactory());
    private final AtomicReference<CategoryReindexTaskStatus> currentTask = new AtomicReference<>(idle());
    private final Object taskLock = new Object();

    public CategoryReindexTaskStartResponse start(String category, SearchPermissionContext permissionContext) {
        synchronized (taskLock) {
            CategoryReindexTaskStatus current = currentTask.get();
            if (current.running()) {
                return new CategoryReindexTaskStartResponse(
                    false,
                    current.withMessage("当前有分类索引重建任务运行中，请等待完成")
                );
            }

            String taskId = UUID.randomUUID().toString();
            String normalizedCategory = hasText(category) ? category.trim() : "all";
            CategoryReindexTaskStatus running = new CategoryReindexTaskStatus(
                taskId,
                normalizedCategory,
                "RUNNING",
                true,
                Instant.now().toEpochMilli(),
                null,
                0,
                0,
                0,
                0,
                "分类索引重建任务已开始"
            );
            currentTask.set(running);
            executor.submit(() -> runTask(taskId, normalizedCategory, permissionContext));
            return new CategoryReindexTaskStartResponse(true, running);
        }
    }

    public CategoryReindexTaskStatus status() {
        return currentTask.get();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void runTask(String taskId, String category, SearchPermissionContext permissionContext) {
        try {
            SearchService.ReindexSummary summary = searchService.reindexDocumentsByCategory(category, permissionContext);
            currentTask.set(new CategoryReindexTaskStatus(
                taskId,
                category,
                "COMPLETED",
                false,
                currentTask.get().startedAt(),
                Instant.now().toEpochMilli(),
                summary.scannedDocuments(),
                summary.matchedDocuments(),
                summary.reindexedDocuments(),
                summary.failedDocuments(),
                "分类索引重建任务已完成"
            ));
        } catch (Exception ex) {
            log.warn("Category reindex task {} failed for category {}: {}", taskId, category, ex.getMessage(), ex);
            currentTask.set(new CategoryReindexTaskStatus(
                taskId,
                category,
                "FAILED",
                false,
                currentTask.get().startedAt(),
                Instant.now().toEpochMilli(),
                0,
                0,
                0,
                0,
                "分类索引重建任务失败：" + ex.getMessage()
            ));
        }
    }

    private static CategoryReindexTaskStatus idle() {
        return new CategoryReindexTaskStatus("", "", "IDLE", false, null, null, 0, 0, 0, 0, "暂无分类索引重建任务");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record CategoryReindexTaskStartResponse(
        boolean accepted,
        CategoryReindexTaskStatus task
    ) {
    }

    public record CategoryReindexTaskStatus(
        String taskId,
        String category,
        String status,
        boolean running,
        Long startedAt,
        Long finishedAt,
        int scannedDocuments,
        int matchedDocuments,
        int reindexedDocuments,
        int failedDocuments,
        String message
    ) {
        public CategoryReindexTaskStatus withMessage(String nextMessage) {
            return new CategoryReindexTaskStatus(
                taskId,
                category,
                status,
                running,
                startedAt,
                finishedAt,
                scannedDocuments,
                matchedDocuments,
                reindexedDocuments,
                failedDocuments,
                nextMessage
            );
        }
    }

    private static final class CategoryReindexThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "category-reindex-task");
            thread.setDaemon(true);
            return thread;
        }
    }
}
