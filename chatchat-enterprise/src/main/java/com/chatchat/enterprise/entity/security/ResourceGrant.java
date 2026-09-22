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
@Table(name = "resource_grant", indexes = {
    @Index(name = "idx_resource_grant_scope", columnList = "tenant_id,resource_type,resource_id")
})
public class ResourceGrant extends EnterpriseAuditable {
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType;
    @Column(name = "resource_id", length = 128, nullable = false)
    private String resourceId;
    @Column(name = "principal_type", length = 16, nullable = false)
    private String principalType;
    @Column(name = "principal_id", length = 64, nullable = false)
    private String principalId;
    @Column(length = 8, nullable = false)
    private String effect;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "expires_at")
    private Instant expiresAt;
}
