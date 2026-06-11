package com.chatchat.chat.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
    name = "mcp_user_tool_policy",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_mcp_user_tool_policy_lookup", columnNames = {"tenant_id", "user_id", "tool_name"})
    },
    indexes = {
        @Index(name = "idx_mcp_user_tool_policy_lookup", columnList = "tenant_id, user_id, tool_name")
    }
)
public class McpUserToolPolicyEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "tool_name", length = 256, nullable = false)
    private String toolName;

    @Column(length = 32, nullable = false)
    private String action;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
