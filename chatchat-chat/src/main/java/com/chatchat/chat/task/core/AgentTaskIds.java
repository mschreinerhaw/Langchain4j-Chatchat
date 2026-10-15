package com.chatchat.chat.task.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable task/session defaults shared by transport entry points and task acceptance. */
public final class AgentTaskIds {
    private AgentTaskIds() { }
    public static String taskId(String tenant, String key) {
        return UUID.nameUUIDFromBytes(("agent-task:"+tenant+":"+key).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static String sessionId(String tenant, String key) {
        return key==null || key.isBlank() ? UUID.randomUUID().toString() : taskId(tenant,"session:"+key.trim());
    }
}
