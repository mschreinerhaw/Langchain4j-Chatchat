package com.chatchat.chat.skills.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** Queues slow external-skill compilation so HTTP upload requests can return immediately. */
@Slf4j
@Service
public class DomainSkillImportTaskService {
    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final DomainSkillImportTaskRepository repository;
    private final DomainSkillService skillService;
    private final TaskExecutor executor;
    private final Map<String, ImportWorkItem> workItems = new ConcurrentHashMap<>();

    public DomainSkillImportTaskService(DomainSkillImportTaskRepository repository,
                                        DomainSkillService skillService,
                                        @Qualifier("domainSkillImportExecutor") TaskExecutor executor) {
        this.repository = repository;
        this.skillService = skillService;
        this.executor = executor;
    }

    public ImportTaskView enqueueFile(String tenantId, String ownerId, byte[] content, String fileName,
                                      String requestedName, String category) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("Select a ZIP or Markdown file");
        if (content.length > DomainSkillService.MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Skill file must not exceed 5MB");
        return enqueue(tenantId, ownerId, "FILE", requestedName, category, fileName,
            new FileWorkItem(content.clone(), fileName, requestedName, category));
    }

    public ImportTaskView enqueueUrl(String tenantId, String ownerId, String sourceUrl, String requestedName,
                                     String category, DomainSkillRemoteImporter.DownloadRequest request) {
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("Skill URL is required");
        return enqueue(tenantId, ownerId, "URL", requestedName, category, sourceUrl,
            new UrlWorkItem(sourceUrl, requestedName, category,
                request == null ? DomainSkillRemoteImporter.DownloadRequest.defaults() : request));
    }

    public ImportTaskView status(String tenantId, String taskId) {
        return view(repository.findByIdAndTenantId(taskId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Skill import task does not exist or is outside the current tenant")));
    }

    private ImportTaskView enqueue(String tenantId, String ownerId, String importType, String requestedName,
                                   String category, String sourceReference, ImportWorkItem workItem) {
        if (category == null || category.isBlank()) throw new IllegalArgumentException("Skill category is required");
        DomainSkillImportTaskEntity task = new DomainSkillImportTaskEntity();
        task.setTenantId(tenantId);
        task.setOwnerId(ownerId);
        task.setImportType(importType);
        task.setStatus(QUEUED);
        task.setRequestedName(trim(requestedName, 200));
        task.setCategory(trim(category, 120));
        task.setSourceReference(trim(sourceReference, 2000));
        task = repository.save(task);
        workItems.put(task.getId(), workItem);
        try {
            String taskId = task.getId();
            executor.execute(() -> process(taskId));
        } catch (RejectedExecutionException ex) {
            workItems.remove(task.getId());
            fail(task, "Skill import queue is full; retry later");
        }
        return view(task);
    }

    private void process(String taskId) {
        ImportWorkItem workItem = workItems.get(taskId);
        DomainSkillImportTaskEntity task = repository.findById(taskId).orElse(null);
        if (task == null || workItem == null) return;
        try {
            task.setStatus(RUNNING);
            task.setStartedAt(Instant.now());
            task.setErrorMessage(null);
            repository.save(task);
            DomainSkillEntity skill;
            if (workItem instanceof FileWorkItem file) {
                skill = skillService.importFile(task.getTenantId(), task.getOwnerId(),
                    file.content(), file.fileName(), file.requestedName(), file.category());
            } else {
                UrlWorkItem url = (UrlWorkItem) workItem;
                skill = skillService.importUrl(task.getTenantId(), task.getOwnerId(),
                    url.sourceUrl(), url.requestedName(), url.category(), url.request());
            }
            task.setSkillId(skill.getId());
            task.setStatus(SUCCEEDED);
            task.setCompletedAt(Instant.now());
            repository.save(task);
        } catch (Exception ex) {
            log.warn("Domain skill import failed taskId={}: {}", taskId, ex.getMessage());
            fail(task, userMessage(ex));
        } finally {
            workItems.remove(taskId);
        }
    }

    private void fail(DomainSkillImportTaskEntity task, String message) {
        task.setStatus(FAILED);
        task.setErrorMessage(trim(message, 2000));
        task.setCompletedAt(Instant.now());
        repository.save(task);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failTasksInterruptedByRestart() {
        repository.findByStatusIn(List.of(QUEUED, RUNNING)).forEach(task ->
            fail(task, "Skill import was interrupted by a service restart; please submit it again"));
    }

    private String userMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
            ? "Skill import failed" : current.getMessage();
    }

    private String trim(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private ImportTaskView view(DomainSkillImportTaskEntity task) {
        return new ImportTaskView(task.getId(), task.getStatus(), task.getSkillId(), task.getErrorMessage(),
            task.getCreatedAt(), task.getStartedAt(), task.getCompletedAt());
    }

    private sealed interface ImportWorkItem permits FileWorkItem, UrlWorkItem { }
    private record FileWorkItem(byte[] content, String fileName, String requestedName,
                                String category) implements ImportWorkItem { }
    private record UrlWorkItem(String sourceUrl, String requestedName, String category,
                               DomainSkillRemoteImporter.DownloadRequest request) implements ImportWorkItem { }

    public record ImportTaskView(String taskId, String status, String skillId, String errorMessage,
                                 Instant createdAt, Instant startedAt, Instant completedAt) { }
}
