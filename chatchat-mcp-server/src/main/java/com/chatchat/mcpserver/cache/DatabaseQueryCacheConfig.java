package com.chatchat.mcpserver.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_database_query_cache_config")
public class DatabaseQueryCacheConfig {

    public static final String DEFAULT_ID = "default";

    @Id
    @Column(length = 64)
    private String id = DEFAULT_ID;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false)
    private int defaultTtlSeconds = 300;

    @Column(nullable = false)
    private int maxRows = 1000;

    @Column(nullable = false)
    private int maxEntryKb = 512;

    @Column(nullable = false, length = 64)
    private String keyStrategy = "SQL_PARAMS_DATASOURCE";

    @Column(nullable = false)
    private boolean cacheEmptyResults = false;

    @Column(nullable = false)
    private boolean cacheErrorResults = false;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) {
            id = DEFAULT_ID;
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public void setDefaultTtlSeconds(int defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getMaxEntryKb() { return maxEntryKb; }
    public void setMaxEntryKb(int maxEntryKb) { this.maxEntryKb = maxEntryKb; }
    public String getKeyStrategy() { return keyStrategy; }
    public void setKeyStrategy(String keyStrategy) { this.keyStrategy = keyStrategy; }
    public boolean isCacheEmptyResults() { return cacheEmptyResults; }
    public void setCacheEmptyResults(boolean cacheEmptyResults) { this.cacheEmptyResults = cacheEmptyResults; }
    public boolean isCacheErrorResults() { return cacheErrorResults; }
    public void setCacheErrorResults(boolean cacheErrorResults) { this.cacheErrorResults = cacheErrorResults; }
    public Instant getUpdatedAt() { return updatedAt; }
}
