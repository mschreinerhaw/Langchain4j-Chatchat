package com.chatchat.chat.task;

import java.util.List;

public interface AgentEventStore {

    String save(AgentEvent event);

    List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit);
}
