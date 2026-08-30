package com.chatchat.runtime.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.agent-runtime.temporal")
public class TemporalWorkflowProperties {

    private String target = "127.0.0.1:7233";
    private String namespace = "default";
    private String taskQueue = "chatchat-agent-runtime";
    private long activityStartToCloseSeconds = 86_400L;
    private long activityHeartbeatSeconds = 5L;
    private int activityMaximumAttempts = 1;

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }

    public long getActivityStartToCloseSeconds() {
        return activityStartToCloseSeconds;
    }

    public void setActivityStartToCloseSeconds(long activityStartToCloseSeconds) {
        this.activityStartToCloseSeconds = activityStartToCloseSeconds;
    }

    public long getActivityHeartbeatSeconds() {
        return activityHeartbeatSeconds;
    }

    public void setActivityHeartbeatSeconds(long activityHeartbeatSeconds) {
        this.activityHeartbeatSeconds = activityHeartbeatSeconds;
    }

    public int getActivityMaximumAttempts() {
        return activityMaximumAttempts;
    }

    public void setActivityMaximumAttempts(int activityMaximumAttempts) {
        this.activityMaximumAttempts = activityMaximumAttempts;
    }

    public String target() {
        return text(target, "127.0.0.1:7233");
    }

    public String namespace() {
        return text(namespace, "default");
    }

    public String taskQueue() {
        return text(taskQueue, "chatchat-agent-runtime");
    }

    public long activityStartToCloseSeconds() {
        return Math.max(60L, activityStartToCloseSeconds);
    }

    public long activityHeartbeatSeconds() {
        return Math.max(1L, Math.min(activityStartToCloseSeconds(), activityHeartbeatSeconds));
    }

    public int activityMaximumAttempts() {
        return Math.max(1, activityMaximumAttempts);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
