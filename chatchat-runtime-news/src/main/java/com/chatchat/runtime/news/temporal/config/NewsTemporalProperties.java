package com.chatchat.runtime.news.temporal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.runtime.news.temporal")
public class NewsTemporalProperties {
    private boolean enabled = true;
    private String target = "127.0.0.1:7233";
    private String namespace = "default";
    private String taskQueue = "chatchat-news-collection";
    private boolean tlsEnabled;
    private long reconcileDelayMillis = 30_000L;
    private long catchupWindowSeconds = 86_400L;
    private long activityStartToCloseSeconds = 7_200L;
    private int activityMaximumAttempts = 3;
    private int maxConcurrentActivities = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getTaskQueue() { return taskQueue; }
    public void setTaskQueue(String taskQueue) { this.taskQueue = taskQueue; }
    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }
    public long getReconcileDelayMillis() { return reconcileDelayMillis; }
    public void setReconcileDelayMillis(long reconcileDelayMillis) { this.reconcileDelayMillis = reconcileDelayMillis; }
    public long getCatchupWindowSeconds() { return catchupWindowSeconds; }
    public void setCatchupWindowSeconds(long catchupWindowSeconds) { this.catchupWindowSeconds = catchupWindowSeconds; }
    public long getActivityStartToCloseSeconds() { return activityStartToCloseSeconds; }
    public void setActivityStartToCloseSeconds(long activityStartToCloseSeconds) { this.activityStartToCloseSeconds = activityStartToCloseSeconds; }
    public int getActivityMaximumAttempts() { return activityMaximumAttempts; }
    public void setActivityMaximumAttempts(int activityMaximumAttempts) { this.activityMaximumAttempts = activityMaximumAttempts; }
    public int getMaxConcurrentActivities() { return maxConcurrentActivities; }
    public void setMaxConcurrentActivities(int maxConcurrentActivities) { this.maxConcurrentActivities = maxConcurrentActivities; }

    public String target() { return text(target, "127.0.0.1:7233"); }
    public String namespace() { return text(namespace, "default"); }
    public String taskQueue() { return text(taskQueue, "chatchat-news-collection"); }
    public long reconcileDelayMillis() { return Math.max(1_000L, reconcileDelayMillis); }
    public long catchupWindowSeconds() { return Math.max(60L, catchupWindowSeconds); }
    public long activityStartToCloseSeconds() { return Math.max(60L, activityStartToCloseSeconds); }
    public int activityMaximumAttempts() { return Math.max(1, activityMaximumAttempts); }
    public int maxConcurrentActivities() { return Math.max(1, maxConcurrentActivities); }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
