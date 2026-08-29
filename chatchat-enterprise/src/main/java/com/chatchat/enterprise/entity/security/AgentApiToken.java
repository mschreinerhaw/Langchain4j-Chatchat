package com.chatchat.enterprise.entity.security;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
    name = "agent_api_token",
    indexes = {
        @Index(name = "idx_agent_api_token_user", columnList = "tenant_id, user_id"),
        @Index(name = "idx_agent_api_token_status", columnList = "status, expires_at")
    }
)
public class AgentApiToken extends EnterpriseAuditable {

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "token_preview", length = 32, nullable = false)
    private String tokenPreview;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "username", length = 64, nullable = false)
    private String username;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    @Column(name = "token_name", length = 128, nullable = false)
    private String tokenName;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "active";

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_used_ip", length = 128)
    private String lastUsedIp;

    @Column(name = "last_used_path", length = 512)
    private String lastUsedPath;

    @Column(name = "used_count", nullable = false)
    private long usedCount;

    @Column(name = "created_by", length = 64, nullable = false)
    private String createdBy;

    @Column(name = "created_by_name", length = 128, nullable = false)
    private String createdByName;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 64)
    private String revokedBy;

    @Column(name = "rotated_at")
    private Instant rotatedAt;
}
