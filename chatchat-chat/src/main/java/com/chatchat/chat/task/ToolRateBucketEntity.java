package com.chatchat.chat.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "tenant_tool_rate_bucket", indexes =
    @Index(name = "idx_tool_rate_bucket_expiry", columnList = "expires_at"))
public class ToolRateBucketEntity {
    @Id
    @Column(name = "bucket_id", length = 512, nullable = false)
    private String bucketId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "tool_name", length = 200, nullable = false)
    private String toolName;

    @Column(name = "window_type", length = 16, nullable = false)
    private String windowType;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_tokens", nullable = false)
    private Integer usedTokens = 0;

    @Column(name = "token_limit", nullable = false)
    private Integer tokenLimit;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;
}
