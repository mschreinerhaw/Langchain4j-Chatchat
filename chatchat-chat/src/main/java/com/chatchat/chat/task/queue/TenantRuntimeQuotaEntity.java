package com.chatchat.chat.task.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "tenant_runtime_quota")
public class TenantRuntimeQuotaEntity {
    @Id
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "active_runs", nullable = false)
    private Integer activeRuns = 0;

    @Column(name = "max_concurrent_runs", nullable = false)
    private Integer maxConcurrentRuns = 4;

    @Column(name = "last_dispatch_at")
    private Instant lastDispatchAt;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;
}
