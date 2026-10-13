package com.chatchat.enterprise.entity.workspace;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.*;
import lombok.*;

/** Provenance only; this entity is deliberately not an ACL or execution state. */
@Entity @Table(name = "workspace_resource_origin", uniqueConstraints =
    @UniqueConstraint(name = "uk_workspace_origin_resource", columnNames = {"tenant_id", "resource_type", "resource_id"}))
@Getter @Setter
public class WorkspaceResourceOrigin extends EnterpriseAuditable {
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "resource_type", nullable = false, length = 32) private String resourceType;
    @Column(name = "resource_id", nullable = false, length = 128) private String resourceId;
    @Column(nullable = false, length = 64) private String workspaceId;
    @Column(nullable = false, length = 64) private String creatorUserId;
}
