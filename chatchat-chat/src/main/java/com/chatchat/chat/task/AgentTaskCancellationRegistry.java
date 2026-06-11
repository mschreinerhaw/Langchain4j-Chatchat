package com.chatchat.chat.task;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentTaskCancellationRegistry {

    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();

    public void cancelTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            cancelledTaskIds.add(taskId.trim());
        }
    }

    public boolean isCancelled(String taskId) {
        return taskId != null && cancelledTaskIds.contains(taskId.trim());
    }

    public void clear(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            cancelledTaskIds.remove(taskId.trim());
        }
    }
}
