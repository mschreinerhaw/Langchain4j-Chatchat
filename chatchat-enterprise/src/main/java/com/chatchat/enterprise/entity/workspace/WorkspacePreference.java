package com.chatchat.enterprise.entity.workspace;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "workspace_user_preference", uniqueConstraints =
    @UniqueConstraint(name = "uk_workspace_preference_user", columnNames = {"tenant_id", "user_id"}))
@Getter @Setter
public class WorkspacePreference extends EnterpriseAuditable {
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "user_id", nullable = false, length = 64) private String userId;
    @Column(length = 64) private String defaultWorkspaceId;
    @Column(length = 64) private String lastWorkspaceId;
    @Version private Long version;
}
