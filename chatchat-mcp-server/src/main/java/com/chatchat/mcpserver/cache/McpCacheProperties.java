package com.chatchat.mcpserver.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.cache")
public class McpCacheProperties {

    private boolean enabled = true;
    private String path = "./data/mcp-cache-rocksdb";
    private boolean createIfMissing = true;
    private long cleanupIntervalMs = 60000;
    private int cleanupBatchSize = 500;

    /**
     * Returns whether is enabled.
     *
     * @return whether the condition is satisfied
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the enabled.
     *
     * @param enabled the enabled value
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the path.
     *
     * @return the path
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the path.
     *
     * @param path the path value
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Returns whether is create if missing.
     *
     * @return whether the condition is satisfied
     */
    public boolean isCreateIfMissing() {
        return createIfMissing;
    }

    /**
     * Sets the create if missing.
     *
     * @param createIfMissing the create if missing value
     */
    public void setCreateIfMissing(boolean createIfMissing) {
        this.createIfMissing = createIfMissing;
    }

    /**
     * Returns the cleanup interval ms.
     *
     * @return the cleanup interval ms
     */
    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    /**
     * Sets the cleanup interval ms.
     *
     * @param cleanupIntervalMs the cleanup interval ms value
     */
    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    /**
     * Returns the cleanup batch size.
     *
     * @return the cleanup batch size
     */
    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    /**
     * Sets the cleanup batch size.
     *
     * @param cleanupBatchSize the cleanup batch size value
     */
    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
