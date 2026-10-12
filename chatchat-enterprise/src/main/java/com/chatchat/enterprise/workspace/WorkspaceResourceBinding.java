package com.chatchat.enterprise.workspace;
import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Getter @Setter
@Table(name = "sys_workspace_resource_binding", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_resource", columnNames = {"tenant_id", "resource_type", "resource_id"}))
public class WorkspaceResourceBinding extends EnterpriseAuditable {
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "resource_type", length = 32, nullable = false) private String resourceType;
    @Column(name = "resource_id", length = 128, nullable = false) private String resourceId;
    @Column(name = "origin_workspace_id", length = 64, nullable = false) private String originWorkspaceId;
    @Column(name = "owner_id", length = 64, nullable = false) private String ownerId;
}
