package com.chatchat.api.search;

import com.chatchat.api.controller.search.LegacyDocumentMcpTransferService;
import com.chatchat.knowledgebase.search.document.LibraryPage;
import com.chatchat.knowledgebase.search.document.LibraryDocumentItem;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    @Autowired(required = false)
    private LegacyDocumentMcpTransferService legacyDocumentMcpTransferService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new CategoryReindexThreadFactory());
    private final AtomicReference<CategoryReindexTaskStatus> currentTask = new AtomicReference<>(idle());
    private final Object taskLock = new Object();

    public CategoryReindexTaskStartResponse start(String category, SearchPermissionContext permissionContext) {
        return start(category, permissionContext, null);
    }

    public CategoryReindexTaskStartResponse start(String category, SearchPermissionContext permissionContext,
                                                  String username) {
        return start(category, permissionContext, username, List.of());
    }

    public CategoryReindexTaskStartResponse start(String category, SearchPermissionContext permissionContext,
                                                  String username, List<String> permissions) {
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
            log.info(
                "category_reindex_task_submitted taskId={} category={} tenantId={} userId={}",
                taskId,
                normalizedCategory,
                permissionContext == null ? SearchPermissionContext.DEFAULT_TENANT : permissionContext.tenantId(),
                permissionContext == null ? SearchPermissionContext.ANONYMOUS_USER : permissionContext.userId()
            );
            List<String> callerPermissions = permissions == null ? List.of() : List.copyOf(permissions);
            executor.submit(() -> runTask(taskId, normalizedCategory, permissionContext, username,
                callerPermissions));
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

    private void runTask(String taskId, String category, SearchPermissionContext permissionContext, String username,
                         List<String> permissions) {
        long startedAt = System.nanoTime();
        log.info("category_reindex_task_start taskId={} category={}", taskId, category);
        try {
            SearchService.ReindexSummary summary = legacyDocumentMcpTransferService != null
                && legacyDocumentMcpTransferService.enabled()
                ? transferCategoryToMcp(category, permissionContext, username, permissions)
                : searchService.reindexDocumentsByCategory(category, permissionContext);
            log.info(
                "category_reindex_task_complete taskId={} category={} scanned={} matched={} reindexed={} failed={} durationMs={}",
                taskId,
                category,
                summary.scannedDocuments(),
                summary.matchedDocuments(),
                summary.reindexedDocuments(),
                summary.failedDocuments(),
                elapsedMs(startedAt)
            );
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
            log.warn(
                "category_reindex_task_failed taskId={} category={} durationMs={} error={}",
                taskId,
                category,
                elapsedMs(startedAt),
                ex.getMessage(),
                ex
            );
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

    private SearchService.ReindexSummary transferCategoryToMcp(String category,
                                                               SearchPermissionContext permissionContext,
                                                               String username,
                                                               List<String> permissions) {
        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int scanned = 0;
        int page = 1;
        LibraryPage library;
        do {
            library = searchService.listLibrary(category, null, page, 100, permissionContext);
            for (LibraryDocumentItem item : library.documents()) {
                scanned++;
                try {
                    SearchDocument document = searchService.get(item.docId(), permissionContext)
                        .orElseThrow(() -> new IllegalStateException("document not found"));
                    legacyDocumentMcpTransferService.transfer(document,
                        searchService.getFileResource(item.docId(), permissionContext).orElse(null),
                        permissionContext, username, permissions);
                    succeeded.add(item.docId());
                } catch (Exception exception) {
                    failed.add(item.docId());
                    log.warn("category_document_mcp_transfer_failed category={} docId={} error={}",
                        category, item.docId(), exception.getMessage(), exception);
                }
            }
            page++;
        } while (page <= library.totalPages());
        return new SearchService.ReindexSummary(scanned, scanned, succeeded.size(), failed.size(),
            succeeded, failed);
    }

    private static CategoryReindexTaskStatus idle() {
        return new CategoryReindexTaskStatus("", "", "IDLE", false, null, null, 0, 0, 0, 0, "暂无分类索引重建任务");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
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
