package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "sys_audit_log")
public class SysAuditLog {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 64)
    private String actorId;

    @Column(length = 128)
    private String actorName;

    @Column(length = 64, nullable = false)
    private String moduleName;

    @Column(length = 64, nullable = false)
    private String actionName;

    @Column(length = 64)
    private String resourceType;

    @Column(length = 64)
    private String resourceId;

    @Column(length = 4000)
    private String detail;

    @Column(length = 32, nullable = false)
    private String result = "success";

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
