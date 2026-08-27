package com.chatchat.mcpserver.news.financial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_financial_query_cache_config")
public class FinancialQueryCacheConfig {
    public static final String DEFAULT_ID = "default";

    @Id
    @Column(length = 64)
    private String id = DEFAULT_ID;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false, length = 16)
    private String storage = "ROCKSDB";
    @Column(nullable = false)
    private long ttlSeconds = 1800L;
    @Column(nullable = false)
    private boolean fallbackToRocksDb = true;
    @Column(nullable = false)
    private int maxEntryKb = 2048;
    @Column(nullable = false)
    private long singleFlightGraceMs = 500L;
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() { if (id == null || id.isBlank()) id = DEFAULT_ID; updatedAt = Instant.now(); }
    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStorage() { return storage; }
    public void setStorage(String storage) { this.storage = storage; }
    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public boolean isFallbackToRocksDb() { return fallbackToRocksDb; }
    public void setFallbackToRocksDb(boolean fallbackToRocksDb) { this.fallbackToRocksDb = fallbackToRocksDb; }
    public int getMaxEntryKb() { return maxEntryKb; }
    public void setMaxEntryKb(int maxEntryKb) { this.maxEntryKb = maxEntryKb; }
    public long getSingleFlightGraceMs() { return singleFlightGraceMs; }
    public void setSingleFlightGraceMs(long singleFlightGraceMs) { this.singleFlightGraceMs = singleFlightGraceMs; }
    public Instant getUpdatedAt() { return updatedAt; }
}
