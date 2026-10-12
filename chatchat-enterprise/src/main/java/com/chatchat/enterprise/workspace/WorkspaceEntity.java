package com.chatchat.enterprise.workspace;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(name = "sys_workspace", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_tenant_code", columnNames = {"tenant_id", "workspace_id"}))
public class WorkspaceEntity extends EnterpriseAuditable {
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "workspace_id", length = 64, nullable = false) private String workspaceId;
    @Column(name = "manifest_json", nullable = false, length = org.hibernate.Length.LONG32) private String manifestJson;
    @Column(name = "access_permission_code", length = 128) private String accessPermissionCode;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "is_default", nullable = false) private boolean defaultWorkspace;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Version @Column(nullable = false) private long revision;
}
