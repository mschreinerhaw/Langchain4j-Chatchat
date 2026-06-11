package com.chatchat.chat.task;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentTaskCancellationRegistry {

    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();

    /**
     * Returns whether cancel task.
     *
     * @param taskId the task id value
     */
    public void cancelTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            cancelledTaskIds.add(taskId.trim());
        }
    }

    /**
     * Returns whether is cancelled.
     *
     * @param taskId the task id value
     * @return whether the condition is satisfied
     */
    public boolean isCancelled(String taskId) {
        return taskId != null && cancelledTaskIds.contains(taskId.trim());
    }

    /**
     * Performs the clear operation.
     *
     * @param taskId the task id value
     */
    public void clear(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            cancelledTaskIds.remove(taskId.trim());
        }
    }
}
