package com.chatchat.chat.task;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskFeedbackQueueService {

    private static final int MAX_IDLE_POLLS = 3;

    private final AgentTaskService taskService;
    private final AgentTaskProperties properties;

    @Qualifier("agentTaskExecutor")
    private final ThreadPoolTaskExecutor taskExecutor;

    private final Map<String, BlockingQueue<FeedbackCommand>> tenantQueues = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> workerStates = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    /**
     * Queues user feedback and returns an optimistic task view for fast UI updates.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param request the feedback request value
     * @return the optimistic task response
     */
    public AgentTaskResponse enqueueFeedback(String tenantId, String taskId, AgentTaskFeedbackRequest request) {
        String normalizedTenantId = normalizeTenant(tenantId);
        String normalizedTaskId = normalizeTaskId(taskId);
        AgentTaskFeedbackRequest feedback = copyAndValidate(request);

        AgentTaskResponse persisted = taskService.persistFeedback(normalizedTenantId, normalizedTaskId, feedback);

        BlockingQueue<FeedbackCommand> queue = tenantQueues.computeIfAbsent(
            normalizedTenantId,
            ignored -> new LinkedBlockingQueue<>(properties.getQueueCapacity())
        );
        boolean queued = queue.offer(new FeedbackCommand(normalizedTenantId, normalizedTaskId, feedback));
        if (!queued) {
            log.warn("Agent feedback was persisted but experience attribution queue is full for tenant={}, taskId={}",
                normalizedTenantId, normalizedTaskId);
            return persisted;
        }
        startWorker(normalizedTenantId);
        return persisted;
    }

    /**
     * Performs the shutdown operation.
     */
    @PreDestroy
    public void shutdown() {
        stopping = true;
    }

    private void startWorker(String tenantId) {
        AtomicBoolean started = workerStates.computeIfAbsent(tenantId, ignored -> new AtomicBoolean(false));
        if (!started.compareAndSet(false, true)) {
            return;
        }
        taskExecutor.submit(() -> consumeTenantQueue(tenantId, started));
    }

    private void consumeTenantQueue(String tenantId, AtomicBoolean started) {
        log.info("Agent feedback worker started for tenant={}", tenantId);
        BlockingQueue<FeedbackCommand> queue = tenantQueues.get(tenantId);
        int idlePolls = 0;
        try {
            while (!stopping) {
                if (queue == null) {
                    break;
                }
                FeedbackCommand command = queue.poll(1, TimeUnit.SECONDS);
                if (command == null) {
                    idlePolls++;
                    if (idlePolls >= MAX_IDLE_POLLS) {
                        break;
                    }
                    continue;
                }
                idlePolls = 0;
                process(command);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            started.set(false);
            if (queue != null && queue.isEmpty()) {
                tenantQueues.remove(tenantId, queue);
            } else if (!stopping) {
                startWorker(tenantId);
            }
            log.info("Agent feedback worker stopped for tenant={}", tenantId);
        }
    }

    private void process(FeedbackCommand command) {
        try {
            taskService.recordFeedbackExperience(command.tenantId(), command.taskId(), command.request());
        } catch (IllegalArgumentException ex) {
            log.warn(
                "Discarded invalid Agent feedback for tenant={}, taskId={}: {}",
                command.tenantId(),
                command.taskId(),
                ex.getMessage()
            );
        } catch (Exception ex) {
            log.error("Failed to process Agent feedback for tenant={}, taskId={}", command.tenantId(), command.taskId(), ex);
        }
    }

    private AgentTaskFeedbackRequest copyAndValidate(AgentTaskFeedbackRequest request) {
        if (request == null || (request.getUseful() == null
            && request.getAdopted() == null
            && request.getResolved() == null
            && (request.getComment() == null || request.getComment().isBlank())
            && (request.getReasonCategory() == null || request.getReasonCategory().isBlank()))) {
            throw new IllegalArgumentException("Feedback cannot be empty");
        }
        AgentTaskFeedbackRequest copy = new AgentTaskFeedbackRequest();
        copy.setTenantId(request.getTenantId());
        copy.setUserId(request.getUserId());
        copy.setUseful(request.getUseful());
        copy.setAdopted(request.getAdopted());
        copy.setResolved(request.getResolved());
        copy.setComment(request.getComment());
        copy.setReasonCategory(request.getReasonCategory());
        return copy;
    }

    private String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenantId.trim();
    }

    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return taskId.trim();
    }

    private record FeedbackCommand(String tenantId, String taskId, AgentTaskFeedbackRequest request) {
    }
}
