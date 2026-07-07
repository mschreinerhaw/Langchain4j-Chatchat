package com.chatchat.mcpserver.sql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_trading_calendar_config")
public class TradingCalendarConfig {

    public static final String DEFAULT_ID = "default";
    public static final String DEFAULT_SQL = "select ZRR,JYR from dsc_cfg.t_xtjyr order by ZRR";

    @Id
    @Column(length = 64)
    private String id = DEFAULT_ID;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(length = 64)
    private String datasourceId;

    @Lob
    @Column
    private String sqlTemplate = DEFAULT_SQL;

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
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getSqlTemplate() { return sqlTemplate; }
    public void setSqlTemplate(String sqlTemplate) { this.sqlTemplate = sqlTemplate; }
    public Instant getUpdatedAt() { return updatedAt; }
}
