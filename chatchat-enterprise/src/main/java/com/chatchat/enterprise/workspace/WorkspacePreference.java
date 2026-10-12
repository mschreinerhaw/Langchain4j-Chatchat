package com.chatchat.enterprise.workspace;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(name = "sys_workspace_user_preference", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_preference_user", columnNames = {"tenant_id", "user_id"}))
public class WorkspacePreference extends EnterpriseAuditable {
    @Column(name = "tenant_id", length = 64, nullable = false) private String tenantId;
    @Column(name = "user_id", length = 64, nullable = false) private String userId;
    @Column(name = "default_workspace_id", length = 64) private String defaultWorkspaceId;
    @Column(name = "last_workspace_id", length = 64) private String lastWorkspaceId;
    @Version @Column(nullable = false) private long revision;
}
