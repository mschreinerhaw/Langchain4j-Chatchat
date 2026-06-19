package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "embed_login_token")
public class EmbedLoginToken extends EnterpriseAuditable {

    @Column(length = 512, nullable = false, unique = true)
    private String token;

    @Column(length = 24, nullable = false)
    private String tokenPreview;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64, nullable = false)
    private String username;

    @Column(length = 128, nullable = false)
    private String displayName;

    @Column
    private Instant expiresAt;

    @Column(length = 32, nullable = false)
    private String status = "active";

    @Column(length = 64)
    private String createdBy;

    @Column(length = 128)
    private String createdByName;

    @Column
    private Instant lastUsedAt;

    @Column(nullable = false)
    private long usedCount = 0L;

    @Column
    private Instant revokedAt;
}
