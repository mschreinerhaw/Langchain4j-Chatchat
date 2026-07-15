package com.chatchat.mcpserver.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_redis_cache_config")
public class RedisCacheConfig {

    public static final String DEFAULT_ID = "default";

    @Id
    @Column(length = 64)
    private String id = DEFAULT_ID;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 32)
    private String mode = "STANDALONE_NO_AUTH";

    @Lob
    @Column(columnDefinition = "longtext")
    private String nodesJson = "[\"127.0.0.1:6379\"]";

    @Column(length = 128)
    private String masterName;

    @Column(nullable = false)
    private int databaseIndex;

    @Column(length = 128)
    private String username;

    @Column(length = 1000)
    private String password;

    @Column(length = 128)
    private String sentinelUsername;

    @Column(length = 1000)
    private String sentinelPassword;

    @Column(name = "ssl_enabled", nullable = false)
    private boolean ssl;

    @Column(nullable = false)
    private int timeoutMillis = 3000;

    @Column(nullable = false)
    private int maxRedirects = 5;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null || id.isBlank()) id = DEFAULT_ID;
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
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getNodesJson() { return nodesJson; }
    public void setNodesJson(String nodesJson) { this.nodesJson = nodesJson; }
    public String getMasterName() { return masterName; }
    public void setMasterName(String masterName) { this.masterName = masterName; }
    public int getDatabaseIndex() { return databaseIndex; }
    public void setDatabaseIndex(int databaseIndex) { this.databaseIndex = databaseIndex; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSentinelUsername() { return sentinelUsername; }
    public void setSentinelUsername(String sentinelUsername) { this.sentinelUsername = sentinelUsername; }
    public String getSentinelPassword() { return sentinelPassword; }
    public void setSentinelPassword(String sentinelPassword) { this.sentinelPassword = sentinelPassword; }
    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }
    public Instant getUpdatedAt() { return updatedAt; }
}
